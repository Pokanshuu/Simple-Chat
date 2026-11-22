package com.simplechat.app.ui.chat

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.simplechat.app.R
import com.simplechat.app.data.ApiKey
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

    /**
     * 正在试听的是哪一个音色；null = 没在试听。
     *
     * 试听态由 [speakingId] 承载，音色只是"这一次试听用的谁"。所以这里用
     * 派生值而不是独立字段：`speakingId` 一被清掉（播完 / 停止 / 失败），
     * 这里自动变 null，不会出现"音乐停了、按钮还显示成停止"的错位。
     */
    val previewingVoice: String? get() = if (previewing) lastPreviewVoice else null
    private var lastPreviewVoice: String? = null

    private var engine: TextToSpeech? = null
    private var pending: Pair<String, String>? = null
    private var utteranceId: String? = null
    @Volatile private var playbackGeneration = 0L

    /** 官方 TTS 的接入参数；null = 当前服务商不支持（界面会隐藏朗读键）。 */
    private var officialConfig: DeepSeekTts.Config? = null

    /**
     * 第三方声音模型配置；null / 未启用 = 不走第三方通道。
     * 由设置流推送过来（见 `ChatScreen` 调用 `updateThirdParty`）。
     */
    private var thirdPartyConfig: com.simplechat.app.data.VoiceApiConfig? = null
    private var thirdPartyChatKey: String = ""
    private var thirdPartyChatBaseUrl: String = ""

    /** 第三方通道的播放器（wav/mp3 都交给 MediaPlayer 解）。 */
    private var mediaPlayer: android.media.MediaPlayer? = null
    private var thirdPartyAudioFile: java.io.File? = null
    private var thirdPartyJob: Job? = null

    fun updateThirdParty(
        config: com.simplechat.app.data.VoiceApiConfig?,
        chatKey: String,
        chatBaseUrl: String,
    ) {
        thirdPartyConfig = config
        thirdPartyChatKey = chatKey
        thirdPartyChatBaseUrl = chatBaseUrl
    }

    /** 这个音色属于第三方通道时返回配置，否则 null。 */
    private fun thirdPartyVoice(
        config: com.simplechat.app.data.VoiceApiConfig?,
        voiceName: String,
    ): com.simplechat.app.data.VoiceApiConfig? {
        if (config == null || !config.enabled) return null
        if (voiceName.isBlank()) return null
        return config.takeIf { voiceName in it.allVoices }
    }

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
                    speakingId = null
                    error = Res.get(R.string.tts_unavailable)
                }
                return@TextToSpeech
            }
            initFailed = false
            /*
             * ⚠️ **绝不能 `engine.voice = null`。**
             *
             * Android 15 的 `TextToSpeech.setVoice(Voice)` 实现里没有空值检查
             * （`lambda$setVoice$13` 直接 `voice.getName()`），传 null 会在引擎
             * 初始化回调里抛 NPE —— 而这条回调在主线程上，进程当场崩溃。
             * 真机崩溃栈：`TextToSpeech.setVoice` → `pu2.onInit`。
             *
             * "回到系统默认音色"的唯一正确做法是设 `language`，让引擎自己挑默认。
             */
            engine?.language = preferredLocale()
            applyVoice(preferredVoice)
            engine?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit

                override fun onDone(utteranceId: String?) {
                    scope.launch {
                        if (utteranceId == this@SpeechOutputController.utteranceId) speakingId = null
                    }
                }

                @Deprecated("旧接口，仍需实现")
                override fun onError(utteranceId: String?) {
                    scope.launch {
                        if (isCurrentUtterance(utteranceId)) {
                            speakingId = null
                            error = Res.get(R.string.tts_failed)
                        }
                    }
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    scope.launch { if (isCurrentUtterance(utteranceId)) {
                        speakingId = null
                        error = Res.get(R.string.tts_failed)
                    } }
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
        val wanted = name.takeIf { it.isNotBlank() && !DeepSeekTts.isOfficialVoice(it) }
        val voice = wanted?.let { name ->
            // 引擎换过 / 音色被删了：`voices` 里找不到就是"回默认"，不抛
            runCatching { tts.voices?.firstOrNull { it.name == name } }.getOrNull()
        }
        if (voice != null) {
            /*
             * setVoice 在个别引擎 / 系统版本上会抛（数据被删、引擎自相矛盾）；
             * 一个坏音色不值得让整个 App 崩掉 —— 退回默认语言继续念。
             */
            runCatching { tts.voice = voice }
                .onFailure { runCatching { tts.language = preferredLocale() } }
        } else {
            // 回默认：**只能设 language**，不能设 voice = null（见 initialize 的说明）
            runCatching { tts.language = preferredLocale() }
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

    /** Start reading a completed reply without toggle semantics. */
    fun read(id: String, raw: String) { speak(id, raw) }

    private fun isCurrentUtterance(id: String?): Boolean =
        utteranceId?.let { id == it || id?.startsWith("$it-") == true } == true

    /**
     * 试听（设置页用）：临时切换到 [voiceName] 念一段样例。
     *
     * 刻意**不落库** —— 试听只是"听听看"，最后选哪个由用户点「确定」那一下决定。
     */
    fun preview(text: String, voiceName: String) {
        applyVoice(voiceName)
        lastPreviewVoice = voiceName
        speak(PreviewUtteranceId, text)
    }

    private fun speak(id: String, raw: String) {
        val text = prepareForSpeech(raw)
        if (text.isBlank()) {
            error = Res.get(R.string.tts_empty)
            return
        }
        /*
         * ★ **每次开口之前，先停掉所有正在播的东西。**
         *
         * 两个 `AudioTrack` 同时播同一段 PCM 会混成"炸麦雪花音"——
         * 真机反馈过"试听还好好的，一读正文就炸麦"。触发路径有两条：
         * 1. 设置页的试听还没播完就回到对话页点朗读（两个控制器、两条轨道）；
         * 2. 正在念 A 时点了 B 的朗读（同一个控制器里，`toggle` 只在**同一条**上
         *    才会先停，换了目标就直接 `speak`）。
         * 所以这里统一收口：先停旧，再开新。
         */
        stopPlayback(clearSpeaking = true)
        ActiveController?.takeIf { it !== this }?.stop()
        ActiveController = this
        /*
         * 第三方音色优先：它是有意配置的（用户选了具体音色），
         * 不该被"官方音色恰好有空"或系统引擎抢走。
         */
        thirdPartyVoice(thirdPartyConfig, preferredVoice)?.let { config ->
            com.simplechat.app.data.Diagnostics.log(
                "TTS",
                "third-party voice=$preferredVoice adapter=${config.adapter} enabled=${config.enabled}",
            )
            speakThirdParty(id, text, config, preferredVoice)
            return
        }
        com.simplechat.app.data.Diagnostics.log(
            "TTS",
            "fallback system voice='$preferredVoice' cfg=${thirdPartyConfig?.let { "on/${it.allVoices.size}" } ?: "off"}",
        )
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
            speakingId = id
            initialize()
            return
        }
        error = null
        speakingId = id
        val token = UUID.randomUUID().toString()
        utteranceId = token
        val chunks = speechChunks(text, TextToSpeech.getMaxSpeechInputLength())
        val failed = try {
            chunks.withIndex().any { (index, chunk) ->
                val chunkId = if (index == chunks.lastIndex) token else "$token-$index"
                tts.speak(chunk, if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, Bundle(), chunkId) != TextToSpeech.SUCCESS
            }
        } catch (_: Exception) { true }
        if (failed) {
            runCatching { tts.stop() }
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
        val generation = playbackGeneration
        val config = officialConfig
        if (config == null) {
            error = Res.get(R.string.tts_official_unavailable)
            return
        }
        // 与对话请求同一条底线：Key 里混进非 ASCII 时 OkHttp 只会报一个码点，
        // 在这里提前说清楚（见 ApiKey）
        if (!ApiKey.isHeaderSafe(config.apiKey)) {
            error = Res.get(R.string.error_api_key_chars)
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
                    if (generation != playbackGeneration || stopRequested) throw CancellationException("tts stopped")
                    audioTrack = created
                    /*
                     * ★ **先攒够一段，再 `play()`。**
                     *
                     * 以前是建完轨道**立刻** `play()`，然后才去发请求。第一次朗读时
                     * 这条请求要走 TLS 握手 + 服务端起合成，头一块音频往往 1 秒以上才到
                     * （真机 logcat：`start()` 之后 1.2 秒才出现
                     * `restartIfDisabled(2225): releaseBuffer() track ... disabled due to
                     * previous underrun, restarting`）。空轨道在 `play()` 里等数据就是
                     * **欠载**；这次真机（HyperOS / 高通）在欠载恢复的那一刻把数据通路
                     * 重新拉起来，用户听到的就是"炸麦雪花杂音"。
                     *
                     * 第二次朗读之所以正常，是因为连接已经热了、头一块音频在几百毫秒内
                     * 就到了，赶在缓冲耗尽之前 —— 这也解释了"只有第一次放会炸"。
                     *
                     * 所以改成：先往轨道里写够 [PreRollBytes]，再 `play()`。
                     * `AudioTrack` 的文档也建议 MODE_STREAM 先 prime 再 play。
                     */
                    val gate = PcmPlaybackGate(
                        preRollBytes = preRollBytesFor(bufferBytesOf(created)),
                        start = {
                            if (generation != playbackGeneration || stopRequested) throw CancellationException("tts stopped")
                            created.play()
                        },
                        writeAll = { buffer, offset, length ->
                            if (generation != playbackGeneration || stopRequested) throw CancellationException("tts stopped")
                            writeFully(created, buffer, offset, length)
                        },
                    )
                    val writer = PcmWriter { buffer, offset, length -> gate.write(buffer, offset, length) }
                    val createdAt = SystemClock.elapsedRealtime()
                    var firstPcmAt = 0L
                    for (chunk in chunks) {
                        if (generation != playbackGeneration || stopRequested) break
                        DeepSeekTts.streamPcm(config, chunk, voiceId) { bytes, length ->
                            // 回调在 IO 线程、且是阻塞写的：这里检查停止标记，
                            // 用户点「停止」后下一次回调就会抛出去，不会把整段读完
                            if (generation != playbackGeneration || stopRequested) throw CancellationException("tts stopped")
                            if (firstPcmAt == 0L) firstPcmAt = SystemClock.elapsedRealtime()
                            writer.write(bytes, length)
                        }
                    }
                    // 短句可能连预滚量都凑不满：流结束时把剩下的放出来，别憋着不响。
                    // 用户按了停止就不该再开嗓（否则停了还会冒出一截声音）。
                    if (generation == playbackGeneration && !stopRequested) {
                        gate.finish()
                        /*
                         * ★ **写完不等于播完。**
                         *
                         * `AudioTrack.write` 只是把数据塞进缓冲（这里整整 1 秒 / 48000 字节），
                         * `finally` 里紧接着 `pause()` + `flush()` + `release()` 会把还没播出去的
                         * 尾巴一起丢掉 —— 用户听到的就是"最后几个字读不到"。
                         * 所以在收尾之前等播放头追上已写入的帧数（见 [awaitPlaybackEnd]）。
                         */
                        awaitPlaybackEnd(
                            track = created,
                            writtenFrames = writer.bytesWritten / DeepSeekTts.BYTES_PER_FRAME,
                        )
                    }
                    logPlayback(created, createdAt, firstPcmAt)
                } catch (cancelled: CancellationException) {
                    // 用户停止 / 被新的朗读顶掉：不是错误
                } catch (t: Throwable) {
                    failure = officialErrorText(t)
                    Log.w(TtsLogTag, "官方朗读失败", t)
                } finally {
                    if (audioTrack === track) audioTrack = null
                    runCatching { track?.pause() }
                    runCatching { track?.flush() }
                    runCatching { track?.release() }
                }
            }
            if (generation == playbackGeneration) {
                if (speakingId == id) speakingId = null
                failure?.let { error = it }
            }
        }
    }
    // ── 第三方 TTS（千问 MaaS / OpenAI 兼容）───────────────

    /**
     * 第三方通道：整段合成 → 落临时文件 → MediaPlayer 播放。
     *
     * 与官方通道（流式 PCM + AudioTrack）的区别：
     * - 第三方返回的是**完整音频文件**（wav/mp3），不是裸 PCM；
     * - 所以用 MediaPlayer 省去自己解 wav 头 / 处理采样率的麻烦，
     *   代价是首包延迟更高（要等合成完）。文字朗读场景可以接受。
     *
     * 停止：`stopPlayback` 会 `release()` 播放器并删临时文件；这里每次
     * 播放前都用新的 [playbackGeneration] 校验，避免"停了又响"。
     */
    private fun speakThirdParty(
        id: String,
        text: String,
        config: com.simplechat.app.data.VoiceApiConfig,
        voiceName: String,
    ) {
        val generation = playbackGeneration
        error = null
        speakingId = id
        thirdPartyJob?.cancel()
        thirdPartyJob = scope.launch {
            val result = withContext(Dispatchers.IO) {
                com.simplechat.app.data.VoiceTtsClient.synthesize(
                    config = config,
                    chatKey = thirdPartyChatKey,
                    chatBaseUrl = thirdPartyChatBaseUrl,
                    text = text,
                    voiceName = voiceName,
                )
            }
            if (generation != playbackGeneration) return@launch
            when (result) {
                is com.simplechat.app.data.VoiceTtsClient.Result.Failed -> {
                    com.simplechat.app.data.Diagnostics.log("TTS", "synth failed reason=${result.reason}")
                    speakingId = null
                    error = result.reason
                }
                is com.simplechat.app.data.VoiceTtsClient.Result.Ok -> {
                    val file = runCatching {
                        java.io.File(context.cacheDir, "tts-${java.util.UUID.randomUUID()}.wav")
                    }.getOrNull()
                    if (file == null) {
                        speakingId = null
                        error = Res.get(R.string.tts_failed)
                        return@launch
                    }
                    thirdPartyAudioFile = file
                    runCatching {
                        file.writeBytes(result.bytes)
                        val player = android.media.MediaPlayer()
                        mediaPlayer = player
                        player.setDataSource(file.absolutePath)
                        player.setOnCompletionListener {
                            if (generation == playbackGeneration) {
                                speakingId = null
                                releaseThirdPartyAudio()
                            }
                        }
                        player.setOnErrorListener { _, _, _ ->
                            if (generation == playbackGeneration) {
                                speakingId = null
                                error = Res.get(R.string.tts_failed)
                                releaseThirdPartyAudio()
                            }
                            true
                        }
                        player.setOnPreparedListener {
                            if (generation == playbackGeneration && mediaPlayer === player) {
                                runCatching { player.start() }.onFailure {
                                    speakingId = null
                                    error = Res.get(R.string.tts_failed)
                                    releaseThirdPartyAudio()
                                }
                            }
                        }
                        player.prepareAsync()
                    }.onFailure {
                        speakingId = null
                        error = Res.get(R.string.tts_failed)
                        releaseThirdPartyAudio()
                    }
                }
            }
        }
    }


    /**
     * 等轨道把**缓冲区里剩下的音频**播完。
     *
     * ### 为什么要等
     *
     * `AudioTrack.write` 返回只代表"数据进了缓冲"，不代表"已经播出来"。
     * 轨道缓冲是 1 秒（24 kHz × 2 B），流结束后立刻 `pause()` / `flush()` / `release()`
     * 会把这段还没播的尾巴整段丢掉 —— 真机反馈的"朗读提前截断、最后几个字听不到"
     * 就是这一处（一句话末尾一秒 ≈ 三四个汉字）。
     *
     * ### 判据
     *
     * `playbackHeadPosition` 是"已经播出去的帧数"，让它追上"已经交给轨道的帧数"
     * 就说明缓冲空了。轮询间隔 20ms 足够细（人耳感知不到），也不会把 IO 线程占满。
     *
     * 超时兜底：设备欠载 / 调度异常时不能把 IO 线程永远挂在这里 —— 那时这一秒
     * 本来也播不出来，记一行日志就好。用户按了停止则立即返回（停止就该立刻没声）。
     */
    private fun awaitPlaybackEnd(track: AudioTrack, writtenFrames: Long) {
        var played = runCatching { track.playbackHeadPosition.toLong() and UnsignedIntMask }
            .getOrNull() ?: return
        var remaining = pendingPlaybackFrames(writtenFrames, played)
        if (remaining <= 0L) return
        val deadline = SystemClock.elapsedRealtime() + drainBudgetMs(remaining)
        while (SystemClock.elapsedRealtime() < deadline) {
            if (stopRequested) return
            played = runCatching { track.playbackHeadPosition.toLong() and UnsignedIntMask }
                .getOrNull() ?: return
            remaining = pendingPlaybackFrames(writtenFrames, played)
            if (remaining <= 0L) return
            SystemClock.sleep(PlaybackPollMs)
        }
        Log.w(TtsLogTag, "官方朗读收尾超时：仍有 $remaining 帧（约 ${remaining * 1000 / DeepSeekTts.SAMPLE_RATE}ms）未播完")
    }

    /**
     * 把 PCM **完整**写进轨道（阻塞写）。
     *
     * 阻塞模式下正常应当一次写满；写不动（轨道被 release / 参数非法）就报错 ——
     * 静默丢字节同样会变成杂音。
     */
    private fun writeFully(track: AudioTrack, buffer: ByteArray, offset: Int, length: Int) {
        var done = 0
        while (done < length) {
            val written = track.write(buffer, offset + done, length - done)
            if (written <= 0) throw IOException("AudioTrack.write returned $written")
            done += written
        }
    }

    /**
     * 一次朗读的播放诊断（真机排查用，用户看不到）。
     *
     * `AudioTrack.underrunCount` 是判断"炸麦"的硬证据：只要它不是 0，说明数据没跟上播放，
     * 设备端就可能吐出杂音。正常应当是 0 —— 留一行日志，下次真机反馈时不用再猜。
     */
    private fun logPlayback(track: AudioTrack, createdAt: Long, firstPcmAt: Long) {
        val underruns = runCatching { track.underrunCount }.getOrDefault(0)
        val total = SystemClock.elapsedRealtime() - createdAt
        val firstData = if (firstPcmAt == 0L) -1 else firstPcmAt - createdAt
        val detail = "首块 ${firstData}ms / 共 ${total}ms / 缓冲 ${bufferBytesOf(track)}B / 欠载 $underruns 次"
        if (underruns > 0) Log.w(TtsLogTag, "官方朗读播放欠载：$detail") else Log.d(TtsLogTag, "官方朗读播放正常：$detail")
    }

    /** 24 kHz / 单声道 / 16 bit 的流式播放器。 */
    private fun createAudioTrack(): AudioTrack {
        val minBuffer = AudioTrack.getMinBufferSize(
            DeepSeekTts.SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        /*
         * 缓冲 1 秒（48000 字节）：跨分块请求的空档（重新建连 + 服务端重新起合成）
         * 也吃得下，不至于每换一块就欠载一次。
         */
        val wanted = DeepSeekTts.SAMPLE_RATE * DeepSeekTts.BYTES_PER_FRAME
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

    /**
     * 预滚字节数：攒够这么多才 `play()`（见 [speakOfficial] 的"先攒够再放"）。
     *
     * 必须给缓冲留出**至少一个网络读块**的空余：预滚阶段轨道还没 `play()`，写进去的
     * 数据不会被消费，写超缓冲会直接阻塞到死（`AudioTrack.write` 在阻塞模式下等空间）。
     */
    private fun preRollBytesFor(trackBufferSize: Int): Int =
        minOf(PreRollBytes, trackBufferSize - DeepSeekTts.STREAM_CHUNK_BYTES).coerceAtLeast(0)

    /**
     * 轨道缓冲的字节数。
     *
     * compileSdk 37 里 `getBufferSizeInBytes()` 已经没了，只剩帧数版本 —— 单声道
     * 16 bit 每帧 [DeepSeekTts.BYTES_PER_FRAME] 字节，换算回来即可。
     */
    private fun bufferBytesOf(track: AudioTrack): Int =
        runCatching { track.bufferSizeInFrames * DeepSeekTts.BYTES_PER_FRAME }
            .getOrDefault(0)
            .coerceAtLeast(0)

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
        stopPlayback(clearSpeaking = true)
        if (ActiveController === this) ActiveController = null
    }

    /**
     * 停掉当前播放（官方 PCM 与系统 TTS 都停）。
     *
     * 不碰 [error]：调用方可能正要写新的错误。
     */
    private fun stopPlayback(clearSpeaking: Boolean) {
        ++playbackGeneration
        pending = null
        utteranceId = null
        stopRequested = true
        officialJob?.cancel()
        officialJob = null
        thirdPartyJob?.cancel()
        thirdPartyJob = null
        releaseThirdPartyAudio()
        runCatching { audioTrack?.pause() }
        runCatching { audioTrack?.flush() }
        runCatching { engine?.stop() }
        if (clearSpeaking) speakingId = null
    }

    private fun releaseThirdPartyAudio() {
        runCatching { mediaPlayer?.release() }
        mediaPlayer = null
        runCatching { thirdPartyAudioFile?.delete() }
        thirdPartyAudioFile = null
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
        stopPlayback(clearSpeaking = true)
        if (ActiveController === this) ActiveController = null
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

        /**
         * 当前正在出声的控制器。
         *
         * 设置页的试听与对话页的朗读是**两个控制器**，各自有自己的 `AudioTrack`。
         * 两条轨道同时播同一段语音会混成"炸麦雪花音"；真机反馈过"试听还好好的、
         * 一读正文就炸麦"。开新播放前先停掉这个，跨页面的并发就没了。
         */
        @Volatile
        var ActiveController: SpeechOutputController? = null

        /**
         * 开嗓前先攒多少字节 = 0.5 秒（24 kHz × 2 B × 0.5）。
         *
         * 真机 logcat 实测：建轨道后立刻 `play()`，第一次朗读要等 1.2 秒才等到头一块
         * 音频（TLS 握手 + 服务端起合成），空轨道欠载，恢复时数据通路被重新拉起，
         * 出来就是"炸麦雪花杂音"。攒够半秒再 `play()`，轨道一开声就有数据。
         */
        const val PreRollBytes = 24_000

        /** `playbackHeadPosition` 是 32 位无符号帧计数，读成 Long 时要按无符号解释。 */
        const val UnsignedIntMask = 0xFFFF_FFFFL

        /** 收尾轮询间隔：20ms 内播出的音频还不到人耳能分辨的一小段。 */
        const val PlaybackPollMs = 20L

        /** 收尾预算的余量：算出来的时长之外再给一点，避免临界值上误报超时。 */
        const val DrainGraceMs = 1_000L
    }
}

