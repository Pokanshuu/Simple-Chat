package com.simplechat.app.ui.markdown

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 自绘列表的纯函数部分（`MarkdownListLayout`）。
 *
 * 真机反馈："数字还是没对齐" —— 旧实现依赖库的 `Box` + `textAlign = End`，
 * 但 `Box` 不传递最小约束，右对齐根本没生效。自绘之后符号列宽由"最宽序号"
 * 测量得出，这里把不依赖 Compose 运行时的两段逻辑钉住：
 *
 * 1. **最宽序号怎么取**：有序列表取最后一项（序号递增、位数最多）；
 * 2. **列宽怎么定**：不能小于该层的最小缩进，也不能小于"序号宽 + 间隙"。
 */
class MarkdownListLayoutTest {

    @Test
    fun `有序列表取最后一项作为最宽序号`() {
        assertEquals("12.", orderedMarkerSample(startNumber = 1, itemCount = 12))
        assertEquals("4.", orderedMarkerSample(startNumber = 3, itemCount = 2))
        assertEquals("3.", orderedMarkerSample(startNumber = 3, itemCount = 1))
        assertEquals("1.", orderedMarkerSample(startNumber = 1, itemCount = 1))
    }

    @Test
    fun `有序列表支持 CommonMark 起始序号`() {
        // `0.` 开头是合法的（start-number），`9.` 开头同理
        assertEquals("0.", orderedMarkerSample(startNumber = 0, itemCount = 1))
        assertEquals("10.", orderedMarkerSample(startNumber = 9, itemCount = 2))
    }

    @Test
    fun `空列表不产生负序号`() {
        // 防御：itemCount 为 0 时（理论上不会走到测量）也不该出现 "0." 之外的怪值
        assertEquals("1.", orderedMarkerSample(startNumber = 1, itemCount = 0))
    }

    @Test
    fun `无序符号按层轮换`() {
        assertEquals("•", bulletMarkerGlyph(0))
        assertEquals("◦", bulletMarkerGlyph(1))
        assertEquals("▪", bulletMarkerGlyph(2))
        assertEquals("•", bulletMarkerGlyph(3))
    }

    @Test
    fun `顶层与嵌套的最小缩进不同`() {
        assertEquals(TopListIndent, listMinIndent(0))
        assertEquals(NestedListIndent, listMinIndent(1))
        assertEquals(NestedListIndent, listMinIndent(2))
    }

    @Test
    fun `一位数序号列宽不小于最小缩进`() {
        // 13dp 的 "1." + 6dp 间隙 < 24dp：取最小缩进，正文对齐同层其它列表
        assertEquals(TopListIndent, markerColumnWidthFor(measuredMarker = 13.dp, minIndent = TopListIndent))
    }

    @Test
    fun `两位数序号把符号列撑宽而不是溢出`() {
        // 22dp 的 "10." + 6dp 间隙 + 0.5dp 余量 > 24dp：列宽跟着序号变宽
        assertEquals(
            22.dp + ListMarkerGap + MarkerColumnEpsilon,
            markerColumnWidthFor(measuredMarker = 22.dp, minIndent = TopListIndent),
        )
    }

    @Test
    fun `嵌套层同样先满足最小缩进`() {
        assertEquals(
            NestedListIndent,
            markerColumnWidthFor(measuredMarker = 5.dp, minIndent = NestedListIndent),
        )
    }

    @Test
    fun `汉字段落才做基线校正`() {
        // 汉字 / CJK 标点 / 全角字符：首行行盒按 CJK 字体取 ascent
        assertEquals(true, containsCjk("神圣之物仅仅是个幻觉"))
        assertEquals(true, containsCjk("这是价值论层面的颠倒："))
        assertEquals(true, containsCjk("「引号」"))
        // 纯拉丁/数字/空格：首行行盒与符号同字体，不需要校正
        assertEquals(false, containsCjk("Hello world"))
        assertEquals(false, containsCjk("1.0.0"))
        assertEquals(false, containsCjk("`code` --flag"))
        assertEquals(false, containsCjk(""))
    }
}
