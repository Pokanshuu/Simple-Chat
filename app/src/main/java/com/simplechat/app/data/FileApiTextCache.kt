package com.simplechat.app.data

import android.content.Context
import com.simplechat.app.R
import com.simplechat.app.net.ServiceApi
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Disk-backed file-ID cache: restart/retry never needs to re-upload identical long user text. */
internal class FileApiTextCache(private val context: Context, private val api: ServiceApi = ServiceApi()) {
    suspend fun prepare(text: String, attachments: List<Attachment>, settings: StoredSettings): Pair<String, List<Attachment>> =
        withContext(Dispatchers.IO) {
            if (!settings.useRemoteFiles) return@withContext text to attachments
            val config = settings.filesApi
            if (text.length < config.longTextChars && attachments.none {
                it.kind == Attachment.Kind.TEXT && it.fileId == null && it.data.length >= config.longTextChars
            }) return@withContext text to attachments
            val endpoint = config.endpoint(settings.baseUrl, config.filesPath)
            val key = config.key(settings.apiKey)
            val chatHost = java.net.URI(settings.baseUrl).host
            if (chatHost != java.net.URI(endpoint).host) throw AppTextException(R.string.service_file_endpoint_mismatch)
            val account = fingerprint(key)
            suspend fun upload(content: String, name: String): Attachment {
                val digest = fingerprint(endpoint + account + name + content)
                val directory = File(context.filesDir, "file-api-cache").apply { mkdirs() }
                val record = File(directory, "$digest.id")
                val cached = if (record.isFile && System.currentTimeMillis() - record.lastModified() < CACHE_TTL_MS)
                    record.readText().takeIf { it.isNotBlank() } else null
                val id = cached ?: run {
                    val staged = File.createTempFile("long-text-", ".txt", context.cacheDir)
                    try {
                        staged.writeText(content)
                        if (staged.length() > 32L * 1024 * 1024) throw AppTextException(R.string.service_file_too_large, 32)
                        api.upload(endpoint, key, staged, name, "text/plain", "user_data").also { value ->
                            // Only metadata is cached. The original is already retained by the message database.
                            val temporary = File(directory, "$digest.tmp")
                            temporary.writeText(value)
                            if (!temporary.renameTo(record)) temporary.delete()
                        }
                    } finally { staged.delete() }
                }
                return Attachment(Attachment.Kind.TEXT, name, "text/plain", "", fileId = id, fileEndpoint = endpoint, fileAccount = account,
                    originalTextChars = content.length)
            }
            val prepared = attachments.map { attachment ->
                if (attachment.kind == Attachment.Kind.TEXT && attachment.fileId == null && attachment.data.length >= config.longTextChars)
                    upload(attachment.data, attachment.name) else attachment
            }.toMutableList()
            val body = if (text.length >= config.longTextChars) {
                prepared += upload(text, "message.txt")
                Res.get(R.string.service_long_text_prompt)
            } else text
            body to prepared
        }

    companion object {
        const val LONG_TEXT_CHARS = 16_000
        private const val CACHE_TTL_MS = 24L * 60 * 60 * 1000
        fun fingerprint(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}
