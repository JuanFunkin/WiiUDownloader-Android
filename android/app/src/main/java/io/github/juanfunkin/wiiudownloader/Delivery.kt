package io.github.juanfunkin.wiiudownloader

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.IOException

/**
 * Moves a finished title from app staging storage into the folder the user picked.
 *
 * Android's scoped storage does not let native code write to an arbitrary folder by
 * path, so Go downloads into the app's own directory and the files are copied through
 * the Storage Access Framework once each title is complete. Files that already exist
 * with the same size are skipped, so a failed copy can be retried.
 */
object Delivery {
    private const val BUFFER_SIZE = 1024 * 1024

    // Never shown to the user: the service catches it and treats it as a cancelled run.
    class CancelledException : IOException("copy cancelled")

    fun deliver(
        context: Context,
        treeUri: Uri,
        sourceDirs: List<File>,
        isCancelled: () -> Boolean,
        onProgress: (Float) -> Unit,
    ) {
        val root = DocumentFile.fromTreeUri(context, treeUri)
            ?: throw IOException(context.getString(R.string.err_folder_gone))
        if (!root.canWrite()) throw IOException(context.getString(R.string.err_no_write))

        val total = sourceDirs.sumOf { sizeOf(it) }.coerceAtLeast(1L)
        var copied = 0L
        val progress = Progress { delta ->
            copied += delta
            onProgress((copied.toFloat() / total).coerceIn(0f, 1f))
        }

        for (dir in sourceDirs) {
            val target = childDir(context, root, directoryChildren(root), dir.name)
            copyDirectory(context, dir, target, isCancelled, progress)
        }
        sourceDirs.forEach { it.deleteRecursively() }
    }

    private fun interface Progress {
        fun add(delta: Long)
    }

    private fun sizeOf(dir: File): Long =
        dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /** One listing per directory keeps this linear; looking each file up by name would not be. */
    private fun directoryChildren(dir: DocumentFile): MutableMap<String, DocumentFile> {
        val map = HashMap<String, DocumentFile>()
        for (child in dir.listFiles()) {
            val name = child.name ?: continue
            map[name] = child
        }
        return map
    }

    private fun childDir(
        context: Context,
        parent: DocumentFile,
        siblings: MutableMap<String, DocumentFile>,
        name: String,
    ): DocumentFile {
        val existing = siblings[name]
        if (existing != null && existing.isDirectory) return existing
        val created = parent.createDirectory(name)
            ?: throw IOException(context.getString(R.string.err_create_dir, name))
        siblings[name] = created
        return created
    }

    private fun copyDirectory(
        context: Context,
        source: File,
        target: DocumentFile,
        isCancelled: () -> Boolean,
        progress: Progress,
    ) {
        val siblings = directoryChildren(target)
        val entries = source.listFiles()
            ?: throw IOException(context.getString(R.string.err_read_dir, source.name))

        for (entry in entries.sortedBy { it.name }) {
            if (isCancelled()) throw CancelledException()

            if (entry.isDirectory) {
                val sub = childDir(context, target, siblings, entry.name)
                copyDirectory(context, entry, sub, isCancelled, progress)
                continue
            }

            val existing = siblings[entry.name]
            if (existing != null && existing.isFile && existing.length() == entry.length()) {
                progress.add(entry.length())
                continue
            }
            existing?.delete()

            val dest = target.createFile("application/octet-stream", entry.name)
                ?: throw IOException(context.getString(R.string.err_create_file, entry.name))
            siblings[entry.name] = dest

            val out = context.contentResolver.openOutputStream(dest.uri, "w")
                ?: throw IOException(context.getString(R.string.err_write_file, entry.name))
            out.use { stream ->
                entry.inputStream().use { input ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        if (isCancelled()) throw CancelledException()
                        val read = input.read(buffer)
                        if (read < 0) break
                        stream.write(buffer, 0, read)
                        progress.add(read.toLong())
                    }
                }
            }
        }
    }
}
