package com.simplechat.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FollowScrollStepTest {
    @Test fun `equal wall time closes the same distance at different refresh rates`() {
        var at60 = 100f
        var at120 = 100f
        repeat(3) { at60 -= followScrollStep(at60, 16_666_666L) }
        repeat(6) { at120 -= followScrollStep(at120, 8_333_333L) }
        assertEquals(at60, at120, 0.001f)
    }

    @Test fun `a stalled frame does not jump and a tiny tail settles`() {
        assertTrue(followScrollStep(100f, 500_000_000L) < 60f)
        assertEquals(1f, followScrollStep(1f, 16_666_667L), 0f)
    }
}
