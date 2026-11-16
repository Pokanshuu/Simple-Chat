package com.simplechat.app.ui.markdown

import org.junit.Assert.*
import org.junit.Test

class LatexCompatibilityTest {
    @Test fun `scalebox keeps nested colored content and Chinese text`() {
        val source = """\scalebox{0.8}[1]{\fcolorbox{purple}{pink}{\text{示例场景：状态}}}"""
        val expected = """{\fcolorbox{purple}{pink}{\text{示例场景：状态}}}"""
        assertEquals(expected, normalizeLatexBoxes(source))
        val parsed = prepareLatexPresentation(source)
        assertEquals(expected, parsed.nativeTex)
        assertTrue(parsed.simplified)
    }
    @Test fun `nested sizing wrappers preserve all content`() {
        val source = """\resizebox{\textwidth}{!}{\scalebox{.8}{\parbox[c]{12cm}{\text{甲}\textbf{乙}}}}"""
        assertEquals("""{{{\text{甲}\textbf{乙}}}}""", normalizeLatexBoxes(source))
        assertEquals("""{x} + {y} + {z}""",
            normalizeLatexBoxes("""\rotatebox[origin=c]{90}{x} + \raisebox{1pt}[2pt][3pt]{y} + \makebox[2cm][c]{z}"""))
    }
    @Test fun `escaped delimiters remain intact and broken wrappers use the original source`() {
        assertEquals("""{\text{a\}b}}""", normalizeLatexBoxes("""\scalebox{1}{\text{a\}b}}"""))
        assertNull(normalizeLatexBoxes("""\scalebox{1}{unfinished"""))
        assertNull(prepareLatexPresentation("""\scalebox{1}{unfinished""").nativeTex)
        assertEquals("""\\scalebox{1}{literal}""", normalizeLatexBoxes("""\\scalebox{1}{literal}"""))
    }
    @Test fun `unsupported commands fall back instead of rendering an error node`() {
        assertNull(prepareLatexPresentation("""\definitelyunsupported{不能消失}""").nativeTex)
        assertNotNull(prepareLatexPresentation("""\frac{1}{2} + \sqrt{x^2}""").nativeTex)
        assertFalse(prepareLatexPresentation("""\frac{1}{2}""").simplified)
    }
    @Test fun `pathological wrapper nesting and giant input are bounded`() {
        assertNull(normalizeLatexBoxes("\\scalebox{1}{".repeat(40) + "x" + "}".repeat(40)))
        assertNull(prepareLatexPresentation("x".repeat(32_001)).nativeTex)
    }
}
