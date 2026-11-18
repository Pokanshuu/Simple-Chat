package com.simplechat.app.ui.markdown

import org.junit.Assert.assertEquals
import org.junit.Test

class RevealBoundaryTest {
    @Test
    fun `one unit of budget advances past a whole supplementary character`() {
        val text = "A\uD83D\uDE00B"
        assertEquals(1, nextRevealEnd(text, 0, 2))
        assertEquals(3, nextRevealEnd(text, 1, 1))
        assertEquals(4, nextRevealEnd(text, 3, 1))
    }

    @Test
    fun `a split delta waits for the low surrogate`() {
        assertEquals(0, nextRevealEnd("\uD83D", 0, 1))
        assertEquals(1, nextRevealEnd("A\uD83D", 0, 2))
        assertEquals(3, nextRevealEnd("A\uD83D\uDE00", 1, 1))
    }
}
