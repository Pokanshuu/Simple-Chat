package com.simplechat.app.ui.chat

import com.simplechat.app.net.ReplyBlock

/**
 * 「保存」编辑后的 AI 回复时，把**内容块时间线**里的正文换成新文本。
 *
 * ### 为什么不能只改 `UiMessage.content`
 *
 * 两条通道读正文的地方根本不是同一个字段：
 *
 * | 通道 | 界面渲染 | 发请求 |
 * |---|---|---|
 * | OpenAI 兼容 | `message.content` | `MessageContent.text(content)` |
 * | Anthropic 兼容 | `timeline` 里的 `Text` 块 | `assistantContent(timeline)` |
 *
 * 只改 `content` 的话，扩展通道**界面还是旧文本、发出去的也还是旧文本** ——
 * 用户点了保存却什么都没变（更糟的是"看着变了一点、发出去是旧的"这种半生效）。
 * 所以保存必须连时间线一起改写，这一步和改 `content` 是同一件事的两半。
 *
 * ### 规则
 *
 * - 空时间线（OpenAI 通道写的 / 升级前的旧消息）原样返回：那种消息的正文
 *   本来就只存在于 `content` 里；
 * - 有正文块 → 全部合并成一块，落在**第一块正文的位置**；
 *   正文前后的思考 / 搜索 / 读链接块**保持原序**，它们描述的是"这段答案怎么来的"，
 *   不该因为改了个错别字就被抹掉；
 * - 一块正文都没有（纯工具回复）→ 新文本补在末尾。
 *
 * 纯函数，有单测（`MessageEditingTest`）。
 */
internal fun rewriteTimelineText(timeline: List<ReplyBlock>, text: String): List<ReplyBlock> {
    if (timeline.isEmpty()) return timeline
    val out = ArrayList<ReplyBlock>(timeline.size + 1)
    var inserted = false
    for (block in timeline) {
        if (block is ReplyBlock.Text) {
            // 第一块正文的位置放新文本，其余正文块丢掉（它们已被合并成这一段）
            if (!inserted) {
                out += ReplyBlock.Text(text)
                inserted = true
            }
            continue
        }
        out += block
    }
    if (!inserted) out += ReplyBlock.Text(text)
    return out
}
