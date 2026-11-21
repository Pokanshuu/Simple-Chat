package com.simplechat.app.ui.chat

import com.simplechat.app.net.Citation
import com.simplechat.app.net.ReplyBlock
import com.simplechat.app.net.StreamEvent

/**
 * 把块级流式事件**归并**成一条可落库、可渲染的回复。
 *
 * 官方 demo 的 `applyEvent` 是同一件事的 TS 版；这里额外做两件本项目需要的事：
 * 1. 思考块的起止时刻在本地记（服务端不给），用来算「已思考（用时 N 秒）」；
 * 2. 提供 [settle]，把"结束原因 = 安全拒绝"的情况按协议收尾。
 *
 * ### 为什么不用 data class + 纯函数
 * 事件里的 `index` 是**本次响应**的块序号，与落库后的下标无关；
 * 只有"边收边记"才能把增量对回正确的块。为此保留一个 [indexOf] 映射，
 * 它本身就是可变状态 —— 与其假装成纯函数（每次递归复制一份 Map），不如直说是累加器。
 *
 * 单测覆盖：块增量的归并、引用去重、思考计时、安全拒绝收尾（`ReplyAccumulatorTest`）。
 */
class ReplyAccumulator {

    private val indexOf = mutableMapOf<Int, Int>()

    private val entries = mutableListOf<PendingBlock>()
    private var cachedBlocks: List<ReplyBlock>? = emptyList()

    /** Only publication materializes strings and lists. Previously published blocks never change. */
    val blocks: List<ReplyBlock>
        get() = cachedBlocks ?: entries.map { it.snapshot() }.also { cachedBlocks = it }

    /** 服务端生成的会话标题（仅首轮请求了 `title.enabled` 时才有）。 */
    var title: String? = null
        private set

    /** 结束原因。续写能力与安全拒绝的收尾都看它。 */
    var stopReason: String? = null
        private set

    /** 正文（所有 text 块按序拼接）。 */
    val answer: String
        get() = combine(ReplyBlock.Text::text)

    /** 思维链（所有 thinking 块按序拼接）。 */
    val thinking: String
        get() = combine(ReplyBlock.Thinking::thinking)

    private inline fun <reified T : ReplyBlock> combine(textOf: (T) -> String): String {
        var first = ""
        var rest: StringBuilder? = null
        for (block in blocks) {
            if (block !is T) continue
            val text = textOf(block)
            if (text.isEmpty()) continue
            if (first.isEmpty()) first = text
            else (rest ?: StringBuilder(first).also { rest = it }).append(text)
        }
        return rest?.toString() ?: first
    }

    /**
     * 续写：以已有回复开局，新事件往**后面**接。
     *
     * 只种内容、不种 `indexOf` —— 续写是**新的一次响应**，它的块序号从 0 重新开始，
     * 与旧的块下标毫无关系。种进去才不会有"第 0 块被新内容覆盖"这种事。
     */
    fun seed(existing: List<ReplyBlock>) {
        entries.clear()
        existing.forEach { entries += PendingBlock(it) }
        cachedBlocks = existing.toList()
        indexOf.clear()
        stopReason = null
    }

    fun consume(event: StreamEvent, now: Long) {
        when (event) {
            is StreamEvent.BlockStart -> {
                indexOf[event.index] = entries.size
                entries += PendingBlock(event.block.withStart(now), fromCurrentResponse = true)
                cachedBlocks = null
            }

            is StreamEvent.TextDelta -> update(event.index) { appendText(event.text, thinking = false) }
            is StreamEvent.ThinkingDelta -> update(event.index) { appendText(event.text, thinking = true) }
            is StreamEvent.SignatureDelta -> update(event.index) { appendSignature(event.signature) }
            is StreamEvent.CitationDelta -> update(event.index) { addCitation(event.citation) }

            is StreamEvent.BlockStop -> {
                update(event.index) { stop(now) }
                indexOf.remove(event.index)
            }

            is StreamEvent.Title -> title = event.text

            is StreamEvent.Stopped -> stopReason = event.reason

            // OpenAI 通道的增量事件不走这个累加器；流量到了这里说明通道配错，忽略即可
            is StreamEvent.Content, is StreamEvent.Reasoning, is StreamEvent.Usage,
            StreamEvent.Done, is StreamEvent.Error, is StreamEvent.ToolCallDelta -> Unit
        }
    }

