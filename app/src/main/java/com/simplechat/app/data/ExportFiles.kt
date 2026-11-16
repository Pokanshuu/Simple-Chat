package com.simplechat.app.data

import com.simplechat.app.data.archive.ChatArchive
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.zip.ZipFile
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Prepare and validate before CreateDocument creates a visible destination. */
internal suspend fun stageArchive(file: File, generate: suspend (File) -> Unit): File {
    try {
        generate(file)
        currentCoroutineContext().ensureActive()
        if (file.length() == 0L) throw IOException("Empty archive")
        ZipFile(file).use { zip ->
            if (zip.getEntry(ChatArchive.MANIFEST) == null || zip.getEntry(ChatArchive.BACKUP) == null) {
                throw IOException("Incomplete archive")
            }
        }
        return file
    } catch (error: Throwable) {
        file.delete()
        throw error
    }
}

/** Copy with bounded memory and report success only after the destination closes successfully. */
internal suspend fun copyExportFile(
    source: File,
    openDestination: () -> OutputStream,
    onProgress: (Float) -> Unit = {},
) {
    val expected = source.length()
    if (expected == 0L) throw IOException("Empty export file")
    var written = 0L
    onProgress(0f)
    source.inputStream().buffered().use { input ->
        openDestination().use { output ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                output.write(buffer, 0, count)
                written += count
                onProgress((written.toDouble() / expected).toFloat().coerceIn(0f, 0.99f))
            }
            if (written != expected) throw IOException("Incomplete export: $written/$expected")
            output.flush()
        }
    }
    onProgress(1f)
}
