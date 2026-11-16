package com.simplechat.app.ui.chat

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.simplechat.app.R
import com.simplechat.app.data.Res
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * 朗读（TTS）。
 *
 * 用**系统** TTS 引擎：不需要权限、不需要额外网络请求，也不额外计费。
 * 官方 demo 走服务端 `/v1/tools/tts`（音色更好），但那个端点只在官方域上提供，
 * 而"把整段回复念出来"这件事系统引擎已经够用 —— 代价是音色取决于用户装的引擎，
 * 所以设置里提供了音色选择（[loadSpeechVoices]）。
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

    /** 正在念试听样例（设置页据此把「试听」换成「停止」）。 */
    val previewing: Boolean get() = speakingId == PreviewUtteranceId

    private var engine: TextToSpeech? = null
    private var pending: Pair<String, String>? = null

    /**
     * 引擎**起不来**（设备没装 TTS / 引擎被禁用）。
     *
     * 单独一个标记而不是直接写 `error`：初始化在页面一进来就发生了，若那时就报错，
     * 用户一打开对话就会看到一条跟自己操作无关的提示。等到他真去点朗读时再报。
     */
    private var initFailed = false

    /** 用户选中的音色名（空 = 跟随系统默认，按界面语言挑）。 */
    private var preferredVoice: String = ""

    fun initialize() {
        if (engine != null) return
        engine = TextToSpeech(context) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (!ready) {
                /*
                 * 引擎起不来时**只记标记**，不立刻报错（见 [initFailed]）：
                 * 用户可能根本不会用到朗读，不该一进对话就被一条提示打扰。
                 * 若他刚才已经点了朗读（pending 非空），那这会儿才该告诉他为什么没声。
                 */
                initFailed = true
                if (pending != null) {
                    pending = null
                    error = Res.get(R.string.tts_unavailable)
                }
                return@TextToSpeech
            }
            initFailed = false
            engine?.voice = null // 先回到系统默认，再按偏好挑
            engine?.language = preferredLocale()
            applyVoice(preferredVoice)
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

    /**
     * 应用音色偏好。空串 = 系统默认；找不到同名音色（引擎换了 / 音色被删了）也退回默认。
     *
     * 在引擎就绪前调用是安全的：偏好先记下来，初始化回调里会再调一次。
     */
    fun applyVoice(name: String) {
        preferredVoice = name
        val tts = engine ?: return
        if (!ready) return
        val voice = preferredVoice
            .takeIf { it.isNotBlank() }
            ?.let { wanted -> tts.voices?.firstOrNull { it.name == wanted } }
        if (voice != null) {
            tts.voice = voice
        } else {
            tts.voice = null
            tts.language = preferredLocale()
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

    /**
     * 试听（设置页用）：临时切换到 [voiceName] 念一段样例。
     *
     * 刻意**不落库** —— 试听只是"听听看"，最后选哪个由用户点「确定」那一下决定。
     */
    fun preview(text: String, voiceName: String) {
        applyVoice(voiceName)
        speak(PreviewUtteranceId, text)
    }

    private fun speak(id: String, raw: String) {
        val text = prepareForSpeech(raw)
        if (text.isBlank()) {
            error = Res.get(R.string.tts_empty)
            return
        }
        val tts = engine
        if (tts == null || !ready) {
            if (initFailed) {
                // 引擎压根起不来：直接说清楚，别让按钮变成"点了没反应"
                pending = null
                error = Res.get(R.string.tts_unavailable)
                return
            }
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
        initFailed = false
        speakingId = null
    }

    private fun preferredLocale(): Locale =
        if (Res.bool(R.bool.ui_is_cjk)) Locale.SIMPLIFIED_CHINESE else Locale.getDefault()

    private companion object {
        const val PreviewUtteranceId = "__preview__"
    }
}

/**
 * 一个可选的朗读音色（系统 TTS 引擎提供的）。
 *
 * 只收**与界面语言同语言**的音色：用户让模型念的正文多半就是界面语言，
 * 把几十种语言全列出来只会让选择变难。引擎里一个匹配的都没有时退回全量列表。
 */
internal data class SpeechVoice(
    /** 引擎里的机器标识，落库用它。 */
    val name: String,
    /** 给人看的名字（地区 + 是否需联网）。 */
    val label: String,
    /** 是否依赖网络合成（离线音色更稳、更快）。 */
    val needsNetwork: Boolean,
)

/**
 * 读系统里可用的音色。
 *
 * 必须**临时起一个 TTS 引擎**：`Voice` 列表只能从引擎实例上拿，没有静态查询接口。
 * 拿完立刻 `shutdown()` —— 设置页只是来列个清单，不该常驻第二个引擎。
 * 引擎起不来（设备没有 TTS 服务）返回空列表，界面据此给出"装一个 TTS 引擎"的提示。
 */
internal suspend fun loadSpeechVoices(context: Context): List<SpeechVoice> = withContext(Dispatchers.Main) {
    suspendCancellableCoroutine { continuation ->
        var engineRef: TextToSpeech? = null
        var resumed = false
        val engine = TextToSpeech(context) { status ->
            val voices = if (status == TextToSpeech.SUCCESS) engineRef?.voices.orEmpty() else emptySet()
            if (!resumed) {
                resumed = true
                continuation.resume(describeVoices(voices))
            }
            runCatching { engineRef?.shutdown() }
        }
        engineRef = engine
        continuation.invokeOnCancellation { runCatching { engine.shutdown() } }
    }
}

/**
 * 音色 → 展示用的清单。纯函数，可单测。
 *
 * 排序：离线优先（更稳、更快）、其次按地区名 —— 用户不该为了选个音色去猜
 * `zh-cn-x-ccc-network` 和 `zh-cn-x-ccc-local` 有什么区别。
 */
internal fun describeVoices(voices: Collection<Voice>): List<SpeechVoice> {
    if (voices.isEmpty()) return emptyList()
    val cjk = Res.bool(R.bool.ui_is_cjk)
    val displayLocale = if (cjk) Locale.SIMPLIFIED_CHINESE else Locale.getDefault()
    val wantedLanguage = if (cjk) "zh" else Locale.getDefault().language
    val online = Res.get(R.string.tts_voice_online)

    fun labelOf(voice: Voice): String {
        val place = voice.locale.getDisplayName(displayLocale).ifBlank { voice.locale.toLanguageTag() }
        return if (voice.isNetworkConnectionRequired) "$place · $online" else place
    }

    // 先按界面语言筛一层；引擎里没有对应语言的音色时才退回全量（总比"一个都没有"强）
    val candidates = voices.filter { it.locale.language == wantedLanguage }.ifEmpty { voices.toList() }
    val mapped = candidates
        .map { SpeechVoice(name = it.name, label = labelOf(it), needsNetwork = it.isNetworkConnectionRequired) }
        .sortedWith(compareBy({ it.needsNetwork }, { it.label }, { it.name }))

    // 同一个地区可能有多个音色（本地/联网/不同音质）：撞名时补上机器标识，否则没法区分
    val duplicated = mapped.groupingBy { it.label }.eachCount().filterValues { it > 1 }.keys
    return mapped.map { voice ->
        if (voice.label in duplicated) voice.copy(label = "${voice.label}（${voice.name}）") else voice
    }
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

/**
 * @param voiceName 用户选的音色（空 = 系统默认）。设置里换音色后立即生效，
 *   正在念的那一句不会被打断 —— 换音色不该像"点了停止"一样。
 */
@Composable
fun rememberSpeechOutput(voiceName: String = ""): SpeechOutputController {
    val context = LocalContext.current
    // 与语音输入同一个道理：TTS 引擎会持有 Context，别把 Activity 留在地基里
    val appContext = remember(context) { context.applicationContext }
    val controller = remember(appContext) { SpeechOutputController(appContext) }
    DisposableEffect(controller) {
        controller.initialize()
        onDispose { controller.release() }
    }
    LaunchedEffect(controller, voiceName) { controller.applyVoice(voiceName) }
    return controller
}
