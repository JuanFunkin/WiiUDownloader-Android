package io.github.juanfunkin.wiiudownloader

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import io.github.juanfunkin.wiiu.Wiiu
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Process-wide state shared by the UI and the download service. */
object Repo {
    private lateinit var appContext: Context
    private lateinit var prefs: SharedPreferences
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sizeLimiter = Semaphore(3)

    private val _settings = MutableStateFlow(Settings())
    val settings: StateFlow<Settings> = _settings

    private val _queue = MutableStateFlow<List<QueueItem>>(emptyList())
    val queue: StateFlow<List<QueueItem>> = _queue

    private val _run = MutableStateFlow(RunState())
    val run: StateFlow<RunState> = _run

    val databaseSize: Int get() = Wiiu.databaseSize()

    fun init(context: Context) {
        appContext = context.applicationContext
        prefs = appContext.getSharedPreferences("wiiudownloader", Context.MODE_PRIVATE)
        Wiiu.init(File(appContext.cacheDir, "go").absolutePath)

        _settings.value = Settings(
            treeUri = prefs.getString("treeUri", null),
            folderName = prefs.getString("folderName", null),
            decrypt = prefs.getBoolean("decrypt", true),
            deleteEncrypted = prefs.getBoolean("deleteEncrypted", true),
            continueOnError = prefs.getBoolean("continueOnError", true),
        )
        loadQueue()
    }

    // ---- Settings -------------------------------------------------------

    fun setFolder(context: Context, uri: Uri) {
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        } catch (_: SecurityException) {
            // The provider did not offer a persistable grant; the picker grant still works for now.
        }
        val name = DocumentFile.fromTreeUri(context, uri)?.name
        prefs.edit().putString("treeUri", uri.toString()).putString("folderName", name).apply()
        _settings.update { it.copy(treeUri = uri.toString(), folderName = name) }
    }

    fun setDecrypt(value: Boolean) {
        prefs.edit().putBoolean("decrypt", value).apply()
        _settings.update { it.copy(decrypt = value) }
    }

    fun setDeleteEncrypted(value: Boolean) {
        prefs.edit().putBoolean("deleteEncrypted", value).apply()
        _settings.update { it.copy(deleteEncrypted = value) }
    }

    fun setContinueOnError(value: Boolean) {
        prefs.edit().putBoolean("continueOnError", value).apply()
        _settings.update { it.copy(continueOnError = value) }
    }

    // ---- Library --------------------------------------------------------

    /** category: all, game, update, dlc, demo. regionMask: 1=JPN 2=USA 4=EUR, 0=any. */
    fun search(query: String, category: String, regionMask: Int, limit: Int = 400): List<Title> =
        // The database can list a title id more than once; the list needs unique keys.
        parseTitles(Wiiu.searchTitles(query, category, regionMask, limit)).distinctBy { it.tid }

    /** Returns the update and the DLC that belong to a game, if the database knows them. */
    fun related(tid: String): Pair<Title?, Title?> {
        val obj = JSONObject(Wiiu.relatedTitles(tid))
        val update = obj.optJSONObject("update")?.let(::parseTitle)
        val dlc = obj.optJSONObject("dlc")?.let(::parseTitle)
        return update to dlc
    }

    private fun parseTitle(o: JSONObject) = Title(
        tid = o.getString("tid"),
        name = o.getString("name"),
        kind = o.getString("kind"),
        region = o.getString("region"),
        regionMask = o.optInt("regionMask", 0),
    )

    private fun parseTitles(json: String): List<Title> {
        val arr = JSONArray(json)
        return List(arr.length()) { parseTitle(arr.getJSONObject(it)) }
    }

    // ---- Queue ----------------------------------------------------------

    fun enqueue(titles: List<Title>) {
        val existing = _queue.value.map { it.title.tid }.toSet()
        val fresh = titles.distinctBy { it.tid }.filter { it.tid !in existing }
        if (fresh.isEmpty()) return
        _queue.update { it + fresh.map { t -> QueueItem(t) } }
        saveQueue()
        fresh.forEach(::fetchSize)
    }

    fun isQueued(tid: String): Boolean = _queue.value.any { it.title.tid == tid }

    fun removeFromQueue(tid: String) {
        _queue.update { list -> list.filterNot { it.title.tid == tid } }
        saveQueue()
    }

    fun clearQueue() {
        _queue.value = emptyList()
        saveQueue()
    }

    private fun fetchSize(title: Title) {
        scope.launch {
            val size = sizeLimiter.withPermit {
                try {
                    Wiiu.titleSize(title.tid, -1)
                } catch (_: Exception) {
                    -1L
                }
            }
            _queue.update { list ->
                list.map { if (it.title.tid == title.tid) it.copy(sizeBytes = size) else it }
            }
        }
    }

    private fun saveQueue() {
        val arr = JSONArray()
        _queue.value.forEach { item ->
            arr.put(
                JSONObject()
                    .put("tid", item.title.tid)
                    .put("name", item.title.name)
                    .put("kind", item.title.kind)
                    .put("region", item.title.region)
                    .put("regionMask", item.title.regionMask)
                    .put("version", item.version),
            )
        }
        prefs.edit().putString("queue", arr.toString()).apply()
    }

    private fun loadQueue() {
        val json = prefs.getString("queue", null) ?: return
        try {
            val arr = JSONArray(json)
            val items = List(arr.length()) {
                val o = arr.getJSONObject(it)
                QueueItem(parseTitle(o), o.optInt("version", -1))
            }
            _queue.value = items
            items.forEach { fetchSize(it.title) }
        } catch (_: Exception) {
            _queue.value = emptyList()
        }
    }

    // ---- Run ------------------------------------------------------------

    fun updateRun(transform: (RunState) -> RunState) = _run.update(transform)

    fun clearRun() {
        if (!_run.value.active) _run.value = RunState()
    }

    fun canStart(): Boolean =
        !_run.value.active && _queue.value.isNotEmpty() && _settings.value.treeUri != null

    fun startDownload(context: Context) {
        if (!canStart()) return
        val intent = Intent(context, DownloadService::class.java).setAction(DownloadService.ACTION_START)
        ContextCompat.startForegroundService(context, intent)
    }

    fun stagingDir(): File {
        val base = appContext.getExternalFilesDir(null) ?: appContext.filesDir
        return File(base, "staging")
    }

    fun clearStaging(): Boolean =
        if (_run.value.active) false else stagingDir().deleteRecursively()

    fun stagingBytes(): Long = stagingDir().walkTopDown().filter { it.isFile }.sumOf { it.length() }
}