    /**
     * 收尾：把所有未结束的思考块按 [now] 收口。
     *
     * ### 安全拒绝（`refusal`）**不丢任何已经收到的内容**
     *
     * 早先这里按 Anthropic 文档的字面意思"这一轮生成的东西必须丢弃"，
     * 在 `stop_reason = refusal` 时删掉正文与思考。那是**想错了**：
     *
     * - 我们是**第三方客户端**，不是内容裁量的执行者。服务端说不写了，
     *   它已经停了 —— 客户端再去把屏幕上已经出现的字擦掉，等于替服务端做销毁；
     * - 这在本项目里是**数据丢失**：和"用户点了停止生成，正文却消失"同性质，
     *   而且用户完全无从预料 —— 他看到字一个个写出来，然后整段不见了。
     *
     * 保留内容不影响任何判定：`stopReason` 仍然记着 `refusal`
     * （`ContinuePolicy` 靠它禁掉"继续生成"），只是不再删字。
     */
    fun settle(now: Long) {
        entries.forEach { if (it.stop(now)) cachedBlocks = null }
        indexOf.clear()
    }

    /** 思考总用时（毫秒）。没有思考块时为 null。 */
    fun thinkingMillis(now: Long): Long? {
        var total: Long? = null
        for (entry in entries) {
            val duration = entry.thinkingMillis(now) ?: continue
            total = (total ?: 0L) + duration
        }
        return total
    }

    private inline fun update(index: Int, change: PendingBlock.() -> Boolean) {
        val at = indexOf[index] ?: return
        if (entries[at].change()) cachedBlocks = null
    }

    private class PendingBlock(private var block: ReplyBlock, private val fromCurrentResponse: Boolean = false) {
        private var text: StringBuilder? = null
        private var signature: StringBuilder? = null
        private var citations: LinkedHashMap<String, Citation>? = null
        private var cached: ReplyBlock? = block

        fun appendText(delta: String, thinking: Boolean): Boolean {
            val initial = when (val current = block) {
                is ReplyBlock.Thinking -> if (thinking) current.thinking else return false
                is ReplyBlock.Text -> if (!thinking) current.text else return false
                else -> return false
            }
            if (delta.isEmpty()) return false
            (text ?: StringBuilder(initial).also { text = it }).append(delta)
            cached = null
            return true
        }

        fun appendSignature(delta: String): Boolean {
            val current = block as? ReplyBlock.Thinking ?: return false
            (signature ?: StringBuilder(current.signature.orEmpty()).also { signature = it }).append(delta)
            cached = null
            return true
        }

        fun addCitation(citation: Citation): Boolean {
            val current = block as? ReplyBlock.Text ?: return false
            val byUrl = citations ?: linkedMapOf<String, Citation>().also { map ->
                current.citations.forEach { map.putIfAbsent(it.url, it) }
                citations = map
            }
            if (byUrl.putIfAbsent(citation.url, citation) != null) return false
            cached = null
            return true
        }

        fun stop(now: Long): Boolean {
            val current = block as? ReplyBlock.Thinking ?: return false
            val start = current.startedAt ?: return false
            if (!fromCurrentResponse || current.endedAt != null) return false
            block = current.copy(endedAt = now.coerceAtLeast(start))
            cached = null
            return true
        }

        fun thinkingMillis(now: Long): Long? {
            val current = block as? ReplyBlock.Thinking ?: return null
            val start = current.startedAt ?: return null
            val end = current.endedAt ?: now.takeIf { fromCurrentResponse } ?: return null
            return (end - start).coerceAtLeast(0L)
        }

        fun snapshot(): ReplyBlock = cached ?: when (val current = block) {
            is ReplyBlock.Text -> current.copy(text = text?.toString() ?: current.text,
                citations = citations?.values?.toList() ?: current.citations)
            is ReplyBlock.Thinking -> current.copy(thinking = text?.toString() ?: current.thinking,
                signature = signature?.toString() ?: current.signature)
            else -> current
        }.also { cached = it }
    }

    /** 思考块从开始计时（服务端不给时刻，只能本地记）。 */
    private fun ReplyBlock.withStart(now: Long): ReplyBlock =
        if (this is ReplyBlock.Thinking && startedAt == null) copy(startedAt = now) else this

    companion object {
        /** 安全拒绝的结束原因字面值。 */
        const val Refusal = "refusal"

        /** 输出被上限截断 —— 可以续写。 */
        const val MaxTokens = "max_tokens"
    }
}
