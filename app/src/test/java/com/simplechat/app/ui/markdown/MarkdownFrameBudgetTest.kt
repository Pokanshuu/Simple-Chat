package com.simplechat.app.ui.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class MarkdownFrameBudgetTest {
    @Test
    fun `healthy frames run continuously and expensive frames yield bounded time for input`() {
        assertEquals(0L, adaptiveCooldownMs(8))
        assertEquals(0L, adaptiveCooldownMs(16))
        assertEquals(48L, adaptiveCooldownMs(40))
        assertEquals(96L, adaptiveCooldownMs(100))
        assertEquals(96L, adaptiveCooldownMs(Long.MAX_VALUE))
    }

    @Test
    fun `backlog shrinks at the same rate over equal wall time on different displays`() {
        fun revealedAfter(frameMs: Long, frames: Int): Int {
            val budget = RevealBudget()
            var pending = 1_000
            repeat(frames) { pending -= budget.advance(pending, frameMs * 1_000_000) }
            return 1_000 - pending
        }
        val fast = revealedAfter(8, 16)
        val normal = revealedAfter(16, 8)
        val slow = revealedAfter(32, 4)
        assertTrue("128ms should reveal part of the burst: $normal", normal in 650..800)
        assertTrue(abs(fast - normal) <= 2)
        assertTrue(abs(slow - normal) <= 2)
    }

    @Test
    fun `sub-character time carries to the next frame`() {
        val budget = RevealBudget()
        assertEquals(0, budget.advance(2, 10_000_000))
        assertEquals(1, budget.advance(2, 10_000_000))
        assertEquals(1, budget.advance(1, 16_000_000))
    }

    @Test
    fun `even a long pause cannot spend infinite credit on a new burst`() {
        val advance = RevealBudget().advance(1_000, Long.MAX_VALUE)
        assertTrue("expected bounded catch-up: $advance", advance in 1 until 1_000)
        assertEquals(1, RevealBudget().advance(1, Long.MAX_VALUE))
    }
}
