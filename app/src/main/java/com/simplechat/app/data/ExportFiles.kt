package com.simplechat.app.data

import com.simplechat.app.data.archive.ChatArchive
import android.content.ContentResolver
import android.net.Uri
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
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

/**
 * 原子写一个文件：先写同目录临时文件，再换名成目标。
 *
 * `File.renameTo()` 失败**不会抛异常、只返回 false**；在某些容器里（鸿蒙 / 卓易通
 * 把应用私有目录映射到不同挂载点）同目录换名也可能失败 —— 不检查返回值就会
 * 把内容留在 `.tmp` 里、目标只剩 0 字节。这里失败时回退成流式复制并核对字节数，
 * 任何情况下都不留下 0 字节目标文件。
 */
internal fun writeFileAtomically(target: File, write: (File) -> Unit) {
    val directory = target.parentFile ?: throw IOException("Missing parent directory: $target")
    if (!directory.exists() && !directory.mkdirs()) throw IOException("Could not create directory")
    val temporary = File.createTempFile("${target.name}.", ".tmp", directory)
    try {
        write(temporary)
        if (!temporary.isFile || temporary.length() == 0L) throw IOException("Empty file")
        val expected = temporary.length()
        if (temporary.renameTo(target)) return
        target.delete()
        temporary.inputStream().use { input ->
            target.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
        }
        if (target.length() != expected) throw IOException("Incomplete file: ${target.length()}/$expected")
    } catch (error: Throwable) {
        target.delete()
        throw error
    } finally {
        temporary.delete()
    }
}

/** 读回 SAF 目标的实际大小；provider 不支持查询时返回 -1。 */
internal fun destinationSize(resolver: ContentResolver, uri: Uri): Long =
    runCatching { resolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L }.getOrDefault(-1L)

/**
 * 用原生文件描述符把 [source] 再写一遍目标 URI。
 *
 * 个别容器（鸿蒙 / 卓易通）的 DocumentsProvider 会把 `openOutputStream(uri, "wt")`
 * 写进去的内容丢掉，留下 0 字节文件。FD 路径能绕开这类 provider 的流实现。
 */
internal suspend fun copyExportFileToDescriptor(
    resolver: ContentResolver,
    uri: Uri,
    source: File,
    onProgress: (Float) -> Unit = {},
) {
    val descriptor = resolver.openFileDescriptor(uri, "rwt") ?: throw IOException("Could not open destination")
    try {
        copyExportFile(source, { FileOutputStream(descriptor.fileDescriptor) }, onProgress)
    } finally {
        // 流关闭时可能已经把 fd 关掉；重复 close 的异常不该盖住真正的错误。
        runCatching { descriptor.close() }
    }
}
