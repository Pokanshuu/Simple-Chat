package com.simplechat.app.ui.chat

import com.simplechat.app.R
import com.simplechat.app.data.Res

/**
 * 滚动条胶囊的状态文案（docs/41 §51.5.9）。
 *
 * 做成 lambda 是为了让下面的纯函数**不碰 Android Resources**：默认实现走 [Res]
 * （随界面语言），单测直接把固定文案塞进来。
 */
internal class ScrollBarPreviewLabels(
    val thinking: () -> String = { Res.get(R.string.scroll_preview_thinking) },
    val streaming: () -> String = { Res.get(R.string.scroll_preview_streaming) },
    val failed: () -> String = { Res.get(R.string.scroll_preview_failed) },
    val stopped: () -> String = { Res.get(R.string.scroll_preview_stopped) },
    val imageGenerating: () -> String = { Res.get(R.string.scroll_preview_image_generating) },
    val deletedBranch: () -> String = { Res.get(R.string.scroll_preview_deleted_branch) },
    val empty: () -> String = { Res.get(R.string.msg_index_empty) },
)

/**
 * 胶囊文本：拖动时"跟手"显示目标消息的第一行前 [ScrollPreviewMaxCodePoints] 个码点。
 *
 * 优先级与消息索引一致：状态文案 > 正文首行 > 占位。
 * 纯函数，有单测（`ScrollBarPreviewTest`）。
 */
internal fun scrollBarPreview(
    message: UiMessage,
    labels: ScrollBarPreviewLabels = ScrollBarPreviewLabels(),
): String = when {
    message.deletedNavigation -> labels.deletedBranch()
    message.imageGeneration && message.streaming -> labels.imageGenerating()
    message.status == MessageStatus.ERROR -> labels.failed()
    message.status == MessageStatus.STOPPED -> labels.stopped()
    message.reasoningStreaming -> labels.thinking()
    message.streaming && message.content.isBlank() -> labels.streaming()
    else -> firstLinePreview(message.content).ifBlank { labels.empty() }
}

/**
 * 首行清洗 + 按**码点**截断。
 *
 * 从 Markdown 里取一行给人看：去掉行首的标题井号、引用符、列表符号与行内代码反引号，
 * 避免胶囊里出现 `## `、`> `、`- ` 这类标记。
 */
internal fun firstLinePreview(content: String, maxCodePoints: Int = ScrollPreviewMaxCodePoints): String {
    val firstLine = content.lineSequence()
        .map { it.trim() }
        .firstOrNull { it.isNotEmpty() }
        .orEmpty()
    val cleaned = firstLine
        .removePrefix("```")
        // 标题井号可能不止一个；引用/列表/强调符号统一从行首剥掉。
        .trimStart('#', '>', '-', '*')
        .trim()
        .replace("`", "")
        .trim()
    return truncateCodePoints(cleaned, maxCodePoints)
}

/** 按 Unicode 码点截断，避免把 emoji 的代理对切成半个字符。 */
internal fun truncateCodePoints(text: String, maxCodePoints: Int): String {
    if (maxCodePoints <= 0 || text.isEmpty()) return ""
    var index = 0
    var count = 0
    while (index < text.length && count < maxCodePoints) {
        index += Character.charCount(text.codePointAt(index))
        count++
    }
    return text.substring(0, index)
}

/** 胶囊最多显示多少个码点（约 13 字，窄屏一行放得下）。 */
internal const val ScrollPreviewMaxCodePoints = 13
