package com.simplechat.app.ui.chat

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PCM 写入器的**帧对齐**。
 *
 * 真机反馈过"读正文突然炸麦雪花音，过一会儿又正常"：网络 `InputStream.read`
 * 返回的字节数不保证是偶数，而 PCM16 每帧 2 字节 —— 从某一次奇数长度的写入开始，
 * 采样就整体错位一个字节，听起来就是白噪声。这里把"任意切分输入 → 输出永远是
 * 完整帧、且字节顺序不变"钉死。
 */
class PcmWriterTest {

    private class Recorder {
        val chunks = ArrayList<ByteArray>()
        fun write(buffer: ByteArray, offset: Int, length: Int): Int {
            chunks += buffer.copyOfRange(offset, offset + length)
            return length
        }

        fun bytes(): ByteArray = chunks.fold(ByteArray(0)) { acc, chunk -> acc + chunk }
    }

    private fun pcm(size: Int, seed: Int = 0): ByteArray =
        ByteArray(size) { index -> ((index * 31 + seed) and 0xFF).toByte() }

    @Test
    fun `奇数长度的读入也能原样还原成完整帧`() {
        val source = pcm(1001)
        val recorder = Recorder()
        val writer = PcmWriter { buffer, offset, length -> recorder.write(buffer, offset, length) }
        // 用一堆奇数长度切分（模拟网络读）
        var offset = 0
        val sizes = listOf(1, 3, 7, 13, 1, 255, 129)
        var i = 0
        while (offset < source.size) {
            val size = minOf(sizes[i % sizes.size], source.size - offset)
            writer.write(source.copyOfRange(offset, offset + size), size)
            offset += size
            i++
        }
        val out = recorder.bytes()
        // 1001 字节 → 500 完整帧；最后 1 字节留在 carry 里（尚未成帧），不写入
        assertEquals(1000, out.size)
        assertArrayEquals(source.copyOf(1000), out)
        // 每一次写入都必须是偶数长度（完整帧）
        assertTrue(recorder.chunks.all { it.size % 2 == 0 })
    }

    @Test
    fun `跨块拼接的半个采样不会丢`() {
        val source = pcm(10)
        val recorder = Recorder()
        val writer = PcmWriter { buffer, offset, length -> recorder.write(buffer, offset, length) }
        // 先来 1 字节（半个采样），再来 9 字节
        writer.write(source.copyOfRange(0, 1), 1)
        writer.write(source.copyOfRange(1, 10), 9)
        assertArrayEquals(source, recorder.bytes())
    }

    @Test
    fun `WAV 头被跳过`() {
        val header = ByteArray(44).apply {
            this[0] = 'R'.code.toByte(); this[1] = 'I'.code.toByte()
            this[2] = 'F'.code.toByte(); this[3] = 'F'.code.toByte()
            this[8] = 'W'.code.toByte(); this[9] = 'A'.code.toByte()
            this[10] = 'V'.code.toByte(); this[11] = 'E'.code.toByte()
        }
        val body = pcm(100)
        val recorder = Recorder()
        val writer = PcmWriter { buffer, offset, length -> recorder.write(buffer, offset, length) }
        val stream = header + body
        writer.write(stream, stream.size)
        assertArrayEquals(body, recorder.bytes())
    }

    @Test
    fun `写入返回部分长度时会继续写完`() {
        val source = pcm(20)
        val recorder = Recorder()
        var calls = 0
        val writer = PcmWriter { buffer, offset, length ->
            calls++
            // 模拟 AudioTrack 只写进去一半
            val written = minOf(length, 4)
            recorder.write(buffer, offset, written)
            written
        }
        writer.write(source, source.size)
        assertArrayEquals(source, recorder.bytes())
        assertTrue("应当多次调用直到写完", calls > 1)
    }

    /**
     * 收尾时要靠这个计数判断"还有多少帧没播"（见 `SpeechOutput.awaitPlaybackEnd`），
     * 所以它必须与**真正写进轨道**的字节数一致：半个采样（carry）不算、WAV 头不算。
     */
    @Test
    fun `已写入字节数与实际写入轨道的字节数一致`() {
        val header = ByteArray(44).apply {
            this[0] = 'R'.code.toByte(); this[1] = 'I'.code.toByte()
            this[2] = 'F'.code.toByte(); this[3] = 'F'.code.toByte()
            this[8] = 'W'.code.toByte(); this[9] = 'A'.code.toByte()
            this[10] = 'V'.code.toByte(); this[11] = 'E'.code.toByte()
        }
        val body = pcm(1001) // 奇数长度：最后一个字节留在 carry 里，不算写过
        val recorder = Recorder()
        val writer = PcmWriter { buffer, offset, length -> recorder.write(buffer, offset, length) }
        val stream = header + body
        writer.write(stream, stream.size)
        assertEquals(1000L, writer.bytesWritten)
        assertEquals(recorder.bytes().size.toLong(), writer.bytesWritten)
    }
}
