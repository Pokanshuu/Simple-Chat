package com.simplechat.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConversationWallpaperTest {
    @Test fun preservesTheImageAndIndependentAdjustments() {
        val wallpaper = ConversationWallpaper("wallpaper_example.jpg", WallpaperAppearance(
            opacity = 0.73f, blur = 0f, brightness = 1.13f, contrast = 0.91f,
            saturation = 0.8f, dim = 0.25f, flipH = true,
        ))
        assertEquals(wallpaper, ConversationWallpaper.decode(wallpaper.encode()))
    }

    @Test fun absentOrMalformedSettingsFallBackToGlobalWallpaper() {
        assertNull(ConversationWallpaper.decode(null))
        assertNull(ConversationWallpaper.decode("{not a wallpaper}"))
    }
}
