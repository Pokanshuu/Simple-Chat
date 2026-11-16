package com.simplechat.app.data.archive

import com.simplechat.app.data.BackupFile
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 「所有对话」的 **ZIP 归档**。
 *
 * ### 为什么在 JSON 之外再加一个 ZIP
 * 单文件 JSON 备份有个绕不开的天花板：**图片以 base64 内联在消息里**。
 * 几百张图之后，那个 JSON 会变成几十 MB，用文本编辑器打不开、用浏览器上传会超时。
 * ZIP 把这件事拆成"一份可读的 JSON + 若干独立文件"，代价只是一个压缩包。
 *
 * ### 包里的内容（刻意简单）
 * | 路径 | 内容 |
 * |---|---|
 * | `manifest.json` | 格式与版本、导出时间、数量统计 |
 * | `backup.json` | **与单文件 JSON 备份逐字同构**的 [BackupFile] |
 * | `markdown/NNNN-标题.md` | 每个会话的文稿（给人读，不参与导入） |
 *
 * ### 为什么 backup.json 不再拆图片
 * 拆图意味着要定义"图在包里叫什么、消息怎么引用它"的一套新契约，
 * 而现有 `BackupFile` 已经能无损往返。**同一份数据只留一种表示**，
 * 导入导出就不会因为"两条路各改一处"而对不上。
 * ZIP 的价值在于**一个文件好传**，不在于省那点体积。
 *
 * 导入端由 `ExternalBackup` 认 `format` 字段后直接读 `backup.json`。
 */
object ChatArchive {

    /** 归档格式标识。与单文件 JSON 备份的 `simplechat-backup` 刻意区分开。 */
    const val FORMAT = "simplechat-archive"

    /** 归档版本。目前只有 1。 */
    const val VERSION = 1

    const val MANIFEST = "manifest.json"
    const val BACKUP = "backup.json"
    const val MARKDOWN_DIR = "markdown"

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    /** 一个会话的 Markdown 文稿，按 id 对应。 */
    data class Document(val fileName: String, val markdown: String)

    internal class StreamDocument(val fileName: String, val write: suspend (OutputStream) -> Unit)

    /** Writes directly to disk; neither the full backup nor the ZIP is held in a byte array. */
    internal suspend fun writeTo(
        output: OutputStream,
        exportedAt: Long,
        conversationCount: Int,
        messageCount: Int,
        presetCount: Int,
        documents: List<StreamDocument>,
        writeBackup: suspend (OutputStream) -> Unit,
    ) {
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry(MANIFEST))
            zip.write(buildJsonObject {
                put("format", FORMAT)
                put("version", VERSION)
                put("exportedAt", exportedAt)
                put("conversations", conversationCount)
                put("messages", messageCount)
                put("presets", presetCount)
                put("documents", documents.size)
                put("app", "SimpleChat")
            }.toString().toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry(BACKUP))
            writeBackup(zip)
            zip.closeEntry()
            for (document in documents) {
                zip.putNextEntry(ZipEntry("$MARKDOWN_DIR/${document.fileName}"))
                document.write(zip)
                zip.closeEntry()
            }
        }
    }

    /**
     * 打包。
     *
     * @param backup 完整的备份模型（与会话导出共用）
     * @param documents 各会话的 Markdown 文稿；文件名由 [documentName] 生成
     */
    fun write(backup: BackupFile, documents: List<Document>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(MANIFEST))
            zip.write(manifest(backup, documents.size).toByteArray())
            zip.closeEntry()

            zip.putNextEntry(ZipEntry(BACKUP))
            zip.write(json.encodeToString(BackupFile.serializer(), backup).toByteArray())
            zip.closeEntry()

            documents.forEach { document ->
                zip.putNextEntry(ZipEntry("$MARKDOWN_DIR/${document.fileName}"))
                zip.write(document.markdown.toByteArray())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /**
     * 从 ZIP 里读出 `backup.json`；不是本应用的归档时返回 null（交给 Chatbox 分支）。
     *
     * 只认 `manifest.format`，**不猜内容**：猜错会把一份 Chatbox 包当成自己的，
     * 然后要么导入一堆空会话，要么报一句看不懂的解析错误。
     */
    internal fun readBackupJson(readEntry: (String) -> ByteArray?): BackupFile? {
        val manifestBytes = readEntry(MANIFEST) ?: return null
        val format = runCatching {
            json.parseToJsonElement(manifestBytes.decodeToString())
                .jsonObject["format"]?.jsonPrimitive?.content
        }.getOrNull()
        if (format != FORMAT) return null

        val backupBytes = readEntry(BACKUP) ?: return null
        return runCatching {
            json.decodeFromString<BackupFile>(backupBytes.decodeToString())
        }.getOrNull()
    }

    private fun manifest(backup: BackupFile, documentCount: Int): String = buildJsonObject {
        put("format", FORMAT)
        put("version", VERSION)
        put("exportedAt", backup.exportedAt)
        put("conversations", backup.conversations.size)
        put("messages", backup.conversations.sumOf { it.messages.size })
        put("presets", backup.presets.size)
        put("documents", documentCount)
        put("app", "SimpleChat")
    }.toString()

    /**
     * 文稿文件名：`NNNN-标题.md`。
     *
     * 必须**过滤文件系统不认识的字符**（`/ \ : * ? " < > |`）—— 模型起的标题里
     * 什么都可能有，一个斜杠就会让 ZIP 里多出一层不存在的目录。
     */
    fun documentName(index: Int, title: String): String {
        val safe = title.trim()
            .map { if (it in ILLEGAL || it.isISOControl()) '_' else it }
            .joinToString("")
            .trim('.', ' ')
            .take(60)
        // 只剩分隔符（"///"、"..."）也算没名字 —— 那种文件名在文件管理器里看不出区别
        val meaningful = safe.ifBlank { "" }.takeIf { name -> name.any { it != '_' } }
        return "${index.toString().padStart(4, '0')}-${meaningful ?: "conversation"}.md"
    }

    private val ILLEGAL = setOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')
}