/**
 * 缓冲里**还没播出去**的帧数。
 *
 * `writtenFrames` = 已经交给 `AudioTrack` 的帧数，`playedFrames` = 播放头位置。
 * 差值就是还在缓冲里排队的那一段 —— 朗读收尾要等的正是它（见 `awaitPlaybackEnd`）。
 * 纯函数，有单测（`SpeechDrainTest`）。
 */
internal fun pendingPlaybackFrames(writtenFrames: Long, playedFrames: Long): Long =
    (writtenFrames - playedFrames).coerceAtLeast(0L)

/**
 * 等这一小段播完最多要多久：按采样率换算，再给 1 秒余量。
 *
 * 只用于**收尾**（此时几乎全部音频已经播完），所以它是个几百毫秒到一秒级的数；
 * 用它去等整段朗读会等上几分钟 —— 那是 `AudioTrack.write` 的阻塞行为，不是这里的事。
 */
internal fun drainBudgetMs(remainingFrames: Long): Long =
    remainingFrames * 1000 / DeepSeekTts.SAMPLE_RATE + 1_000L

/**
 * PCM 写入器：保证写进 `AudioTrack` 的永远是**完整的 16 bit 帧**。
 *
 * 网络 `InputStream.read` 返回的字节数不保证是偶数。直接把奇数字节写进 PCM16
 * 轨道，从那一刻起采样就错位了（每两字节被拆成"上一帧的高字节 + 下一帧的低字节"），
 * 听起来正是**炸麦雪花音**。真机反馈的"试听正常、读正文突然炸麦，过一会儿又正常"
 * 就是这个：每个分块是一条独立 HTTP 流，错位只发生在同一个分块内，下一块重新对齐。
 *
 * 顺手兼容"服务端返回 WAV"（协议参数被网关忽略时）：首个数据块带 `RIFF` 头就跳过。
 */
