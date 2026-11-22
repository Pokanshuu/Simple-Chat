package com.simplechat.app.data

import android.os.Build
import android.util.Log
import com.simplechat.app.BuildConfig
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 应用内诊断日志。
 *
 * 为什么不用 `logcat`：真机上用户拿不到 logcat，求助时只能描述"它报错了"；
 * 有了这圈内存缓冲，用户能把**出问题前后那段**直接复制或导出，排查成本立刻降下来。
 *
 * 设计约束（都是踩过的）：
 *
 * - **只在内存里**：不落盘、不后台写文件 —— 日志本身不该成为新的隐私与耗电来源，
 *   进程被杀就没了，这正是"当前运行日志"的语义。
 * - **有上限**：环形缓冲 2000 行，超了丢最旧的。无上限的日志等于内存泄漏。
 * - **不记消息正文、不记 Key**：只记事件、模型、端点 host、错误码与原因。
 *   [redact] 兜底把 `sk-` 开头的串和常见 key 字段替换掉。
 */
object Diagnostics {

    private const val CAPACITY = 2000

    /** 同时写一份到 logcat（debug 构建排查时方便 adb）。 */
    private const val MIRROR_TO_LOGCAT = true

    private val lines = ArrayDeque<String>(CAPACITY)
    private val lock = Any()
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT)

    /** 记录一条。`tag` 用短词：NET / CHAT / SAVE / SETTINGS / UI。 */
    fun log(tag: String, message: String) {
        val line = "${timeFormat.format(Date())} [$tag] ${redact(message)}"
        synchronized(lock) {
            if (lines.size >= CAPACITY) lines.removeFirst()
            lines.addLast(line)
        }
        if (MIRROR_TO_LOGCAT) runCatching { Log.i("SimpleChat/$tag", redact(message)) }
    }

    fun e(tag: String, message: String, error: Throwable? = null) {
        val detail = error?.let { "${it.javaClass.simpleName}: ${it.message}" }
        log(tag, if (detail.isNullOrBlank()) message else "$message — $detail")
    }

    /** 当前缓冲（按时间正序）。 */
    fun snapshot(): List<String> = synchronized(lock) { lines.toList() }

    fun count(): Int = synchronized(lock) { lines.size }

    fun clear() = synchronized(lock) { lines.clear() }

    /**
     * 导出文本：设备与环境信息 + 全部日志。
     *
     * 环境信息必须带上 —— 同一个报错在不同 Android 版本 / ROM 上成因经常不同，
     * 只有日志没有环境，等于让维护者猜。
     */
    fun exportText(): String = buildString {
        appendLine("SimpleChat diagnostics")
        appendLine("version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine("android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("device: ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date())}")
        appendLine("lines: ${count()}/$CAPACITY (oldest dropped first)")
        appendLine("----------")
        snapshot().forEach { appendLine(it) }
    }

    /** 去掉密钥类内容：`sk-xxx` 与 `api_key=` / `authorization:` 后面的值。 */
    internal fun redact(text: String): String = text
        .replace(Regex("sk-[A-Za-z0-9_\\-]{8,}"), "sk-***")
        .replace(Regex("(?i)(api[_-]?key\\s*[=:]\\s*)\\S+"), "$1***")
        .replace(Regex("(?i)(authorization\\s*[=:]\\s*)\\S+"), "$1***")
}
