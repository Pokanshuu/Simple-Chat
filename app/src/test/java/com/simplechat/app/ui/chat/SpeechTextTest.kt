package com.simplechat.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 朗读前的正文清洗。
 *
 * 判据只有一条：**念出来像人话**。所以 code fence 必须整块消失
 * （逐字符念 `fun main()` 毫无意义），链接要留文字、表格行要整行跳过。
 */
class SpeechTextTest {

    @Test
    fun `围栏代码块整块被剥掉`() {
        val text = prepareForSpeech(
            """
            先看这段：

            ```kotlin
            fun main() {
                println("hello")
            }
            ```

            就是这样。
            """.trimIndent(),
        )
        assertFalse(text.contains("fun main"))
        assertFalse(text.contains("```"))
        assertTrue(text.contains("就是这样"))
    }

    @Test
    fun `行内代码去掉反引号但保留内容`() {
        assertEquals("用 remember 就够了", prepareForSpeech("用 `remember` 就够了"))
    }

    @Test
    fun `链接只保留文字`() {
        assertEquals("见 官方文档 第二节", prepareForSpeech("见 [官方文档](https://example.com) 第二节"))
    }

    @Test
    fun `图片整个丢弃`() {
        assertEquals("看图", prepareForSpeech("看图 ![截图](https://example.com/a.png)"))
    }

    @Test
    fun `标题与列表的记号被剥掉`() {
        assertEquals(
            "第一章\n第一条\n第二条",
            prepareForSpeech("## 第一章\n- 第一条\n- 第二条"),
        )
    }

    @Test
    fun `有序列表与引用也剥掉`() {
        assertEquals(
            "引用的话\n第一点",
            prepareForSpeech("> 引用的话\n1. 第一点"),
        )
    }

    @Test
    fun `表格行整行跳过`() {
        val text = prepareForSpeech(
            """
            对照如下：

            | 项 | 值 |
            |---|---|
            | 甲 | 1 |

            完毕。
            """.trimIndent(),
        )
        assertFalse(text.contains("|"))
        assertTrue(text.contains("对照如下"))
        assertTrue(text.contains("完毕"))
    }

    @Test
    fun `强调记号被剥掉`() {
        assertEquals("很重要的一点", prepareForSpeech("**很重要**的一点"))
        assertEquals("斜体文字", prepareForSpeech("*斜体文字*"))
        assertEquals("划掉", prepareForSpeech("~~划掉~~"))
    }

    @Test
    fun `分割线被跳过`() {
        assertEquals("上\n\n下", prepareForSpeech("上\n\n---\n\n下"))
    }

    @Test
    fun `纯文本原样通过`() {
        assertEquals("雨是从傍晚开始下的。", prepareForSpeech("雨是从傍晚开始下的。"))
    }

    @Test
    fun `空正文返回空串`() {
        assertEquals("", prepareForSpeech(""))
        assertEquals("", prepareForSpeech("   \n\n  "))
    }

    @Test
    fun `多个空行压成一个段间空行`() {
        assertEquals("甲\n\n乙", prepareForSpeech("甲\n\n\n\n\n乙"))
    }
}
