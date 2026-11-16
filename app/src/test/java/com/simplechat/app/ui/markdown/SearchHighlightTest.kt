package com.simplechat.app.ui.markdown

import org.junit.Assert.*
import org.junit.Test

class SearchHighlightTest {
    @Test fun `rendered match uses visible offsets and preserves CJK emphasis separators`() {
        assertEquals(4..5, renderedSearchRange("正常排版章鱼文字", "章鱼"))
        assertEquals(2..4, renderedSearchRange("前缀章\u200B鱼后文", "章鱼"))
        assertEquals(0..2, renderedSearchRange("ABC normal", "abc"))
        assertNull(renderedSearchRange("normal", "absent"))
        assertNull(renderedSearchRange("normal", ""))
    }
}
