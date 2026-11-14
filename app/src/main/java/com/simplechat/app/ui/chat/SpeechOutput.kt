package com.simplechat.app.ui.chat

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.simplechat.app.R
import com.simplechat.app.data.Res
import java.util.Locale
import java.util.UUID

/**
 * 朗读（TTS）。
 *
 * 用**系统** TTS 引擎：不需要权限、不需要额外网络请求，也不额外计费。
 * 官方 demo 走服务端 `/v1/tools/tts`（音色更好），但那个端点只在官方域上提供，
 * 而"把整段回复念出来"这件事系统引擎已经够用。
 *
 * ### 只念正文，不念代码块与表格
 * 与官方一致：代码块逐字符念出来毫无意义。这里在 [prepareForSpeech] 里剥掉
 * 围栏代码块、行内代码、表格行与链接语法 —— **纯文本替换，不改动消息本身**。
 */
class SpeechOutputController(private val context: Context) {

    /** 正在朗读哪条消息；null 表示没在念。 */
    var speakingId by mutableStateOf<String?>(null)
        private set

    /** 引擎就绪与否。没就绪时按钮点了只会弹一句提示。 */
    var ready by mutableStateOf(false)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    private var engine: TextToSpeech? = null
    private var pending: Pair<String, String>? = null

    fun initialize() {
        if (engine != null) return
        engine = TextToSpeech(context) { status ->
            ready = status == TextToSpeech.SUCCESS
            engine?.language = preferredLocale()
            engine?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit

                override fun onDone(utteranceId: String?) {
                    // 回调在 TTS 线程：只改状态，Compose 会自己切回来
                    if (utteranceId == speakingId) speakingId = null
                }

                @Deprecated("旧接口，仍需实现")
                override fun onError(utteranceId: String?) {
                    if (utteranceId == speakingId) speakingId = null
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    if (utteranceId == speakingId) {
                        speakingId = null
                        error = Res.get(R.string.tts_failed)
                    }
                }
            })
            pending?.let { (id, text) -> pending = null; speak(id, text) }
        }
    }

    /** 念 / 停止念同一条消息。 */
    fun toggle(id: String, raw: String) {
        if (speakingId == id) {
            stop()
            return
        }
        speak(id, raw)
    }

    private fun speak(id: String, raw: String) {
        val text = prepareForSpeech(raw)
        if (text.isBlank()) {
            error = Res.get(R.string.tts_empty)
            return
        }
        val tts = engine
        if (tts == null || !ready) {
            // 引擎还在初始化：先记下来，就绪后自动开口，不让用户再点一次
            pending = id to raw
            initialize()
            return
        }
        error = null
        speakingId = id
        val result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), UUID.randomUUID().toString())
        if (result != TextToSpeech.SUCCESS) {
            speakingId = null
            error = Res.get(R.string.tts_failed)
        }
    }

    fun stop() {
        runCatching { engine?.stop() }
        speakingId = null
    }

    fun consumeError() {
        error = null
    }

    fun release() {
        runCatching { engine?.stop() }
        runCatching { engine?.shutdown() }
        engine = null
        ready = false
        speakingId = null
    }

    private fun preferredLocale(): Locale =
        if (Res.bool(R.bool.ui_is_cjk)) Locale.SIMPLIFIED_CHINESE else Locale.getDefault()
}

/**
 * 把一段 markdown 正文变成**适合念出来**的纯文本。
 *
 * 剥掉：围栏代码块（整块）、行内代码的反引号、表格行、图片、链接语法
 * （保留链接文字）、引用与列表的记号、强调星号、标题井号。
 *
 * 刻意不追求完美：念出来的内容只要**可听**就行，多剥一个字符远比
 * "把 `**` 念成星号星号"要好。纯函数，有单测（`SpeechTextTest`）。
 */
internal fun prepareForSpeech(markdown: String): String {
    if (markdown.isBlank()) return ""

    val out = StringBuilder()
    var fence: String? = null
    markdown.lineSequence().forEach { line ->
        val trimmed = line.trimStart()
        // 围栏代码块：开合之间的所有行整块跳过
        if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
            fence = if (fence == null) "x" else null
            return@forEach
        }
        if (fence != null) return@forEach
        // 表格行整行跳过（念出来是"竖线竖线"，毫无意义）
        if (trimmed.startsWith("|")) return@forEach
        // 水平分割线
        if (trimmed.matches(Regex("^([-*_])(\\s*\\1){2,}$"))) return@forEach

        out.append(flattenInline(line)).append('\n')
    }
    return out.toString()
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()
}

/** 行内的 markdown 记号 → 纯文字。 */
private fun flattenInline(line: String): String {
    var text = line
    // 标题井号、引用尖角、列表符号
    text = text.replace(Regex("^\\s{0,3}#{1,6}\\s*"), "")
    text = text.replace(Regex("^\\s{0,3}>\\s?"), "")
    text = text.replace(Regex("^\\s{0,3}([-*+]|\\d+[.)])\\s+"), "")
    // 图片 → 丢弃；链接 → 只留文字
    text = text.replace(Regex("!\\[[^\\]]*\\]\\([^)]*\\)"), "")
    text = text.replace(Regex("\\[([^\\]]*)\\]\\([^)]*\\)"), "$1")
    // 行内代码的反引号
    text = text.replace(Regex("`+([^`]*)`+"), "$1")
    // 强调与删除线
    text = text.replace(Regex("\\*{1,3}([^*]+)\\*{1,3}"), "$1")
    text = text.replace(Regex("_{1,3}([^_]+)_{1,3}"), "$1")
    text = text.replace(Regex("~~([^~]+)~~"), "$1")
    // 结尾两个空格 = 硬换行，念的时候当作停顿
    return text.trimEnd()
}

@Composable
fun rememberSpeechOutput(): SpeechOutputController {
    val context = LocalContext.current
    // 与语音输入同一个道理：TTS 引擎会持有 Context，别把 Activity 留在地基里
    val appContext = remember(context) { context.applicationContext }
    val controller = remember(appContext) { SpeechOutputController(appContext) }
    DisposableEffect(controller) {
        controller.initialize()
        onDispose { controller.release() }
    }
    return controller
}
