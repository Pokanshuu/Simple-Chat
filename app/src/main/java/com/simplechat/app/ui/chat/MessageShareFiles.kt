package com.simplechat.app.ui.chat

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.simplechat.app.R
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal const val MAX_SHARE_TEXT_CHARS = 100_000

internal fun shareDirectory(context: Context): File {
    val root = File(context.cacheDir, "shares").apply { mkdirs() }
    // Recipient apps may read a shared URI after our chooser closes. Retain it for a day.
    root.listFiles()?.filter { it.isDirectory && System.currentTimeMillis() - it.lastModified() > 24L * 60 * 60 * 1000 }
        ?.forEach { it.deleteRecursively() }
    return File(root, UUID.randomUUID().toString()).apply { check(mkdirs()) }
}

internal fun shareFileName(title: String): String = title.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
    .trim().trim('.').take(70).ifBlank { "SimpleChat" }

internal suspend fun exportSharedMarkdown(context: Context, document: MessageShareDocument): File = withContext(Dispatchers.IO) {
    val directory = shareDirectory(context)
    try {
        currentCoroutineContext().ensureActive()
        File(directory, "${shareFileName(document.title)}.md").also { file ->
            file.bufferedWriter().use { writer ->
                writeSharedMarkdown(document, writer, context.getString(R.string.export_role_me),
                    context.getString(R.string.export_role_ai), context.getString(R.string.share_image_attachment_note))
            }
            currentCoroutineContext().ensureActive()
        }
    } catch (error: Throwable) { directory.deleteRecursively(); throw error }
}

internal fun messageShareIntent(context: Context, files: List<File>, mime: String, title: String): Intent {
    require(files.isNotEmpty())
    val uris = files.map { FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", it) }
    return Intent(if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply {
        type = mime
        putExtra(Intent.EXTRA_SUBJECT, title)
        if (uris.size == 1) putExtra(Intent.EXTRA_STREAM, uris.first())
        else putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        clipData = ClipData.newUri(context.contentResolver, title, uris.first()).apply {
            uris.drop(1).forEach { addItem(ClipData.Item(it)) }
        }
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
