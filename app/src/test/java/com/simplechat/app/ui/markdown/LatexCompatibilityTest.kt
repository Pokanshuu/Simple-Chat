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
        assertFalse(prepareLatexPresentation("""\begin{tikzpicture}x\end{tikzpicture}""").canRender)
        assertNotNull(prepareLatexPresentation("""\frac{1}{2} + \sqrt{x^2}""").nativeTex)
        assertFalse(prepareLatexPresentation("""\frac{1}{2}""").simplified)
    }
    @Test fun `pathological wrapper nesting and giant input are bounded`() {
        assertNull(normalizeLatexBoxes("\\scalebox{1}{".repeat(40) + "x" + "}".repeat(40)))
        assertNull(prepareLatexPresentation("x".repeat(32_001)).nativeTex)
    }
    @Test fun `oval outline and small Chinese label are parts of the same formula`() {
        val inline = inlineMathDocument("""\(\ovalbox{项链还戴着吗}\quad\text{\tiny已读}\)""")
        val presentation = prepareLatexPresentation(inline.formulas.values.single().tex)
        assertEquals(listOf(LatexPart("""\text{项链还戴着吗}""", OvalOutline.THIN), LatexPart("""\quad{\tiny\text{已读}}""")),
            presentation.ovalParts)
        assertTrue(presentation.canRender)
        assertFalse(presentation.simplified)
    }

    @Test fun `formula parts preserve nested groups and fail together for unsupported input`() {
        val source = """x+\ovalbox {\frac{1}{2}}\,\Ovalbox{\text{中文}}"""
        assertEquals(listOf(LatexPart("x+"), LatexPart("""\frac{1}{2}""", OvalOutline.THIN),
            LatexPart("""\,"""), LatexPart("""\text{中文}""", OvalOutline.THICK)), prepareLatexPresentation(source).ovalParts)
        assertFalse(prepareLatexPresentation("""\ovalbox{未闭合""").canRender)
        assertFalse(prepareLatexPresentation("""\ovalbox{中文}\notacommand{内容}""").canRender)
    }

    @Test fun `text mode size declarations stay scoped and preserve surrounding math`() {
        val source = """\text{\scriptsize 中文} + \mbox{\Large 大字} + x^2"""
        assertEquals("""{\scriptsize\text{中文}} + {\Large\text{大字}} + x^2""",
            prepareLatexPresentation(source).nativeTex)
    }

    @Test fun visibleBoxExamplesKeepContentAndDimensions() {
        val source = "\$\\fbox{\$x^2+y^2=r^2\$}\$"
        val nested = inlineMathDocument(source).formulas.values.single()
        assertEquals("x^2+y^2=r^2", prepareLatexPresentation(nested.tex).ovalParts.single().tex)
        val square = prepareLatexPresentation("""\framebox[2cm][c]{\rule{0pt}{2cm}\centering 固定方框}""").ovalParts.single()
        assertEquals(LatexLength(2f, "cm"), square.frame?.width)
        assertEquals(square.frame?.width, square.frame?.height)
        assertEquals("""\text{固定方框}""", square.tex)
        val capsule = prepareLatexPresentation("""\bbox[4px,lightblue,border:1px solid blue,border-radius:12px]{x^2}""")
        assertEquals(LatexLength(12f, "px"), capsule.ovalParts.single().frame?.radius)
        assertFalse(prepareLatexPresentation("""\bbox[display:none]{x}""").canRender)
    }
}
