package com.simplechat.app.ui.markdown

import org.junit.Assert.assertEquals
import org.junit.Test

class SelectionCopyTextTest {
    @Test
    fun paragraphsAndListsKeepReadableSpacingAndDepth() {
        val source = "Intro\n\n- First\n  - Nested\n\n**End**"
        assertEquals("Intro\n\n\u00b7 First\n  \u00b7 Nested\n\nEnd", selectionCopyText(source))
    }

    @Test
    fun htmlTagsAreRemovedButContentsRemain() {
        assertEquals("Hello world", selectionCopyText("<p>Hello <b>world</b></p>"))
    }
}
