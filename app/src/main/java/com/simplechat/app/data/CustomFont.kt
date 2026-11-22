package com.simplechat.app.data

import java.io.File
import kotlinx.serialization.Serializable

/**
 * 用户导入的自定义字体。
 *
 * **只存元数据，字节落在 `filesDir/fonts/`**。字体文件动辄几 MB，
 * 塞进 DataStore 会把设置读写拖成龟速（每次读设置都要反序列化一遍）。
 * 与图片附件落盘是同一个理由。
 *
 * [fileName] 是导入时生成的名字（`<uuid>.ttf`），[displayName] 是给用户看的
 * —— 用户可能导入两个同名字体，展示名重复不代表文件重复。
 */
@Serializable
data class ImportedFont(
    val id: String,
    val displayName: String,
    val fileName: String,
)

/**
 * 字体可以作用的三个位置。三层互相独立：可以只换回复字体、
 * 或者只给用户发言换一个手写体，其余保持系统默认。
 */
enum class FontSlot {
    /** 软件 UI 字体层：设置、列表、按钮等一切界面文字。 */
    UI,

    /** 用户发言层：自己发出的消息气泡。 */
    USER,

    /** 模型回复层：AI 回复正文与 Markdown。 */
    ASSISTANT,
}

/**
 * 字体文件的存放与导入。
 *
 * 导入时做一次**魔数校验**：用户从文件管理器选的可能根本不是字体
 * （改过扩展名的图片、损坏的下载文件）。放进去之后再让 Compose 去解析，
 * 失败是静默的 —— 界面全体回退系统字体，用户完全不知道发生了什么。
 */
object FontFiles {

    private const val Dir = "fonts"

    /** 单个字体文件上限：24 MB。再大的多半是字体合集，加载也会拖慢首屏。 */
    private const val MaxBytes = 24L * 1024 * 1024

    fun dir(context: android.content.Context): File = File(context.filesDir, Dir)

    fun fileOf(context: android.content.Context, fileName: String): File = File(dir(context), fileName)

    /**
     * 把用户选中的字体复制进私有目录。
     *
     * @return 成功时返回元数据；不是字体 / 读不出来 / 太大都返回 null，
     *   由调用方给用户一句提示。
     */
    fun import(
        context: android.content.Context,
        uri: android.net.Uri,
        displayName: String,
    ): ImportedFont? {
        /*
         * 不用 `InputStream.readNBytes`：那是 Java 9 的 API，
         * 在 minSdk 26 的设备上直接 NoSuchMethodError（被 runCatching 吞掉，
         * 表现只是"导入了但列表没变"，非常难查）。这里手动按块读并在超限时中止。
         */
        val bytes = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val read = input.read(chunk)
                    if (read <= 0) break
                    total += read
                    if (total > MaxBytes) return null
                    out.write(chunk, 0, read)
                }
                out.toByteArray()
            }
        }.getOrNull() ?: return null
        if (bytes.isEmpty() || bytes.size > MaxBytes) return null
        if (!looksLikeFont(bytes)) return null

        val fileName = "${java.util.UUID.randomUUID()}${extensionOf(displayName, bytes)}"
        return runCatching {
            dir(context).mkdirs()
            File(dir(context), fileName).writeBytes(bytes)
            ImportedFont(id = fileName, displayName = displayName.trim().ifBlank { "未命名字体" }, fileName = fileName)
        }.getOrNull()
    }

    /** 删除字体文件；返回是否真的删掉了（不存在也算成功）。 */
    fun delete(context: android.content.Context, font: ImportedFont): Boolean =
        runCatching { !fileOf(context, font.fileName).exists() || fileOf(context, font.fileName).delete() }
            .getOrDefault(false)

    /**
     * 根据文件头判断是不是字体。
     *
     * 支持的四种：TrueType（`00 01 00 00`）、OpenType CFF（`OTTO`）、
     * Apple 旧格式（`true` / `typ1`）、字体集合（`ttcf`）。
     * WOFF 是压缩格式，Compose 解不了，故意不算。
     */
    private fun looksLikeFont(bytes: ByteArray): Boolean {
        if (bytes.size < 4) return false
        val b = bytes
        val tag = String(byteArrayOf(b[0], b[1], b[2], b[3]), Charsets.ISO_8859_1)
        return tag == "OTTO" || tag == "true" || tag == "typ1" || tag == "ttcf" ||
            (b[0].toInt() == 0 && b[1].toInt() == 1 && b[2].toInt() == 0 && b[3].toInt() == 0)
    }

    private fun extensionOf(displayName: String, bytes: ByteArray): String {
        val dot = displayName.lastIndexOf('.')
        if (dot >= 0 && displayName.length - dot <= 6) {
            val ext = displayName.substring(dot).lowercase()
            if (ext in setOf(".ttf", ".otf", ".ttc")) return ext
        }
        // 回退：按魔数判断
        val tag = String(byteArrayOf(bytes[0], bytes[1], bytes[2], bytes[3]), Charsets.ISO_8859_1)
        return if (tag == "OTTO") ".otf" else ".ttf"
    }
}
