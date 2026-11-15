package com.simplechat.app.ui.chat

import com.simplechat.app.net.ReplyBlock
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 续写的准入规则。
 *
 * 放行错了 → 服务端拒绝，或者接出一段风马牛不相及的内容；
 * 拦错了 → 用户明明看到回复被截断，却没有按钮可点。两头都是可见的坏体验。
 */
class ContinuePolicyTest {

    private fun reply(
        status: MessageStatus = MessageStatus.STOPPED,
        stopReason: String? = null,
        timeline: List<ReplyBlock> = listOf(ReplyBlock.Text("写到一半")),
        role: MessageRole = MessageRole.ASSISTANT,
    ) = UiMessage(
        id = "a1",
        role = role,
        content = "写到一半",
        model = "deepseek-flash",
        status = status,
        timeline = timeline,
        stopReason = stopReason,
    )

    @Test
    fun `被用户停止的最后一条可以续`() {
        assertTrue(canContinueReply(reply(status = MessageStatus.STOPPED), isLastOnPath = true))
    }

    @Test
    fun `被输出上限截断的最后一条可以续`() {
        assertTrue(
            canContinueReply(
                reply(status = MessageStatus.DONE, stopReason = ReplyMaxTokens),
                isLastOnPath = true,
            ),
        )
    }

    @Test
    fun `正常结束的回复不可续（没什么好接的）`() {
        assertFalse(
            canContinueReply(
                reply(status = MessageStatus.DONE, stopReason = "end_turn"),
                isLastOnPath = true,
            ),
        )
    }

    @Test
    fun `不在最后一条时不可续`() {
        assertFalse(canContinueReply(reply(), isLastOnPath = false))
    }

    @Test
    fun `没有时间线的回复不可续（OpenAI 通道或老数据）`() {
        assertFalse(
            canContinueReply(reply(timeline = emptyList()), isLastOnPath = true),
        )
    }

    @Test
    fun `服务端的结束原因不参与续写判断`() {
        // 拒绝、审核之类都不该影响"能不能接着写" —— 客户端不替服务端做内容裁量
        assertTrue(
            canContinueReply(
                reply(status = MessageStatus.STOPPED, stopReason = "refusal"),
                isLastOnPath = true,
            ),
        )
    }

    @Test
    fun `流式中的回复不可续`() {
        assertFalse(
            canContinueReply(reply(status = MessageStatus.STREAMING), isLastOnPath = true),
        )
    }

    @Test
    fun `出错的回复不可续`() {
        assertFalse(
            canContinueReply(reply(status = MessageStatus.ERROR), isLastOnPath = true),
        )
    }

    @Test
    fun `用过服务端工具的回复不可续`() {
        val withSearch = listOf(
            ReplyBlock.ToolUse("t", "deepseek_web_search", "search", "search", listOf("q")),
            ReplyBlock.SearchResult("t", "search"),
            ReplyBlock.Text("正文"),
        )
        assertFalse(
            canContinueReply(reply(timeline = withSearch), isLastOnPath = true),
        )
    }

    @Test
    fun `读过链接的回复不可续`() {
        val withFetch = listOf(
            ReplyBlock.FetchResult("r", ReadLinkKind, "https://e.com", "页面"),
            ReplyBlock.Text("正文"),
        )
        assertFalse(canContinueReply(reply(timeline = withFetch), isLastOnPath = true))
    }

    @Test
    fun `用户消息永远不可续`() {
        assertFalse(
            canContinueReply(reply(role = MessageRole.USER), isLastOnPath = true),
        )
    }
}
