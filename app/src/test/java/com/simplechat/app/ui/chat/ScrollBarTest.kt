package com.simplechat.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrollBarTest {
    @Test fun `thumb advances while scrolling inside one tall message`() {
        val positions = (0..8).map { step ->
            scrollBarItemPosition(index = 2, offset = -step * 100, extent = 900, viewportEdge = 0, leadingPadding = 0)
        }
        assertTrue(positions.zipWithNext().all { (previous, current) -> previous < current })
        assertTrue(positions.last() < 3f)
    }

    @Test fun `different height messages meet at the same position`() {
        val endOfTallMessage = scrollBarItemPosition(2, -900, 900, 0, 0)
        val startOfShortMessage = scrollBarItemPosition(3, 0, 40, 0, 0)
        assertEquals(3f, endOfTallMessage, 0f)
        assertEquals(endOfTallMessage, startOfShortMessage, 0f)
    }

    @Test fun `first message includes top padding in its travel`() {
        assertEquals(0f, scrollBarItemPosition(0, 0, 80, -20, 20), 0f)
        assertEquals(0.2f, scrollBarItemPosition(0, -20, 80, -20, 20), 0.001f)
    }
}
