package com.simplechat.app.ui.chat

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
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

/**
 * 语音输入状态。
 *
 * 刻意只有三档，与用户能看到的三种界面一一对应：
 * 没在录 / 正在录 / 刚出错。
 */
data class VoiceInputState(
    val listening: Boolean = false,
    /** 识别中的中间结果，实时回显到输入框。 */
    val partial: String = "",
    /** 失败原因；非空时界面弹一次提示，之后由调用方清掉。 */
    val error: String? = null,
    /** 当前设备有没有可用的识别服务。没有就不该露出麦克风。 */
    val available: Boolean = false,
)

/**
 * 语音输入。用**系统**识别服务（`SpeechRecognizer`），不接服务端 ASR。
 *
 * ### 为什么用系统识别而不是官方 demo 的 `/v1/tools/asr`
 * 1. 官方那个端点只在 DeepSeek 官方域上提供，OpenCode Go / 自建端点没有；
 * 2. 走系统识别**不需要额外网络请求**，也不额外计费；
 * 3. 系统识别在多数机型上可用，且支持中文。
 *
 * 代价是识别质量取决于机型（有的走本地、有的走厂商云），以及**需要
 * `RECORD_AUDIO` 运行时权限** —— 本项目唯一的一个。
 *
 * ### 为什么把 partial 也回显
 * 录音时看得见字，用户才知道"它在听"，也才敢在识别错时立刻停下重说。
 */
class VoiceInputController(
    private val context: Context,
    private val onText: (String) -> Unit,
) {
    var state by mutableStateOf(VoiceInputState())
        private set

    private var recognizer: SpeechRecognizer? = null

    /** 本次录音已确认的文字（中间结果每次都是全量，不需要累加）。 */
    private var committed: String? = null

    fun refreshAvailability() {
        state = state.copy(available = runCatching {
            SpeechRecognizer.isRecognitionAvailable(context)
        }.getOrDefault(false))
    }

    fun start() {
        if (state.listening) return
        if (!state.available) {
            state = state.copy(error = Res.get(R.string.voice_unavailable))
            return
        }
        val engine = recognizer ?: createRecognizer().also { recognizer = it }
        committed = null
        state = state.copy(listening = true, partial = "", error = null)
        runCatching { engine.startListening(intent()) }.onFailure {
            state = state.copy(listening = false, error = it.message)
        }
    }

    /** 主动结束录音：让引擎收尾并回一次结果，而不是丢弃。 */
    fun stop() {
        if (!state.listening) return
        runCatching { recognizer?.stopListening() }
    }

    /** 放弃这次录音。 */
    fun cancel() {
        runCatching { recognizer?.cancel() }
        state = state.copy(listening = false, partial = "")
    }

    fun consumeError() {
        if (state.error != null) state = state.copy(error = null)
    }

    fun release() {
        runCatching { recognizer?.destroy() }
        recognizer = null
        state = state.copy(listening = false)
    }

    private fun createRecognizer(): SpeechRecognizer =
        SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) = Unit
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit

                override fun onPartialResults(partialResults: Bundle?) {
                    val text = partialResults.firstResult()
                    if (text.isNullOrBlank()) return
                    state = state.copy(partial = text)
                    // 中间结果同样写进输入框：用户能看到"它听成了什么"
                    onText(text)
                }

                override fun onResults(results: Bundle?) {
                    val text = results.firstResult().orEmpty()
                    state = state.copy(listening = false, partial = "")
                    if (text.isNotBlank()) {
                        committed = text
                        onText(text)
                    } else if (committed.isNullOrBlank()) {
                        // 一声都没识别出来：给个明确说法，别让按钮自己转回原样
                        state = state.copy(error = Res.get(R.string.voice_empty))
                    }
                }

                override fun onError(error: Int) {
                    state = state.copy(listening = false, partial = "", error = describe(error))
                }
            })
        }

    /** 识别参数：中文优先，也允许说英文；只要一段短语音。 */
    private fun intent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(
            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
        )
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        // 跟随界面语言：中文界面就按中文识别，不说人话的"中英混识"反而更差
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, localeTag())
    }

    private fun localeTag(): String =
        if (Res.bool(R.bool.ui_is_cjk)) "zh-CN" else Locale.getDefault().toLanguageTag()

    private fun describe(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_AUDIO -> Res.get(R.string.voice_error_audio)
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> Res.get(R.string.voice_error_permission)
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
            Res.get(R.string.voice_error_network)
        SpeechRecognizer.ERROR_NO_MATCH -> Res.get(R.string.voice_empty)
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> Res.get(R.string.voice_error_busy)
        SpeechRecognizer.ERROR_SERVER -> Res.get(R.string.voice_error_server)
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> Res.get(R.string.voice_empty)
        else -> Res.get(R.string.voice_unavailable)
    }
}

/** `Bundle` 里第一条识别结果。系统给的是 `ArrayList<String>`，取第一个。 */
private fun Bundle?.firstResult(): String? =
    this?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

/** 记住一个 [VoiceInputController]，并在离开界面时释放识别服务。 */
@Composable
fun rememberVoiceInput(onText: (String) -> Unit): VoiceInputController {
    val context = LocalContext.current
    // 用 applicationContext：识别服务会持有传入的 Context，拿 Activity 会把它一起留住
    val appContext = remember(context) { context.applicationContext }
    val controller = remember(appContext) { VoiceInputController(appContext, onText) }
    DisposableEffect(controller) {
        controller.refreshAvailability()
        onDispose { controller.release() }
    }
    return controller
}
