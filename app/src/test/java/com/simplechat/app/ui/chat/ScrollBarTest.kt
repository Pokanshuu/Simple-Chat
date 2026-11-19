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

    @Test fun `minimum height thumb reaches both ends and advances within an item`() {
        // A 100px track with a 28px minimum thumb has 72px of travel.
        assertEquals(0f, scrollBarThumbTop(0f, 1f, 101, 72f), 0f)
        assertEquals(0.36f, scrollBarThumbTop(0.5f, 1f, 101, 72f), 0.0001f)
        assertEquals(72f, scrollBarThumbTop(100f, 1f, 101, 72f), 0f)
    }

    @Test fun `full viewport or short track keeps thumb at the top`() {
        assertEquals(0f, scrollBarThumbTop(0f, 2f, 2, 30f), 0f)
        assertEquals(0f, scrollBarThumbTop(4f, 1f, 10, 0f), 0f)
    }

    @Test fun `first message includes top padding in its travel`() {
        assertEquals(0f, scrollBarItemPosition(0, 0, 80, -20, 20), 0f)
        assertEquals(0.2f, scrollBarItemPosition(0, -20, 80, -20, 20), 0.001f)
    }
}
