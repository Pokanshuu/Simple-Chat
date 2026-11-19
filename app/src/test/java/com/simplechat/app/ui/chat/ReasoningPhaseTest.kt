package com.simplechat.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 思考栏的折叠判据与秒数取整。
 *
 * ### 这个测试在钉什么
 *
 * 原来思考栏的「流式中」用的是 `message.streaming` —— 那是**整条回复**
 * 还在生成（思维链 + 正文），而思维链结束得早得多。后果是：正文都写了一大半，
 * 思考栏还挂着「正在思考」的尾巴在滚。
 *
 * 正确的判据是 [UiMessage.reasoningStreaming]（= 在流 **且** 思维链没写完）。
 * 这条链路上没有别的测试能覆盖它 —— 它只影响"什么时候折叠"这个时序，
 * 从截图上也看不出对错，所以在这里钉死。
 */
class ReasoningPhaseTest {

    private fun message(
        status: MessageStatus,
        reasoningDone: Boolean,
        reasoning: String? = "思考内容",
        content: String = "",
    ) = UiMessage(
        id = "m",
        role = MessageRole.ASSISTANT,
        content = content,
        reasoning = reasoning,
        status = status,
        reasoningDone = reasoningDone,
    )

    @Test
    fun `thinking is streaming until the first content token`() {
        val msg = message(MessageStatus.STREAMING, reasoningDone = false)

        assertTrue("思维链还在写，思考栏应当处于尾巴模式", msg.reasoningStreaming)
    }

    @Test
    fun `thinking collapses as soon as content starts, while the reply is still streaming`() {
        // 关键用例：整条消息**还在流**，但只要正文开始了，思考栏就该收起来
        val msg = message(MessageStatus.STREAMING, reasoningDone = true, content = "正文第一句")

        assertTrue("前提：整条回复确实还在流", msg.streaming)
        assertFalse("思维链写完了，思考栏就该折叠", msg.reasoningStreaming)
    }

    @Test
    fun `a finished message never shows the thinking tail`() {
        val msg = message(MessageStatus.DONE, reasoningDone = false)

        assertFalse(msg.reasoningStreaming)
    }

    @Test
    fun `an interrupted message never shows the thinking tail`() {
        // 用户在思考阶段就按了停止：正文一条没有，但也不该继续滚尾巴
        val msg = message(MessageStatus.STOPPED, reasoningDone = false, content = "")

        assertFalse(msg.reasoningStreaming)
    }

    @Test
    fun `absent and blank reasoning never show a thinking panel or tail`() {
        for (reasoning in listOf(null, "", " \n\t")) {
            val msg = message(MessageStatus.STREAMING, reasoningDone = false, reasoning = reasoning)
                .copy(reasoningSeconds = 5)

            assertFalse(msg.hasReasoning)
            assertFalse(msg.reasoningStreaming)
        }
    }

    @Test
    fun `a late summary shows a settled thinking panel even without timing`() {
        val msg = message(MessageStatus.STREAMING, reasoningDone = true, reasoning = "思考摘要", content = "正文")

        assertTrue(msg.hasReasoning)
        assertFalse(msg.reasoningStreaming)
    }

    @Test
    fun `a newline after a long thinking paragraph keeps the preceding lines visible`() {
        val paragraph = "想".repeat(1400)
        val tail = visibleTailOf("$paragraph\n")
        assertTrue(tail.endsWith("想想\n"))
        assertTrue(tail.length < paragraph.length)
    }

    @Test
    fun `thinking window starts on a complete emoji`() {
        val suffix = "想".repeat(1199)
        assertEquals("🧠$suffix", visibleTailOf("前🧠$suffix"))
    }

    // ── 秒数取整 ────────────────────────────────────────────

    @Test
    fun `sub-second thinking rounds up to 1 instead of showing 0`() {
        // 直接截断会显示「已深度思考 · 0 秒」，看着像计时坏了
        assertEquals(1, thinkingSeconds(0))
        assertEquals(1, thinkingSeconds(120))
        assertEquals(1, thinkingSeconds(499))
    }

    @Test
    fun `thinking seconds round to nearest`() {
        assertEquals(1, thinkingSeconds(500))
        assertEquals(1, thinkingSeconds(1_400))
        assertEquals(2, thinkingSeconds(1_500))
        assertEquals(8, thinkingSeconds(7_600))
    }

    @Test
    fun `long thinking is reported as-is`() {
        assertEquals(42, thinkingSeconds(41_800))
    }
}
