package io.github.juanfunkin.wiiudownloader

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import io.github.juanfunkin.wiiu.Downloader
import io.github.juanfunkin.wiiu.Listener
import io.github.juanfunkin.wiiu.Wiiu
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Foreground service that owns the Go downloader, so a download keeps going while the
 * app is in the background. Progress is published through [Repo.run].
 */
class DownloadService : Service() {

    companion object {
        const val ACTION_START = "io.github.juanfunkin.wiiudownloader.START"
        const val ACTION_PAUSE = "io.github.juanfunkin.wiiudownloader.PAUSE"
        const val ACTION_RESUME = "io.github.juanfunkin.wiiudownloader.RESUME"
        const val ACTION_CANCEL = "io.github.juanfunkin.wiiudownloader.CANCEL"

        private const val CHANNEL_ID = "downloads"
        private const val ONGOING_ID = 1
        private const val RESULT_ID = 2
        private const val WAKE_LOCK_TIMEOUT_MS = 12L * 60 * 60 * 1000

        /** Sends pause / resume / cancel to the running service. */
        fun send(context: Context, action: String) {
            context.startService(Intent(context, DownloadService::class.java).setAction(action))
        }
    }

    private var downloader: Downloader? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var treeUri: String? = null

    @Volatile
    private var cancelRequested = false
    private var lastNotify = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action != ACTION_START && downloader == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        when (action) {
            ACTION_START -> startRun()
            ACTION_PAUSE -> setPaused(true)
            ACTION_RESUME -> setPaused(false)
            ACTION_CANCEL -> cancelRun()
        }
        return START_NOT_STICKY
    }

    // Android 15 time limits data-sync foreground services; stop cleanly when it fires.
    override fun onTimeout(startId: Int, fgsType: Int) {
        cancelRun()
    }

    override fun onDestroy() {
        downloader?.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    // ---- Run control ----------------------------------------------------

    private fun startRun() {
        if (downloader != null) return

        val settings = Repo.settings.value
        val items = Repo.queue.value
        val uri = settings.treeUri

        // A service started with startForegroundService must call startForeground
        // promptly, even if it is about to stop again.
        createChannel()
        ServiceCompat.startForeground(
            this,
            ONGOING_ID,
            buildOngoing(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )

        if (uri == null || items.isEmpty()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        treeUri = uri
        cancelRequested = false
        Repo.updateRun { RunState(active = true, total = items.size) }

        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WiiUDownloader:download").apply {
            acquire(WAKE_LOCK_TIMEOUT_MS)
        }

        val queueJson = JSONArray().also { arr ->
            items.forEach { arr.put(JSONObject().put("tid", it.title.tid).put("version", it.version)) }
        }.toString()

        try {
            val d = Wiiu.newDownloader(
                Repo.stagingDir().absolutePath,
                settings.decrypt,
                settings.deleteEncrypted,
                settings.continueOnError,
                listener,
            )
            downloader = d
            d.start(queueJson)
        } catch (e: Exception) {
            Repo.updateRun {
                it.copy(results = it.results + ResultLine("Descarga", false, e.message ?: "Error al iniciar"))
            }
            finish(cancelled = false)
        }
    }

    private fun setPaused(paused: Boolean) {
        val d = downloader ?: return
        if (paused) d.pause() else d.resume()
        Repo.updateRun { it.copy(paused = paused) }
        updateOngoing(force = true)
    }

    private fun cancelRun() {
        cancelRequested = true
        downloader?.cancel()
    }

    private fun finish(cancelled: Boolean) {
        val state = Repo.run.value
        Repo.updateRun {
            it.copy(
                active = false,
                paused = false,
                phase = Phase.Idle,
                finished = true,
                cancelled = cancelled,
            )
        }
        releaseWakeLock()
        downloader = null

        val ok = state.results.count { it.ok }
        val failed = state.results.count { !it.ok }
        val text = when {
            cancelled -> "Descarga cancelada"
            failed == 0 -> "Descarga completa ($ok)"
            else -> "Terminó con errores: $ok correctos, $failed con error"
        }

        stopForeground(STOP_FOREGROUND_REMOVE)
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(RESULT_ID, notification)
        stopSelf()
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    // ---- Events coming from Go ------------------------------------------

    private val listener = object : Listener {
        override fun onTitleStarted(index: Int, total: Int, tid: String, name: String) {
            Repo.updateRun {
                it.copy(
                    phase = Phase.Downloading,
                    index = index,
                    total = total,
                    title = name,
                    downloaded = 0,
                    size = 0,
                    speed = 0,
                    fraction = 0f,
                )
            }
            updateOngoing(force = true)
        }

        override fun onProgress(downloaded: Long, total: Long, bytesPerSecond: Long) {
            Repo.updateRun {
                it.copy(
                    phase = Phase.Downloading,
                    downloaded = downloaded,
                    size = total,
                    speed = bytesPerSecond,
                )
            }
            updateOngoing(force = false)
        }

        override fun onDecryptProgress(fraction: Double) {
            Repo.updateRun { it.copy(phase = Phase.Decrypting, fraction = fraction.toFloat()) }
            updateOngoing(force = false)
        }

        // Blocks the Go download loop on purpose: the finished title is moved out of
        // staging before the next one starts, so only one title is ever staged.
        override fun onTitleFinished(
            tid: String,
            name: String,
            success: Boolean,
            message: String,
            encDir: String,
            outDir: String,
        ) {
            if (!success) {
                Repo.updateRun { it.copy(results = it.results + ResultLine(name, false, message)) }
                return
            }

            val dirs = listOf(encDir, outDir).filter { it.isNotEmpty() }.map { File(it) }
            val uri = treeUri ?: return
            Repo.updateRun { it.copy(phase = Phase.Copying, fraction = 0f) }
            updateOngoing(force = true)

            try {
                Delivery.deliver(
                    this@DownloadService,
                    Uri.parse(uri),
                    dirs,
                    { cancelRequested },
                ) { fraction ->
                    Repo.updateRun { it.copy(fraction = fraction) }
                    updateOngoing(force = false)
                }
                Repo.removeFromQueue(tid)
                Repo.updateRun { it.copy(results = it.results + ResultLine(name, true, "")) }
            } catch (_: Delivery.CancelledException) {
                // The run is being cancelled; the staged files are kept.
            } catch (e: Exception) {
                val reason = e.message ?: e.javaClass.simpleName
                Repo.updateRun {
                    it.copy(
                        results = it.results + ResultLine(
                            name,
                            false,
                            "No se pudo copiar a la carpeta elegida: $reason. " +
                                "Los archivos siguen en el almacenamiento temporal.",
                        ),
                    )
                }
                // A destination problem would repeat for every title, so stop here.
                downloader?.cancel()
            }
        }

        override fun onFinished(cancelled: Boolean) {
            finish(cancelled)
        }
    }

    // ---- Notifications --------------------------------------------------

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Descargas", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun actionIntent(action: String, code: Int): PendingIntent = PendingIntent.getService(
        this,
        code,
        Intent(this, DownloadService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun updateOngoing(force: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastNotify < 1000) return
        lastNotify = now
        getSystemService(NotificationManager::class.java).notify(ONGOING_ID, buildOngoing())
    }

    private fun buildOngoing() = Repo.run.value.let { s ->
        val position = if (s.total > 0) " (${s.index + 1}/${s.total})" else ""
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(if (s.title.isEmpty()) "Preparando…" else s.title)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent())

        when {
            s.paused -> builder.setContentText("En pausa$position").setProgress(0, 0, false)
            s.phase == Phase.Decrypting ->
                builder.setContentText("Descifrando$position")
                    .setProgress(100, (s.fraction * 100).toInt(), false)
            s.phase == Phase.Copying ->
                builder.setContentText("Copiando a la carpeta elegida$position")
                    .setProgress(100, (s.fraction * 100).toInt(), false)
            s.size > 0 ->
                builder.setContentText(
                    "${formatBytes(s.downloaded)} / ${formatBytes(s.size)} · ${formatSpeed(s.speed)}$position",
                ).setProgress(1000, ((s.downloaded.toDouble() / s.size) * 1000).toInt().coerceIn(0, 1000), false)
            else -> builder.setContentText("Descargando$position").setProgress(0, 0, true)
        }

        if (s.paused) {
            builder.addAction(0, "Reanudar", actionIntent(ACTION_RESUME, 1))
        } else {
            builder.addAction(0, "Pausar", actionIntent(ACTION_PAUSE, 2))
        }
        builder.addAction(0, "Cancelar", actionIntent(ACTION_CANCEL, 3))
        builder.build()
    }
}
