package com.simplechat.app.data

import kotlinx.serialization.Serializable

/** 生成中（思维链还在流）时，思考内容的展示策略。 */
@Serializable
enum class ThinkingDuring {
    /** 全文可见、随流增长。 */
    FULL,

    /** 只显示尾巴（最后约 4 行，底部对齐、顶部渐隐）。 */
    PARTIAL,
}

/** 思维链收口（首条正文到达 / 中断 / 失败）后，思考内容的展示策略。 */
@Serializable
enum class ThinkingAfter {
    /** 保持全文展开。 */
    EXPAND,

    /** 收成一行标题「已思考（用时 N 秒）」。 */
    COLLAPSE,
}

/**
 * 「思考过程」的展开策略。**只影响展示，不参与请求构造。**
 *
 * 结构与语义（docs/42 §52.4）：
 * - [expand] 是总开关：关闭时生成中只显示尾巴、结束后自动收起 —— 与旧版行为逐帧一致；
 * - 打开后 [during] / [after] 两个阶段策略各自独立；
 * - 旧键 `auto_collapse_thinking` 只用于迁移（见 [fromLegacyAutoCollapse]），
 *   写入时仍镜像回去，方便用户降级到旧版本。
 *
 * 默认值刻意取"关闭"：老用户升级后什么都不变。打开后的默认是"全部展开 + 保持展开"，
 * 这样开关一打开就能立刻看到差异。
 */
@Serializable
data class ThinkingDisplaySettings(
    val expand: Boolean = false,
    val during: ThinkingDuring = ThinkingDuring.FULL,
    val after: ThinkingAfter = ThinkingAfter.EXPAND,
) {
    /** 实际生效的生成中策略：总开关关闭时一律只看尾巴。 */
    fun effectiveDuring(): ThinkingDuring = if (expand) during else ThinkingDuring.PARTIAL

    /** 实际生效的生成结束策略：总开关关闭时一律自动收起。 */
    fun effectiveAfter(): ThinkingAfter = if (expand) after else ThinkingAfter.COLLAPSE

    companion object {
        /**
         * 旧键 `auto_collapse_thinking` → 新模型。
         *
         * 旧语义只有"结束后是否自动折叠"这一半：
         * - 缺省 / `true`（旧默认）→ 新默认（关闭 = 尾巴 + 折叠）；
         * - `false`（用户手动关过自动折叠）→ 展开 + 显示部分 + 保持展开，
         *   与旧行为完全一致（生成中仍是尾巴，结束后保持全文）。
         */
        fun fromLegacyAutoCollapse(autoCollapse: Boolean?): ThinkingDisplaySettings = when (autoCollapse) {
            null, true -> ThinkingDisplaySettings()
            false -> ThinkingDisplaySettings(
                expand = true,
                during = ThinkingDuring.PARTIAL,
                after = ThinkingAfter.EXPAND,
            )
        }
    }
}

/** 手动覆盖表的上限：正常使用远远到不了，只是防止长会话无限增长。 */
internal const val ThinkingOverrideLimit = 1000

/**
 * 没有任何手动覆盖时，某条消息的思考面板是否展开。
 *
 * 纯函数，单测覆盖：生成中看 [ThinkingDisplaySettings.effectiveDuring]，
 * 收口后看 [ThinkingDisplaySettings.effectiveAfter]。
 */
internal fun thinkingExpandedByDefault(
    reasoningStreaming: Boolean,
    policy: ThinkingDisplaySettings,
): Boolean = if (reasoningStreaming) {
    policy.effectiveDuring() == ThinkingDuring.FULL
} else {
    policy.effectiveAfter() == ThinkingAfter.EXPAND
}

/**
 * 解析某条消息最终的展开状态：手动覆盖优先于全局策略。
 *
 * @param override 用户手动点过标题 / 「展开全部」/ 搜索命中后的值；null = 跟随策略。
 */
internal fun resolveThinkingExpanded(
    override: Boolean?,
    reasoningStreaming: Boolean,
    policy: ThinkingDisplaySettings,
): Boolean = override ?: thinkingExpandedByDefault(reasoningStreaming, policy)

/**
 * 展开时是否播放 220 ms 尺寸动画。
 *
 * 长文本 / 超过一屏的内容瞬时展开：逐帧重排长思考必然掉帧，用户真正要的是"打开就能读"。
 * 纯函数，单测覆盖（docs/42 §52.7.3）。
 */
internal fun shouldAnimateThinkingExpand(
    charCount: Int,
    contentHeightPx: Int,
    viewportHeightPx: Int,
): Boolean = charCount < 4000 && contentHeightPx < viewportHeightPx
