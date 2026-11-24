package io.github.juanfunkin.wiiudownloader

data class Title(
    val tid: String,
    val name: String,
    val kind: String,
    val region: String,
    val regionMask: Int,
)

/** sizeBytes: null = still loading, a negative value = could not be fetched. */
data class QueueItem(
    val title: Title,
    val version: Int = -1,
    val sizeBytes: Long? = null,
)

data class Settings(
    val treeUri: String? = null,
    val folderName: String? = null,
    val decrypt: Boolean = true,
    val deleteEncrypted: Boolean = true,
    val continueOnError: Boolean = true,
)

enum class Phase { Idle, Downloading, Decrypting, Copying }

data class ResultLine(val name: String, val ok: Boolean, val message: String)

data class RunState(
    val active: Boolean = false,
    val paused: Boolean = false,
    val phase: Phase = Phase.Idle,
    val index: Int = 0,
    val total: Int = 0,
    val title: String = "",
    val downloaded: Long = 0,
    val size: Long = 0,
    val speed: Long = 0,
    /** Progress of the current decrypt / copy phase, 0..1. */
    val fraction: Float = 0f,
    val results: List<ResultLine> = emptyList(),
    val finished: Boolean = false,
    val cancelled: Boolean = false,
)