internal class PcmWriter(
    /** 实际写入：返回写进去的字节数（`AudioTrack.write` 的语义）。 */
    private val write: (buffer: ByteArray, offset: Int, length: Int) -> Int,
) {

    /** 上一次剩下的半个采样（高字节）。-1 = 没有。 */
    private var carry: Int = -1

    private var headerChecked = false

    /**
     * 已经交给 `AudioTrack` 的**字节数**（完整帧，含 carry 拼出来的那半个采样）。
     *
     * 朗读收尾要用它算"还有多少帧没播"：写完不代表播完，见 `awaitPlaybackEnd`。
     */
    var bytesWritten: Long = 0L
        private set

    fun write(buffer: ByteArray, length: Int) {
        var offset = 0
        var remaining = length
        if (!headerChecked) {
            headerChecked = true
            // 网关忽略 format=pcm、回了 wav 时，前 44 字节是头，直接播会"啪"一声
            if (remaining >= WavHeaderSize && isRiffWave(buffer)) offset = WavHeaderSize
        }
        remaining -= offset
        if (remaining <= 0) return
        if (carry >= 0) {
            writeAll(byteArrayOf(carry.toByte(), buffer[offset]))
            carry = -1
            offset += 1
            remaining -= 1
            if (remaining <= 0) return
        }
        val even = remaining and 1.inv()
        if (even > 0) writeAll(buffer, offset, even)
        if (remaining and 1 == 1) carry = buffer[offset + remaining - 1].toInt() and 0xFF
    }

    private fun writeAll(buffer: ByteArray, offset: Int = 0, length: Int = buffer.size) {
        var done = 0
        while (done < length) {
            val written = write(buffer, offset + done, length - done)
            // 阻塞模式下正常应当写满；写不动（轨道被 release / 参数非法）就报错，
            // 否则会静默丢字节 → 同样会变成杂音
            if (written <= 0) throw IOException("AudioTrack.write returned $written")
            done += written
        }
        bytesWritten += length
    }

    private fun isRiffWave(buffer: ByteArray): Boolean =
        buffer.size >= 12 &&
            buffer[0] == 'R'.code.toByte() && buffer[1] == 'I'.code.toByte() &&
            buffer[2] == 'F'.code.toByte() && buffer[3] == 'F'.code.toByte() &&
            buffer[8] == 'W'.code.toByte() && buffer[9] == 'A'.code.toByte() &&
            buffer[10] == 'V'.code.toByte() && buffer[11] == 'E'.code.toByte()

    private companion object {
        const val WavHeaderSize = 44
    }
}

