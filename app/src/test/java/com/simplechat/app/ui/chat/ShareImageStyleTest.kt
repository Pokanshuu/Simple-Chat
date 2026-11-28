package com.simplechat.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/** §49.6.5 可读性保护层：导出时壁纸透明度必须被钳到下限。 */
class ShareImageStyleTest {

    @Test
    fun `full transparency clamps to light floor`() {
        assertEquals(0.55f, shareWallpaperVeil(opacity = 1f, dark = false), 0.0001f)
    }

    @Test
    fun `full transparency clamps to dark floor`() {
        assertEquals(0.65f, shareWallpaperVeil(opacity = 1f, dark = true), 0.0001f)
    }

    @Test
    fun `opacity below the floor survives`() {
        assertEquals(0.8f, shareWallpaperVeil(opacity = 0.2f, dark = false), 0.0001f)
        assertEquals(0.9f, shareWallpaperVeil(opacity = 0.1f, dark = true), 0.0001f)
    }

    @Test
    fun `opacity above the floor is raised to the floor`() {
        // 1 - 0.6 = 0.4 < 0.55
        assertEquals(0.55f, shareWallpaperVeil(opacity = 0.6f, dark = false), 0.0001f)
        // 1 - 0.5 = 0.5 < 0.65
        assertEquals(0.65f, shareWallpaperVeil(opacity = 0.5f, dark = true), 0.0001f)
    }

    @Test
    fun `transparent wallpaper gives a fully opaque veil`() {
        assertEquals(1f, shareWallpaperVeil(opacity = 0f, dark = false), 0.0001f)
        assertEquals(1f, shareWallpaperVeil(opacity = 0f, dark = true), 0.0001f)
    }

    @Test
    fun `out of range opacity is coerced`() {
        assertEquals(1f, shareWallpaperVeil(opacity = -3f, dark = false), 0.0001f)
        assertEquals(0.55f, shareWallpaperVeil(opacity = 7f, dark = false), 0.0001f)
    }
}
