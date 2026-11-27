package com.simplechat.app.data

import kotlinx.serialization.json.Json

object FavoriteFolders {
    const val DefaultId = "default"
    const val MaxFolders = 50
    const val MaxNameChars = 20

    /** 去空白后非空、不超过 20 字；返回 null 表示不合法。 */
    fun normalizeName(raw: String): String? =
        raw.trim().takeIf { it.isNotEmpty() && it.length <= MaxNameChars }
}

object FavoriteContent {
    const val PreviewChars = 280
    private val namesJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun preview(text: String): String = text
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(PreviewChars)

    /** 只保留图片附件，并清空 base64；path 由 Repository 先落盘。 */
    fun images(attachments: List<Attachment>): List<Attachment> =
        attachments.filter { it.isImage }.map { it.copy(data = "") }

    /** 非图片附件的文件名，用于告知用户「原本还带了个文件」。 */
    fun names(attachments: List<Attachment>): List<String> =
        attachments.filterNot { it.isImage }.map { it.name }

    fun encodeNames(names: List<String>): String? =
        if (names.isEmpty()) null else namesJson.encodeToString(names)

    fun decodeNames(raw: String?): List<String> =
        if (raw.isNullOrBlank()) emptyList()
        else runCatching { namesJson.decodeFromString<List<String>>(raw) }.getOrDefault(emptyList())

    fun coverJson(images: List<Attachment>): String? =
        images.firstOrNull()?.let { Attachments.encode(listOf(it)) }
}
