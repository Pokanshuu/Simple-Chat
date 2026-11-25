package com.simplechat.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class AppearanceDefaultsTest {
    @Test fun interfaceStartsFullyOpaque() {
        // 透明是通过「底色不透明度」主动开启的效果；没有保存过该值的安装应保持实色。
        assertEquals(1f, DefaultPanelOpacity, 0f)
        assertEquals(DefaultPanelOpacity, StoredSettings().wallpaperPanelAlpha, 0f)
    }
}
