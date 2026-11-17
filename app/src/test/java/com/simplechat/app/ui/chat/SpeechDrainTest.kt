package com.simplechat.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 朗读收尾的**等待判据**。
 *
 * 真机反馈"朗读提前截断、最后几个字听不到"：`AudioTrack.write` 只是把数据塞进缓冲
 * （这里整整 1 秒），流结束后立刻 `pause/flush/release` 会把还没播的尾巴丢掉。
 * 收尾要等"已播帧数"追上"已写入帧数"，这两个纯函数就是那个判据和它的超时预算。
 */
class SpeechDrainTest {

    @Test
    fun `缓冲里还有未播完的帧`() {
        // 写了 24000 帧（1 秒），只播到第 19000 帧 → 还有 5000 帧
        assertEquals(5_000L, pendingPlaybackFrames(writtenFrames = 24_000, playedFrames = 19_000))
    }

    @Test
    fun `播放头追上写入帧数就算播完`() {
        assertEquals(0L, pendingPlaybackFrames(writtenFrames = 24_000, playedFrames = 24_000))
        // 极端情况下播放头可能略超过写入帧数（设备缓冲实现差异），不能变成负数
        assertEquals(0L, pendingPlaybackFrames(writtenFrames = 24_000, playedFrames = 24_100))
    }

    @Test
    fun `什么都没写时不需要等`() {
        assertEquals(0L, pendingPlaybackFrames(writtenFrames = 0, playedFrames = 0))
        // 没有帧要等，预算只剩那 1 秒余量（负值也不能出现）
        assertEquals(1_000L, drainBudgetMs(0))
        assertEquals(1_000L, drainBudgetMs(-5))
    }

    @Test
    fun `一秒的尾巴最多等一秒加余量`() {
        // 24000 帧 = 1 秒（24 kHz）：预算 1000ms + 1000ms 余量
        assertEquals(2_000L, drainBudgetMs(24_000))
        // 半秒的尾巴
        assertEquals(1_500L, drainBudgetMs(12_000))
    }
}
