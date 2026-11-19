package com.simplechat.app.ui.chat

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class VoicePcmRecordingTest {
    @Test
    fun wavContainsLittleEndianPcm16MonoAt16kHz() {
        val wav = VoiceWavBuffer(4)
        wav.append(shortArrayOf(0x1234, -2, 0), 2)
        wav.append(shortArrayOf(Short.MIN_VALUE), 1)
        val bytes = wav.toWav()
        val data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(50, bytes.size)
        assertEquals("RIFF", String(bytes, 0, 4, Charsets.US_ASCII))
        assertEquals(bytes.size - 8, data.getInt(4))
        assertEquals("WAVEfmt ", String(bytes, 8, 8, Charsets.US_ASCII))
        assertEquals(16, data.getInt(16))
        assertEquals(1, data.getShort(20).toInt())
        assertEquals(1, data.getShort(22).toInt())
        assertEquals(16_000, data.getInt(24))
        assertEquals(32_000, data.getInt(28))
        assertEquals(2, data.getShort(32).toInt())
        assertEquals(16, data.getShort(34).toInt())
        assertEquals("data", String(bytes, 36, 4, Charsets.US_ASCII))
        assertEquals(6, data.getInt(40))
        assertEquals(0x1234, data.getShort(44).toInt())
        assertEquals(-2, data.getShort(46).toInt())
        assertEquals(Short.MIN_VALUE, data.getShort(48))
    }

    @Test
    fun wavBufferKeepsTheConfiguredSampleLimit() {
        val wav = VoiceWavBuffer(2)
        wav.append(shortArrayOf(1, 2), 2)
        assertEquals(2, wav.sampleCount)
        assertEquals(0, wav.remainingSamples)
        assertThrows(IllegalArgumentException::class.java) { wav.append(shortArrayOf(3), 1) }
        assertEquals(48, wav.toWav().size)
    }

    @Test
    fun rmsUsesOnlyRecordedSamplesAndDoesNotOverflow() {
        assertEquals(0f, pcmRms(shortArrayOf(Short.MIN_VALUE), 0), 0f)
        assertEquals(0f, pcmRms(shortArrayOf(0, Short.MIN_VALUE), 1), 0f)
        assertEquals(0.5f, pcmRms(shortArrayOf(16_384, -16_384, 0), 2), 0f)
        assertEquals(1f, pcmRms(ShortArray(320) { Short.MIN_VALUE }, 320), 0f)
        assertEquals(0.70710677f, pcmRms(shortArrayOf(0, Short.MIN_VALUE), 2), 1e-6f)
    }
}
