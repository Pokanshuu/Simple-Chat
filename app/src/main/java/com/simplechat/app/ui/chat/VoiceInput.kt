package com.simplechat.app.ui.chat

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.simplechat.app.R
import com.simplechat.app.data.Res
import com.simplechat.app.net.DeepSeekAsr
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal data class VoiceInputState(
    val systemAvailable: Boolean = false,
    val holding: Boolean = false,
    val processing: Boolean = false,
) {
    val active: Boolean get() = holding || processing
}

internal class VoiceInputController(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    var state by mutableStateOf(VoiceInputState())
        private set
    var level by mutableStateOf(0f)
        private set

    private class Session(val onResult: (String) -> Unit, val onError: (String) -> Unit) {
        val held = AtomicBoolean(true)
        var recognizer: SpeechRecognizer? = null
        var recording: Job? = null
        var timeout: Job? = null
        var outcome: Result<String>? = null
    }

    private var session: Session? = null

    fun refreshAvailability() {
        state = state.copy(systemAvailable = runCatching {
            onDeviceAvailable() || SpeechRecognizer.isRecognitionAvailable(context)
        }.getOrDefault(false))
    }

    private fun onDeviceAvailable(): Boolean = Build.VERSION.SDK_INT >= 31 &&
        SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    fun start(official: DeepSeekAsr.Config?, onResult: (String) -> Unit, onError: (String) -> Unit): Boolean {
        if (session != null || (official == null && !state.systemAvailable)) return false
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            onError(Res.get(R.string.voice_error_permission))
            return false
        }
        val run = Session(onResult, onError)
        session = run
        state = state.copy(holding = true)
        if (official != null) {
            run.recording = scope.launch {
                try {
                    val wav = try {
                        recordVoicePcm(run.held::get) { rms -> updateLevel(run, kotlin.math.sqrt(rms * 4f)) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        complete(run, error(R.string.voice_error_audio))
                        return@launch
                    }
                    // A recording limit can be reached before the finger lifts. Never upload until release.
                    while (run.held.get()) delay(30)
                    val outcome = if (wav == null) error(R.string.voice_empty) else {
                        Result.success(DeepSeekAsr.transcribe(official, wav))
                    }
                    complete(run, outcome)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    complete(run, error(R.string.voice_error_server))
                }
            }
        } else {
            try {
                val recognizer = if (SpeechRecognizer.isRecognitionAvailable(context)) {
                    SpeechRecognizer.createSpeechRecognizer(context)
                } else if (Build.VERSION.SDK_INT >= 31 && onDeviceAvailable()) {
                    SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                } else {
                    cancel()
                    onError(Res.get(R.string.voice_unavailable))
                    return false
                }
                run.recognizer = recognizer
                recognizer.setRecognitionListener(listener(run))
                recognizer.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                })
            } catch (_: RuntimeException) {
                cancel()
                onError(Res.get(R.string.voice_unavailable))
                return false
            }
        }
        return true
    }

    fun finish() {
        val run = session ?: return
        if (!run.held.getAndSet(false)) return
        state = state.copy(holding = false, processing = true)
        level = 0f
        run.outcome?.let {
            complete(run, it)
            return
        }
        if (run.recognizer != null) {
            run.timeout = scope.launch {
                delay(6_000)
                complete(run, error(R.string.voice_error_server))
            }
            runCatching { run.recognizer?.stopListening() }.onFailure {
                complete(run, error(R.string.voice_error_server))
            }
        }
    }

    fun cancel() {
        val run = session ?: return
        session = null
        state = state.copy(holding = false, processing = false)
        level = 0f
        run.held.set(false)
        run.timeout?.cancel()
        run.recording?.cancel()
        runCatching { run.recognizer?.destroy() }
    }

    private fun updateLevel(run: Session, level: Float) {
        if (session === run && state.holding) this.level = level.coerceIn(0f, 1f)
    }

    private fun complete(run: Session, result: Result<String>) {
        if (session !== run) return
        if (run.held.get()) {
            run.outcome = result
            updateLevel(run, 0f)
            return
        }
        cancel()
        result.fold(
            onSuccess = { text ->
                if (text.isBlank()) run.onError(Res.get(R.string.voice_empty)) else run.onResult(text.trim())
            },
            onFailure = { run.onError(it.message ?: Res.get(R.string.voice_error_server)) },
        )
    }

    private fun listener(run: Session) = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = updateLevel(run, ((rmsdB + 1f) / 12f))
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = updateLevel(run, 0f)
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
        override fun onPartialResults(partialResults: Bundle?) = Unit
        override fun onResults(results: Bundle?) {
            complete(run, Result.success(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull().orEmpty()))
        }
        override fun onError(code: Int) {
            complete(run, error(when (code) {
                SpeechRecognizer.ERROR_AUDIO -> R.string.voice_error_audio
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> R.string.voice_error_permission
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> R.string.voice_error_network
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> R.string.voice_empty
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> R.string.voice_error_busy
                else -> R.string.voice_error_server
            }))
        }
    }

    private fun error(resource: Int): Result<String> = Result.failure(IllegalStateException(Res.get(resource)))
}

@Composable
internal fun rememberVoiceInput(): VoiceInputController {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val controller = remember(context, scope) { VoiceInputController(context, scope) }
    DisposableEffect(controller) {
        controller.refreshAvailability()
        onDispose { controller.cancel() }
    }
    return controller
}
