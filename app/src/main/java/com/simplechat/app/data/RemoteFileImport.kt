package com.simplechat.app.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.simplechat.app.R
import com.simplechat.app.net.ServiceApi
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Upload directly from a bounded disk staging file, never loading a 100 MiB document into RAM. */
object RemoteFileImport {
    suspend fun document(context: Context, uri: Uri, settings: StoredSettings): Result<AttachmentImport.DocumentImport> =
        withContext(Dispatchers.IO) {
            var staged: File? = null
            try {
                val config = settings.filesApi
                val endpoint = config.endpoint(settings.baseUrl, config.filesPath)
                if (java.net.URI(endpoint).host != java.net.URI(settings.baseUrl).host)
                    throw AppTextException(R.string.service_file_endpoint_mismatch)
                val key = config.key(settings.apiKey)
                val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "document.txt"
                val purpose = ServiceApi.purpose(name, config.purpose)
                val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
                val limit = (if (purpose != "user_data") 100L else if (mime.startsWith("image/")) 64L else 32L) * 1024 * 1024
                val file = File.createTempFile("file-upload-", ".tmp", context.cacheDir).also { staged = it }
                context.contentResolver.openInputStream(uri)?.use { input ->
                    file.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            coroutineContext.ensureActive()
                            val size = input.read(buffer)
                            if (size < 0) break
                            total += size
                            if (total > limit) throw AppTextException(R.string.service_file_too_large, limit / 1024 / 1024)
                            output.write(buffer, 0, size)
                        }
                    }
                } ?: throw AppTextException(R.string.err_file_read)
                if (file.length() == 0L) throw AppTextException(R.string.err_file_empty)
                val localText = if (purpose == "user_data" && file.length() <= 1_000_000 && !mime.startsWith("image/") &&
                    name.substringAfterLast('.', "").lowercase() !in listOf("xls", "xlsx", "epub", "mobi")) runCatching {
                    Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(file.readBytes())).toString()
                }.getOrDefault("") else ""
                val id = ServiceApi().upload(endpoint, key, file, name, mime, purpose)
                Result.success(AttachmentImport.DocumentImport(listOf(Attachment(
                    kind = Attachment.Kind.TEXT, name = name, mime = mime, data = localText,
                    fileId = id, fileEndpoint = endpoint, fileAccount = FileApiTextCache.fingerprint(key),
                    remoteImage = purpose == "user_data" && mime.startsWith("image/"),
                ))))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { Result.failure(error) }
            finally { staged?.delete() }
        }
}
