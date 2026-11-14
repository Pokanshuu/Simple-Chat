package com.simplechat.app.ui.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 流式渲染的**自适应冷却**。
 *
 * 这条规则是把官方 Chat Demo 的 `useThrottleMarkdownRender`
 * （渲染耗时 × 2、上限 2 秒）搬到 Compose 的核心：
 * 轻内容每帧都更新（连续流动），重内容自动降频（不放弃帧）。
 *
 * 判错的代价很直接：冷却给大了 → 文字一跳一跳；给小了 → 长文档每帧超时、整屏卡死。
 */
class MarkdownFrameBudgetTest {

    @Test
    fun `一帧之内不算贵，不需要冷却`() {
        assertEquals(0L, adaptiveCooldownMs(0))
        assertEquals(0L, adaptiveCooldownMs(8))
        assertEquals(0L, adaptiveCooldownMs(16))
    }

    @Test
    fun `超出预算的部分按两倍冷却`() {
        // 一帧拖到 40ms：超了 24ms → 冷却 48ms
        assertEquals(48L, adaptiveCooldownMs(40))
        // 一帧 100ms：超 84 → 168
        assertEquals(168L, adaptiveCooldownMs(100))
    }

    @Test
    fun `冷却有上限，不会因为一次异常就停更`() {
        assertEquals(2_000L, adaptiveCooldownMs(5_000))
        assertEquals(2_000L, adaptiveCooldownMs(60_000))
    }

    @Test
    fun `耗时越长冷却越长（单调）`() {
        val samples = listOf(16L, 17L, 24L, 33L, 50L, 120L, 400L)
        val cooldowns = samples.map(::adaptiveCooldownMs)
        cooldowns.zipWithNext().forEach { (a, b) ->
            assertTrue("冷却应当单调不减：$cooldowns", b >= a)
        }
    }

    @Test
    fun `最长间隔下仍然每次只跳过有限帧`() {
        // 上限 2 秒：再糟的文档也不会"永远不更新"
        val worst = adaptiveCooldownMs(Long.MAX_VALUE / 2)
        assertEquals(2_000L, worst)
    }
}
