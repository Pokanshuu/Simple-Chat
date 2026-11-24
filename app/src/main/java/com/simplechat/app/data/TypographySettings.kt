package com.simplechat.app.data

import kotlinx.serialization.Serializable

/** Independent typography overrides for one of the existing font slots. */
@Serializable
data class CategoryTypographySettings(
    /** Last custom value is retained while the override is disabled. */
    val fontScale: Float? = null,
    val overrideFontSizeEnabled: Boolean = false,
    val customColorEnabled: Boolean = false,
    val lightTextColorArgb: Int? = null,
    val darkTextColorArgb: Int? = null,
    /** Only meaningful for [FontSlot.USER]; retained when custom color is enabled. */
    val useThemeColor: Boolean = false,
) {
    fun effectiveFontScale(globalScale: Float): Float =
        if (overrideFontSizeEnabled) fontScale ?: globalScale else globalScale

    fun effectiveTextColor(slot: FontSlot, isDark: Boolean, defaultArgb: Int, themeArgb: Int? = null): Int = when {
        customColorEnabled -> (if (isDark) darkTextColorArgb else lightTextColorArgb) ?: defaultArgb
        slot == FontSlot.USER && useThemeColor && themeArgb != null -> themeArgb
        else -> defaultArgb
    }
}

/** Persisted text settings. Missing/invalid legacy data decodes to these defaults. */
@Serializable
data class TypographySettings(
    val user: CategoryTypographySettings = CategoryTypographySettings(),
    val assistant: CategoryTypographySettings = CategoryTypographySettings(),
    val ui: CategoryTypographySettings = CategoryTypographySettings(),
) {
    operator fun get(slot: FontSlot): CategoryTypographySettings = when (slot) {
        FontSlot.USER -> user
        FontSlot.ASSISTANT -> assistant
        FontSlot.UI -> ui
    }

    fun withSlot(slot: FontSlot, value: CategoryTypographySettings): TypographySettings = when (slot) {
        FontSlot.USER -> copy(user = value)
        FontSlot.ASSISTANT -> copy(assistant = value)
        FontSlot.UI -> copy(ui = value)
    }

    val hasFontSizeOverride: Boolean
        get() = user.overrideFontSizeEnabled || assistant.overrideFontSizeEnabled || ui.overrideFontSizeEnabled
}

/** Single editor draft, committed atomically with the existing custom-theme preferences. */
data class TypographyDraft(
    val fontScale: Float,
    val followSystemFont: Boolean,
    val uiFontId: String?,
    val userFontId: String?,
    val assistantFontId: String?,
    val typography: TypographySettings,
)
