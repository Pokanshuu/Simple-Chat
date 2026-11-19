package com.simplechat.app.ui.chat

import android.Manifest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.annotation.RequiresPermission
import com.simplechat.app.net.DeepSeekAsr
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

private const val SAMPLE_RATE = 16_000
private const val BYTES_PER_SAMPLE = 2
private const val FRAME_SAMPLES = SAMPLE_RATE / 50 // 20 ms
private const val MIN_SAMPLES = SAMPLE_RATE * 300 / 1000
private const val MAX_SECONDS = DeepSeekAsr.MAX_AUDIO_SECONDS
private const val WAV_HEADER_BYTES = 44

/**
 * Captures a WAV while held. [isHeld] is read on IO and [onLevel] runs in the caller's context.
 * Returns null for fewer than 300 ms of audio. Permission errors, recorder failures and
 * coroutine cancellation propagate to the caller.
 */
@RequiresPermission(Manifest.permission.RECORD_AUDIO)
internal suspend fun recordVoicePcm(isHeld: () -> Boolean, onLevel: (Float) -> Unit): ByteArray? {
    val callbackContext = currentCoroutineContext().minusKey(Job)
    return withContext(Dispatchers.IO) {
        if (!isHeld()) return@withContext null
        val channel = AudioFormat.CHANNEL_IN_MONO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, channel, encoding)
        if (minBuffer <= 0) throw IOException("Unsupported microphone format ($minBuffer)")

        val samples = ShortArray(FRAME_SAMPLES)
        val wav = VoiceWavBuffer(MAX_SECONDS * SAMPLE_RATE)
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC, SAMPLE_RATE, channel, encoding,
            maxOf(minBuffer, 4 * FRAME_SAMPLES * BYTES_PER_SAMPLE),
        )
        try {
            if (recorder.state != AudioRecord.STATE_INITIALIZED) throw IOException("Microphone initialization failed")
            if (!isHeld()) return@withContext null
            ensureActive()
            recorder.startRecording()
            if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) throw IOException("Microphone did not start")

            val startedAt = System.nanoTime()
            var frameSamples = 0
            while (isHeld() && wav.remainingSamples > frameSamples &&
                System.nanoTime() - startedAt < MAX_SECONDS * 1_000_000_000L
            ) {
                ensureActive()
                // Poll in short intervals so gesture release and cancellation do not depend on a
                // blocking AudioRecord read completing, even when the device stops delivering data.
                val count = recorder.read(
                    samples, frameSamples,
                    minOf(samples.size, wav.remainingSamples) - frameSamples,
                    AudioRecord.READ_NON_BLOCKING,
                )
                if (count < 0) throw IOException("Microphone read failed ($count)")
                if (count == 0) {
                    delay(10)
                    continue
                }
                frameSamples += count
                if (frameSamples == minOf(samples.size, wav.remainingSamples)) {
                    wav.append(samples, frameSamples)
                    val level = pcmRms(samples, frameSamples)
                    withContext(callbackContext) { onLevel(level) }
                    frameSamples = 0
                }
            }
            ensureActive()
            wav.append(samples, frameSamples)
        } finally {
            try {
                if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop()
            } finally {
                recorder.release()
            }
        }
        if (wav.sampleCount < MIN_SAMPLES) null else wav.toWav()
    }
}

/** Linear RMS of signed PCM16, normalized to [0, 1]. Only the read portion is measured. */
internal fun pcmRms(samples: ShortArray, count: Int): Float {
    require(count in 0..samples.size)
    if (count == 0) return 0f
    var squared = 0L
    for (index in 0 until count) {
        val sample = samples[index].toLong()
        squared += sample * sample
    }
    return (sqrt(squared.toDouble() / count) / 32768.0).toFloat()
}

internal class VoiceWavBuffer(private val maxSamples: Int) {
    private val buffer = ByteBuffer.allocate(WAV_HEADER_BYTES + maxSamples * BYTES_PER_SAMPLE)
        .order(ByteOrder.LITTLE_ENDIAN).apply { position(WAV_HEADER_BYTES) }

    val sampleCount: Int get() = (buffer.position() - WAV_HEADER_BYTES) / BYTES_PER_SAMPLE
    val remainingSamples: Int get() = maxSamples - sampleCount

    fun append(samples: ShortArray, count: Int) {
        require(count in 0..minOf(samples.size, remainingSamples))
        for (index in 0 until count) buffer.putShort(samples[index])
    }

    fun toWav(): ByteArray {
        val totalBytes = buffer.position()
        buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN).apply {
            position(0)
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(totalBytes - 8)
            put("WAVEfmt ".toByteArray(Charsets.US_ASCII))
            putInt(16) // PCM format chunk size
            putShort(1) // PCM
            putShort(1) // Mono
            putInt(SAMPLE_RATE)
            putInt(SAMPLE_RATE * BYTES_PER_SAMPLE)
            putShort(BYTES_PER_SAMPLE.toShort())
            putShort(16) // Bits per sample
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(totalBytes - WAV_HEADER_BYTES)
        }
        return buffer.array().copyOf(totalBytes)
    }
}
