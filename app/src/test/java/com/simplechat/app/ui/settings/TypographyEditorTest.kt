package com.simplechat.app.ui.settings

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

class TypographyEditorTest {
    @Test fun `hsl preserves primary and neutral colors`() {
        assertEquals(Color.Red, hslToColor(0f, 1f, 0.5f))
        assertEquals(Color.Green, hslToColor(120f, 1f, 0.5f))
        assertEquals(Color.Blue, hslToColor(240f, 1f, 0.5f))
        assertEquals(Color(0xFF808080), hslToColor(210f, 0f, 0.5f))
    }

    @Test fun `hsl input is clamped to legal ranges`() {
        assertEquals(hslToColor(0f, 1f, 0.5f), hslToColor(-30f, 2f, 0.5f))
        assertEquals(Color.Black, hslToColor(1000f, 1f, -1f))
        assertEquals(Color.White, hslToColor(1000f, 1f, 2f))
    }
}
