package com.simplechat.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class TypographySettingsTest {
    @Test fun `font size override follows global while retaining disabled value`() {
        val saved = CategoryTypographySettings(fontScale = 1.2f)
        assertEquals(0.9f, saved.effectiveFontScale(0.9f))
        val enabled = saved.copy(overrideFontSizeEnabled = true)
        assertEquals(1.2f, enabled.effectiveFontScale(0.9f))
        assertEquals(1.2f, enabled.copy(overrideFontSizeEnabled = false).fontScale!!)
    }

    @Test fun `one size override disables global size and other categories still inherit it`() {
        val settings = TypographySettings(user = CategoryTypographySettings(fontScale = 1.1f, overrideFontSizeEnabled = true))
        assertEquals(true, settings.hasFontSizeOverride)
        assertEquals(1.1f, settings[FontSlot.USER].effectiveFontScale(0.85f))
        assertEquals(0.85f, settings[FontSlot.ASSISTANT].effectiveFontScale(0.85f))
        assertEquals(0.85f, settings[FontSlot.UI].effectiveFontScale(0.85f))
        assertEquals(false, settings.copy(user = settings.user.copy(overrideFontSizeEnabled = false)).hasFontSizeOverride)
    }

    @Test fun `custom colors default off and are retained when disabled`() {
        val default = CategoryTypographySettings()
        assertEquals(false, default.customColorEnabled)
        assertEquals(TextPrimaryArgb, default.effectiveTextColor(FontSlot.ASSISTANT, false, TextPrimaryArgb))
        val custom = default.copy(customColorEnabled = true, lightTextColorArgb = 0xFF118844.toInt())
        assertEquals(0xFF118844.toInt(), custom.effectiveTextColor(FontSlot.ASSISTANT, false, TextPrimaryArgb))
        val disabled = custom.copy(customColorEnabled = false)
        assertEquals(0xFF118844.toInt(), disabled.lightTextColorArgb!!)
        assertEquals(TextPrimaryArgb, disabled.effectiveTextColor(FontSlot.ASSISTANT, false, TextPrimaryArgb))
    }

    @Test fun `theme color can be toggled after custom color is turned off`() {
        val custom = CategoryTypographySettings(customColorEnabled = true, useThemeColor = true, lightTextColorArgb = 0xFF334455.toInt())
        assertEquals(0xFF334455.toInt(), custom.effectiveTextColor(FontSlot.USER, false, TextPrimaryArgb, 0xFF4D6BFE.toInt()))
        val restored = custom.copy(customColorEnabled = false)
        assertEquals(0xFF4D6BFE.toInt(), restored.effectiveTextColor(FontSlot.USER, false, TextPrimaryArgb, 0xFF4D6BFE.toInt()))
        val default = restored.copy(useThemeColor = false)
        assertEquals(TextPrimaryArgb, default.effectiveTextColor(FontSlot.USER, false, TextPrimaryArgb, 0xFF4D6BFE.toInt()))
        assertEquals(0xFF334455.toInt(), default.lightTextColorArgb!!)
    }

    @Test fun `light and dark custom colors remain independent`() {
        val colors = CategoryTypographySettings(customColorEnabled = true, lightTextColorArgb = 0xFF111111.toInt(), darkTextColorArgb = 0xFFEEEEEE.toInt())
        assertEquals(0xFF111111.toInt(), colors.effectiveTextColor(FontSlot.UI, false, TextPrimaryArgb))
        assertEquals(0xFFEEEEEE.toInt(), colors.effectiveTextColor(FontSlot.UI, true, TextPrimaryArgb))
    }

    private companion object {
        const val TextPrimaryArgb: Int = 0xFF1A1A1A.toInt()
    }
}
