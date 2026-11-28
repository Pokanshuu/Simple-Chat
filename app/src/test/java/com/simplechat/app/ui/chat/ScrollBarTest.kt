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

    @Test fun `thumb height clamps around the minimum`() {
        assertEquals(28f, scrollBarThumbHeight(track = 200f, visibleFraction = 0.1f, minThumb = 28f), 0f)
        assertEquals(80f, scrollBarThumbHeight(track = 200f, visibleFraction = 0.4f, minThumb = 28f), 0f)
        assertEquals(200f, scrollBarThumbHeight(track = 200f, visibleFraction = 1f, minThumb = 28f), 0f)
        // 轨道比最小滑块还短时以轨道为上限，不越界
        assertEquals(20f, scrollBarThumbHeight(track = 20f, visibleFraction = 0.2f, minThumb = 28f), 0f)
    }

    @Test fun `unit and thumb top round-trip`() {
        val travel = 120f
        listOf(0f, 0.25f, 0.5f, 1f).forEach { unit ->
            val top = scrollBarThumbTopForUnit(unit, travel)
            assertEquals(unit, scrollBarUnitForThumbTop(top, travel), 0.0001f)
        }
        // 零行程（内容正好一屏）：位置固定为 0
        assertEquals(0f, scrollBarThumbTopForUnit(0.7f, 0f), 0f)
        assertEquals(0f, scrollBarUnitForThumbTop(50f, 0f), 0f)
    }

    @Test fun `drag unit maps to row endpoints and middle`() {
        assertEquals(0, scrollBarRowForUnit(0f, 10))
        assertEquals(9, scrollBarRowForUnit(1f, 10))
        assertEquals(5, scrollBarRowForUnit(0.5f, 10))
        assertEquals(0, scrollBarRowForUnit(0.5f, 1))
        assertEquals(0, scrollBarRowForUnit(0.5f, 0))
    }

    @Test fun `overshoot beyond both ends is damped`() {
        assertEquals(0f, scrollBarDampedUnit(0f), 0f)
        assertEquals(1f, scrollBarDampedUnit(1f), 0f)
        assertEquals(0.5f, scrollBarDampedUnit(0.5f), 0f)
        // 越界时位移被压缩到最多约 overshoot 的 40%
        val below = scrollBarDampedUnit(-1f, overshoot = 0.06f)
        val above = scrollBarDampedUnit(2f, overshoot = 0.06f)
        assertTrue(below < 0f && below > -0.06f)
        assertTrue(above > 1f && above < 1.06f)
    }

    @Test fun `hit test only claims the interactive band`() {
        val geometry = ScrollBarGeometry(unit = 0.5f, thumbTop = 100f, thumbHeight = 48f, travel = 200f, track = 300f)
        // 命中带宽度 32dp：x 在带外一律不拦
        assertEquals(ScrollBarHit.NONE, scrollBarHitTest(100f, 120f, 400f, geometry, interactive = true, 96f, 30f))
        // Ready：滑块内是 THUMB，滑块外但仍在带内是 TRACK
        assertEquals(ScrollBarHit.THUMB, scrollBarHitTest(360f, 120f, 400f, geometry, interactive = true, 96f, 30f))
        assertEquals(ScrollBarHit.TRACK, scrollBarHitTest(360f, 20f, 400f, geometry, interactive = true, 96f, 30f))
        // Thin：远离滑块的落点不拦
        assertEquals(ScrollBarHit.NONE, scrollBarHitTest(360f, 20f, 400f, geometry, interactive = false, 96f, 30f))
        assertEquals(ScrollBarHit.THUMB, scrollBarHitTest(360f, 130f, 400f, geometry, interactive = false, 96f, 30f))
    }
}
