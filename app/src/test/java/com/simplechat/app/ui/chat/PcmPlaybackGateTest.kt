package com.simplechat.app.ui.chat

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "先攒够，再开嗓"。
 *
 * 真机 logcat 证据：建完 `AudioTrack` 立刻 `play()`，然后 1.2 秒才等到头一块 PCM ——
 * `restartIfDisabled(...): disabled due to previous underrun, restarting`。空轨道欠载，
 * 恢复时数据通路被重新拉起，听感就是"炸麦雪花杂音"。这里把三件事钉死：
 *
 * 1. 预滚没攒够**绝不** `play()`；
 * 2. 攒下的数据必须**按原顺序**在 `play()` 之前写进轨道（不然开声就是后半句）；
 * 3. 短句凑不满预滚时，流结束要能把剩下的放出来（否则点了朗读没声音）。
 */
class PcmPlaybackGateTest {

    /** 记录"写了什么"与"什么时候开嗓"的事件序列。 */
    private class Recorder {
        val events = ArrayList<String>()
        private val chunks = ArrayList<ByteArray>()
        var started = 0

        fun start() {
            started++
            events += "start"
        }

        fun writeAll(buffer: ByteArray, offset: Int, length: Int) {
            events += "write:$length"
            chunks += buffer.copyOfRange(offset, offset + length)
        }

        fun bytes(): ByteArray = chunks.fold(ByteArray(0)) { acc, chunk -> acc + chunk }
    }

    private fun pcm(size: Int, seed: Int = 0): ByteArray =
        ByteArray(size) { index -> ((index * 17 + seed) and 0xFF).toByte() }

    @Test
    fun `预滚没攒够不开嗓`() {
        val recorder = Recorder()
        val gate = PcmPlaybackGate(10, recorder::start, recorder::writeAll)
        gate.write(pcm(4), 0, 4)
        gate.write(pcm(5, 1), 0, 5)
        assertEquals(0, recorder.started)
        assertFalse(gate.isStarted)
        assertTrue(recorder.events.isEmpty())
    }

    @Test
    fun `攒够时按原顺序写完再开嗓`() {
        val recorder = Recorder()
        val gate = PcmPlaybackGate(10, recorder::start, recorder::writeAll)
        /*
         * 上游 `DeepSeekTts.streamPcm` 复用同一个 ByteArray，攒的时候必须拷贝；
         * 这里每块用一个独立数组并事后改写，模拟"复用同一块缓冲"。
         */
        val source = pcm(16)
        val buffer = source.copyOfRange(0, 16)
        gate.write(buffer, 0, 4)
        buffer.fill(0) // 上游把缓冲改掉了
        gate.write(source.copyOfRange(4, 8), 0, 4)
        gate.write(source.copyOfRange(8, 12), 0, 4)

        assertEquals(1, recorder.started)
        assertEquals("攒够后先写数据、再开嗓", listOf("write:4", "write:4", "write:4", "start"), recorder.events)
        assertArrayEquals(source.copyOfRange(0, 12), recorder.bytes())
        assertTrue(gate.isStarted)
    }

    @Test
    fun `开嗓之后直通，不再重复 play`() {
        val recorder = Recorder()
        val gate = PcmPlaybackGate(6, recorder::start, recorder::writeAll)
        gate.write(pcm(6), 0, 6)
        gate.write(pcm(8, 3), 0, 8)
        assertEquals(1, recorder.started)
        assertEquals(listOf("write:6", "start", "write:8"), recorder.events)
        assertEquals(14, recorder.bytes().size)
    }

    @Test
    fun `短句凑不满预滚也要在流结束时放出来`() {
        val recorder = Recorder()
        val gate = PcmPlaybackGate(10, recorder::start, recorder::writeAll) // 10 字节 = 5 帧
        gate.write(pcm(4), 0, 4)
        gate.finish()
        assertEquals(1, recorder.started)
        assertEquals(listOf("write:4", "start"), recorder.events)
        assertEquals(4, recorder.bytes().size)
    }

    @Test
    fun `一个字节都没有时不开嗓`() {
        val recorder = Recorder()
        val gate = PcmPlaybackGate(10, recorder::start, recorder::writeAll)
        gate.finish()
        assertEquals(0, recorder.started)
        assertTrue(recorder.events.isEmpty())
    }

    @Test
    fun `空块与零长度写入不会开嗓`() {
        val recorder = Recorder()
        val gate = PcmPlaybackGate(0, recorder::start, recorder::writeAll)
        gate.write(pcm(4), 0, 0)
        assertEquals(0, recorder.started)
        gate.finish()
        assertEquals("没有数据时零预滚也不能开嗓", 0, recorder.started)
    }

    @Test
    fun `预滚为零时头一块数据直接开嗓`() {
        val recorder = Recorder()
        val gate = PcmPlaybackGate(0, recorder::start, recorder::writeAll)
        gate.write(pcm(4), 0, 4)
        assertEquals(1, recorder.started)
        assertEquals(listOf("write:4", "start"), recorder.events)
    }
}
