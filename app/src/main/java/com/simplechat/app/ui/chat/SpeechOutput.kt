package com.simplechat.app.ui.chat

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
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
import com.simplechat.app.data.userMessage
import com.simplechat.app.net.DeepSeekTts
import com.simplechat.app.net.httpErrorText
import java.io.IOException
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * 朗读（TTS）。两条通道：
 *
 * 1. **系统 TTS 引擎**：不需要权限、不额外计费，音色取决于设备装了哪个引擎；
 * 2. **DeepSeek 官方 TTS**（`POST /v1/tools/tts`，音色 `stella` / `tide`）：
 *    音色明显更好，但只在官方服务商（`api.deepseek.com` + 已填 Key）上可用。
 *    官方返回的是**裸 PCM**（16 bit / 单声道 / 24 kHz），这里用 `AudioTrack`
 *    边收边播 —— 几千字的回复不需要先攒成一个 `ByteArray`。
 *
 * 选哪条由**音色名**决定：`deepseek:` 前缀走官方，其余走系统（见 [speak]）。
 *
 * ### 只念正文，不念代码块与表格
 *
 * 与官方一致：代码块逐字符念出来毫无意义。这里在 [prepareForSpeech] 里剥掉
 * 围栏代码块、行内代码、表格行与链接语法 —— **纯文本替换，不改动消息本身**。
 * 官方通道把这份纯文本发过去，同时再带 `filter_markdown = true`（双保险）。
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

    /** 官方 TTS 的接入参数；null = 当前服务商不支持（界面会隐藏朗读键）。 */
    private var officialConfig: DeepSeekTts.Config? = null

    /** 官方朗读任务与它自己的作用域（控制器不是 ViewModel，自己管生命周期）。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var officialJob: Job? = null
    private var audioTrack: AudioTrack? = null

    /**
     * 用户点了停止。
     *
     * 用标记而不是只 `cancel()` 协程：`AudioTrack.write` 与 `InputStream.read`
     * 都是**阻塞调用**，取消协程不会立刻打断它们；下一次回调检查这个标记就能退出。
     */
    @Volatile
    private var stopRequested = false

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
            .takeIf { it.isNotBlank() && !DeepSeekTts.isOfficialVoice(it) }
            ?.let { wanted -> tts.voices?.firstOrNull { it.name == wanted } }
        if (voice != null) {
            tts.voice = voice
        } else {
            tts.voice = null
            tts.language = preferredLocale()
        }
    }

    /**
     * 应用官方 TTS 的接入参数（null = 当前服务商不支持官方朗读）。
     *
     * 只有"服务商 = DeepSeek 官方 + Key 已填"时界面才会列官方音色；这里再兜一层：
     * 配置为空时，即便音色名还是 `deepseek:*` 也不会去发请求（界面会隐藏朗读键）。
     */
    fun applyOfficialConfig(config: DeepSeekTts.Config?) {
        officialConfig = config
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
        // 官方音色：只有当配置在的时候才走官方通道；不在时界面根本不该显示朗读键
        // （见 `showReadAloud`），真走到了这里也只是给一句提示，不发请求。
        val voiceId = DeepSeekTts.voiceIdOf(preferredVoice)
        if (voiceId != null) {
            speakOfficial(id, text, voiceId)
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

    // ── DeepSeek 官方 TTS ─────────────────────────────────

    /**
     * 官方通道：逐块合成、边下边播。
     *
     * 主要逻辑在 IO 线程（网络读取 + `AudioTrack.write` 都是阻塞调用），
     * 状态切回主线程 —— Compose 的状态读写在后台线程虽然也能工作，
     * 但"在哪个线程改的"这种事不要靠运气。
     */
    private fun speakOfficial(id: String, text: String, voiceId: String) {
        val config = officialConfig
        if (config == null) {
            error = Res.get(R.string.tts_official_unavailable)
            return
        }
        val chunks = DeepSeekTts.splitForSpeech(text)
        if (chunks.isEmpty()) {
            error = Res.get(R.string.tts_empty)
            return
        }
        error = null
        stopRequested = false
        officialJob?.cancel()
        speakingId = id
        officialJob = scope.launch {
            var failure: String? = null
            withContext(Dispatchers.IO) {
                var track: AudioTrack? = null
                try {
                    // 用 val 接住再给 var：`track.write` 在 lambda 里，对捕获的可变局部变量
                    // 无法智能转换（编译期拿不到非空证明）
                    val created = createAudioTrack()
                    track = created
                    audioTrack = created
                    created.play()
                    for (chunk in chunks) {
                        if (stopRequested) break
                        DeepSeekTts.streamPcm(config, chunk, voiceId) { bytes, length ->
                            // 回调在 IO 线程、且是阻塞写的：这里检查停止标记，
                            // 用户点「停止」后下一次回调就会抛出去，不会把整段读完
                            if (stopRequested) throw CancellationException("tts stopped")
                            created.write(bytes, 0, length)
                        }
                    }
                } catch (cancelled: CancellationException) {
                    // 用户停止 / 被新的朗读顶掉：不是错误
                } catch (t: Throwable) {
                    failure = officialErrorText(t)
                    android.util.Log.w(TtsLogTag, "官方朗读失败", t)
                } finally {
                    if (audioTrack === track) audioTrack = null
                    runCatching { track?.pause() }
                    runCatching { track?.flush() }
                    runCatching { track?.release() }
                }
            }
            if (speakingId == id) speakingId = null
            failure?.let { error = it }
        }
    }

    /** 24 kHz / 单声道 / 16 bit 的流式播放器。 */
    private fun createAudioTrack(): AudioTrack {
        val minBuffer = AudioTrack.getMinBufferSize(
            DeepSeekTts.SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        // 至少 0.5 秒的缓冲：网络抖动时不会出现"每块之间咔一下"
        val wanted = DeepSeekTts.SAMPLE_RATE * DeepSeekTts.BYTES_PER_FRAME / 2
        val bufferSize = maxOf(if (minBuffer > 0) minBuffer else 0, wanted)
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(DeepSeekTts.SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(bufferSize)
            .build()
    }

    /** 官方通道的失败原因 → 人话（带状态码与原文，便于排查）。 */
    private fun officialErrorText(error: Throwable): String = when (error) {
        is DeepSeekTts.TtsException ->
            // 400 通常是"没有可朗读的文本"（代码/表格被过滤空）或参数问题
            if (error.code == 400) {
                Res.get(R.string.tts_official_empty)
            } else {
                Res.get(R.string.tts_official_failed, httpErrorText(error.code, error.detail))
            }

        is IOException -> Res.get(R.string.tts_official_failed, error.userMessage())
        else -> Res.get(R.string.tts_official_failed, error.message.orEmpty())
    }

    fun stop() {
        stopRequested = true
        officialJob?.cancel()
        officialJob = null
        runCatching { audioTrack?.pause() }
        runCatching { audioTrack?.flush() }
        runCatching { engine?.stop() }
        speakingId = null
    }

    fun consumeError() {
        error = null
    }

    /**
     * 当前音色**能不能出声**。
     *
     * 界面据此决定要不要显示"朗读"按钮（用户要求：不支持的 API 直接隐藏，而不是
     * 点下去再弹错误）。官方音色只有拿到配置才算可用；系统音色恒为 true ——
     * 引擎缺失那种情况在真的点了之后才提示（不该因为设备没装引擎就藏掉所有入口）。
     */
    fun isReadAloudSupported(voiceName: String): Boolean =
        !DeepSeekTts.isOfficialVoice(voiceName) || officialConfig != null

    fun release() {
        runCatching { audioTrack?.stop() }
        runCatching { audioTrack?.release() }
        audioTrack = null
        scope.cancel()
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

        /** 官方朗读的诊断日志 tag（用户看不到，真机排查时要它）。 */
        const val TtsLogTag = "SimpleChat/Tts"
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
 * 朗读控制器。
 *
 * @param voiceName 用户选的音色（空 = 系统默认）。
 * @param officialConfig 官方 TTS 的接入参数；null = 当前服务商不支持官方朗读。
 *
 * 换音色/换服务商后立即生效，正在念的那一句不会被打断 —— 换音色不该像"点了停止"。
 */
@Composable
fun rememberSpeechOutput(
    voiceName: String = "",
    officialConfig: DeepSeekTts.Config? = null,
): SpeechOutputController {
    val context = LocalContext.current
    // 与语音输入同一个道理：TTS 引擎会持有 Context，别把 Activity 留在地基里
    val appContext = remember(context) { context.applicationContext }
    val controller = remember(appContext) { SpeechOutputController(appContext) }
    DisposableEffect(controller) {
        controller.initialize()
        onDispose { controller.release() }
    }
    LaunchedEffect(controller, voiceName) { controller.applyVoice(voiceName) }
    LaunchedEffect(controller, officialConfig) { controller.applyOfficialConfig(officialConfig) }
    return controller
}