/**
 * "先攒够，再开嗓"。
 *
 * `AudioTrack` 一 `play()` 就开始**按实时速率消费缓冲**。轨道还没数据就 `play()`，
 * 它立刻进入欠载（underrun）；恢复那一刻设备端会把数据通路重新拉起来 —— 真机
 * （HyperOS / 高通）实测这时会放出"炸麦雪花杂音"。第一次朗读必然踩到：头发一条
 * TTS 请求要走 TLS 握手 + 服务端起合成，1 秒以上才吐第一块音频；第二次连接热了，
 * 数据赶在缓冲耗尽前到，就不炸了 —— 这正是"只有第一次放会炸"的原因。
 *
 * 所以在 `write` 里先按 [preRollBytes] 攒着，攒够了再一次性写进去并 [start]。
 * 攒的字节数必须小于轨道缓冲（见 `preRollBytesFor`），否则写超会阻塞到死。
 *
 * 上游 [DeepSeekTts.streamPcm] 会复用同一个 `ByteArray`，所以攒的时候必须拷贝。
 * 纯逻辑，有单测（`PcmPlaybackGateTest`）。
 */
internal class PcmPlaybackGate(
    /** 攒到多少字节才开嗓。0 = 一有数据就放（缓冲太小、留不出预滚空间的兜底）。 */
    private val preRollBytes: Int,
    /** 开嗓：`AudioTrack.play()`。 */
    private val start: () -> Unit,
    /** 写满：把 `length` 字节**全部**写进轨道（阻塞写）。 */
    private val writeAll: (buffer: ByteArray, offset: Int, length: Int) -> Unit,
) {

    /** 预滚阶段攒下的块（已拷贝，顺序即播放顺序）。 */
    private val pending = ArrayList<ByteArray>()

    private var pendingBytes = 0

    private var started = false

    /** 供界面/日志用：是否已经开嗓。 */
    val isStarted: Boolean get() = started

    /** 收下一个（已对齐的）PCM 块；返回收到的字节数，语义同 `AudioTrack.write`。 */
    fun write(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length <= 0) return 0
        if (started) {
            writeAll(buffer, offset, length)
            return length
        }
        pending += buffer.copyOfRange(offset, offset + length)
        pendingBytes += length
        if (pendingBytes >= preRollBytes) drain()
        return length
    }

    /**
     * 流结束：不足预滚量也要把剩下的放出来。
     *
     * 一个短句（"好的。"）可能只有几百毫秒，凑不满 0.5 秒的预滚 —— 不调这个就永远
     * 不 `play()`，用户点了朗读却一点声都没有。没有任何数据时不 `play()`：
     * 空轨道开声又是欠载。
     */
    fun finish() {
        if (started || pendingBytes == 0) return
        drain()
    }

    private fun drain() {
        started = true
        pending.forEach { writeAll(it, 0, it.size) }
        pending.clear()
        pendingBytes = 0
        start()
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
    thirdPartyConfig: com.simplechat.app.data.VoiceApiConfig? = null,
    chatKey: String = "",
    chatBaseUrl: String = "",
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
    LaunchedEffect(controller, thirdPartyConfig, chatKey, chatBaseUrl) {
        controller.updateThirdParty(thirdPartyConfig, chatKey, chatBaseUrl)
    }
    return controller
}




