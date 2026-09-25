package com.simplechat.app.data

import com.simplechat.app.R

/** 标题最多多少字。抽屉一行放得下，也不会挤掉分组标签。 */
const val TITLE_MAX_CHARS = 20

/**
 * 生成标题用的系统提示词。**随界面语言取**（`values` 与 `values-en` 下的
 * `strings.xml`，键 `prompt_title_system`）—— 英文界面生成的标题才是英文。
 *
 * 要求写得很死：**只输出标题**。模型很爱解释"我为你起了这样一个标题…"，
 * 那会直接被当成标题显示出来。
 */
fun titleSystemPrompt(): String = Res.get(R.string.prompt_title_system)

/**
 * 各语言的**兜底标题**。
 *
 * 判"标题有没有被人改过"时两种都要认：会话可能建于中文界面、
 * 之后切到英文界面（或反过来），认不出来的后果是 AI 生成的标题永远盖不上去。
 */
val AUTO_TITLES = setOf("新对话", "New chat")

/** 这个标题还只是兜底值吗？是 → AI 生成的结果可以覆盖上去。 */
fun isAutoTitle(title: String): Boolean = title in AUTO_TITLES

/** 送进去当素材的正文长度上限（首条用户消息 / 首条回复各自截断）。 */
const val TITLE_MATERIAL_CHARS = 600

/** 生成标题的上限 token。标题很短，给多了纯属浪费。 */
const val TITLE_MAX_TOKENS = 32

/**
 * 从首条用户消息里**兜底**推出一个标题。
 *
 * 两个用途，所以必须是纯函数：
 * 1. 建会话时先写一个（AI 生成之前总得有个能看的名字）；
 * 2. 之后判断"标题有没有被人改过" —— 只有还等于这个兜底值时，
 *    AI 生成的结果才允许覆盖上去。
 *
 * 规则必须和写库时用的**完全一致**，否则第 2 条永远不成立、标题永远覆盖不了。
 *
 * [fallback] 由调用方从资源里取（`conversation_default_title`，随界面语言）：
 * 这是纯函数，不碰 Android Resources —— 单测直接把字符串传进来。
 */
fun derivedTitle(firstUserMessage: String, fallback: String): String =
    firstUserMessage.lineSequence().firstOrNull().orEmpty()
        .trim()
        .take(TITLE_MAX_CHARS)
        .ifBlank { fallback }

/**
 * 把模型吐出来的标题收拾干净。
 *
 * 模型非常爱加包装：「标题：xxx」、`"xxx"`、`《xxx》`、结尾还带个句号。
 * 这些符号在抽屉里只占位置、不提供信息，一律剥掉。
 *
 * 返回 null 表示"这东西没法当标题"（空、或者只剩符号），调用方应当保留原名字。
 */
fun cleanGeneratedTitle(raw: String): String? {
    val firstLine = raw.lineSequence().firstOrNull().orEmpty()

    val stripped = firstLine
        .removePrefix("标题：")
        .removePrefix("标题:")
        .removePrefix("Title:")
        .removePrefix("title:")
        .trim()
        // 成对的引号 / 书名号 / 括号
        .trim(
            '"', '\'', '“', '”', '‘', '’',
            '《', '》', '〈', '〉', '「', '」', '『', '』', '【', '】',
            '(', ')', '（', '）',
        )
        .trim()
        // 结尾的标点
        .trimEnd('。', '.', '！', '!', '？', '?', '，', ',', '、', '；', ';', '：', ':', '~', '～')
        .trim()

    if (stripped.isEmpty()) return null
    // 还是太长就硬截（模型不一定听"不超过 12 个字"）
    return stripped.take(TITLE_MAX_CHARS).ifBlank { null }
}
