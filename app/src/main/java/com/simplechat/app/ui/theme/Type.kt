package com.simplechat.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp

/**
 * 全局排版基准。
 *
 * **「整体都偏大 / 偏小」时唯一该改的地方。**
 *
 * 整套字号（正文、标题、标签、代码块、markdown 各级标题）都由它算出来，
 * 一起等比缩放 —— 逐个手改那二十来个数字，比例会因为四舍五入走样，
 * 下次想再调又得重来一遍。
 *
 * 0.925 = 设置里「字体大小」滑块中「较小」那一档的原值。
 * 换句话说：原来要手动滑一档才舒服，现在把它变成了默认。
 */
private const val TypeScale = 0.925f

/**
 * 一个排版样式的统一构造入口。
 *
 * 传进来的都是**基准值**（缩放前），乘完 [TypeScale] 才是实际字号 ——
 * 所以下面读到的数字就是"设计稿上的数字"，不用心算。
 */
private fun textStyle(
    size: Float,
    lineHeight: Float,
    weight: FontWeight = FontWeight.Normal,
    family: FontFamily = FontFamily.Default,
): TextStyle = TextStyle(
    fontFamily = family,
    fontWeight = weight,
    fontSize = (size * TypeScale).sp,
    lineHeight = (lineHeight * TypeScale).sp,
    letterSpacing = 0.sp,
)

/**
 * 排版规格对标官方 Web 端（基准值，实际渲染会再乘 [TypeScale]）：
 * 正文 **16px / 28px**、标题 24/22/20...16、分组小标签 13sp。
 *
 * `bodyLarge` 的两个基准值（17.3 / 30.3）是**反推**出来的：
 * 官方 Web 端是 16/28，而我们有全局 [TypeScale] = 0.925 —— 16 / 0.925 = 17.3，
 * 28 / 0.925 = 30.3，乘回去正好是官方的 16/28。写整数 17/29 会差 2% 字号、4% 行高，
 * 单看不明显，和官方截图并排一放就能看出来"我们的行更紧"。
 */
val SimpleChatTypography = Typography(
    // 正文：AI 回复与用户气泡共用，行高宽松，长文阅读友好
    bodyLarge = textStyle(size = 17.3f, lineHeight = 30.3f),
    bodyMedium = textStyle(size = 15f, lineHeight = 24f),
    bodySmall = textStyle(size = 13f, lineHeight = 20f),
    // 标题
    titleLarge = textStyle(size = 22f, lineHeight = 30f, weight = FontWeight.Bold),
    titleMedium = textStyle(size = 17f, lineHeight = 24f, weight = FontWeight.SemiBold),
    titleSmall = textStyle(size = 15f, lineHeight = 22f, weight = FontWeight.Medium),
    // 标签：分组小标题、页脚提示
    labelMedium = textStyle(size = 13f, lineHeight = 18f),
    labelSmall = textStyle(size = 12f, lineHeight = 16f),
)

// ── Markdown 用样式 ────────────────────────────────────────
//
// 下面这几个**刻意派生自 `SimpleChatTypography`，不再各写一份相同的数值**。
//
// 先前 `MarkdownBodyStyle` 与 `bodyLarge`、`ProcessTextStyle` 与 `bodyMedium`
// 是把一样的数手抄了两遍；哪天改字号忘了同步，表现就是"流式结束的瞬间
// 字号跳一下"——用户看得见，但很难联想到是两处声明不同步。
// 派生之后，这种可能从源头消失。

/**
 * Markdown 正文 = 全局正文（官方 16/28），**不做两端对齐**。
 *
 * 早先中文档开了 `TextAlign.Justify`，理由是"中文方块字没有词间空格"。
 * 但实际正文里中英混排极常见（专有名词、代号、英文引文），
 * 两端对齐会把那一行里的**西文词间空格**拉成一道道白沟 ——
 * 真机截图上 "The       quick brown fox" 就是这样。
 * 官方 Web 端就是默认左对齐（`markdown.css` 没有 justify），这里对齐官方。
 *
 * 两份样式都是**常量**（不是 `get()`）：`.copy` 会新建 TextStyle，挂在组合里的
 * 样式每帧读一次，白白的分配没必要。[MarkdownBlockView] 每块都会读它。
 */
@Composable
fun markdownBodyStyle(cjk: Boolean): TextStyle {
    /*
     * 模型回复层的自定义字体在这里落地：正文样式是常量，只有用户真的选了字体
     * 才 `.copy` 一次。没选时仍返回那个常量对象，避免每块 Markdown 都新建样式
     * （那会让 Compose 判为样式变化，整段重排版）。
     */
    val base = if (cjk) MarkdownBodyCjkStyle else MarkdownBodyWesternStyle
    val family = LocalTypographyFonts.current.assistant ?: LocalAssistantFontFamily.current
    return if (family == null) base else base.copy(fontFamily = family)
}

/** 中文档正文：左对齐（原因见上）。 */
private val MarkdownBodyCjkStyle: TextStyle =
    SimpleChatTypography.bodyLarge.copy(textAlign = TextAlign.Start)

/** 西文档正文：左对齐，断行走 HighQuality（长单词落点更准，仍不开连字符）。 */
private val MarkdownBodyWesternStyle: TextStyle =
    SimpleChatTypography.bodyLarge.copy(
        textAlign = TextAlign.Start,
        lineBreak = LineBreak.Paragraph,
    )

/** 过程面板（思考区）文字 = 全局次级文字。 */
val ProcessTextStyle: TextStyle get() = SimpleChatTypography.bodyMedium

/**
 * Markdown 的六级标题，**比例对齐官方 Web 端**（正文 16px 时：
 * h1 24 / h2 22 / h3 20 / h4–h6 16；h1–h3 Bold、h4–h6 SemiBold）。
 *
 * 换算到我们的正文基准（`bodyLarge` 17 / 29）就是下表的数字 ——
 * 先前是 20/19/18/17/16/16 一路小下去，层级只差 1sp，
 * 视觉上"标题只是字大一点点"，长文里几乎没有结构感。
 *
 * 为什么 h4–h6 与正文同字号：官方就是这么定的 —— h4 起靠**字重**分层，
 * 不靠字号。再小下去会正文还小，那就不像标题了。
 */
val MarkdownHeadingStyles: List<TextStyle> = listOf(
    textStyle(size = 25.5f, lineHeight = 35f, weight = FontWeight.Bold),
    textStyle(size = 23.4f, lineHeight = 33f, weight = FontWeight.Bold),
    textStyle(size = 21.25f, lineHeight = 31f, weight = FontWeight.Bold),
    textStyle(size = 17f, lineHeight = 29f, weight = FontWeight.SemiBold),
    textStyle(size = 17f, lineHeight = 29f, weight = FontWeight.SemiBold),
    textStyle(size = 17f, lineHeight = 29f, weight = FontWeight.SemiBold),
)

/** 表格内文字：官方 15/25（正文 16/28），比正文小一档、行高略宽。 */
val MarkdownTableStyle: TextStyle = textStyle(size = 15.9f, lineHeight = 26f)

/**
 * 列表项目符号与序号（`bullet` / `ordered`）。
 *
 * ⚠️ **必须与正文同字号、同行高。**
 *
 * 库把符号放在一个 `Row` 的**顶部**、和列表正文并排；两边字号一旦不同，
 * 基线就错开 —— 真机反馈正是"无序符号和正文不对齐，数字大一圈"。
 *
 * 先前为了让 CJK 里的 `•` 显得明显，把这一档放大到 21sp；结果英文列表里
 * 数字比正文大 34%，而且整体往上蹿半行。官方（浏览器默认）就是**正文字号**，
 * 这里照做 —— 颜色由渲染处按主题补成次级文字色。
 */
val MarkdownMarkerStyle: TextStyle = SimpleChatTypography.bodyLarge

/** 行内代码：官方 `0.875em`（正文 16 时 14px），等宽字体。 */
val MarkdownInlineCodeStyle: TextStyle =
    textStyle(size = 14.9f, lineHeight = 22f, family = FontFamily.Monospace)

/** 代码块正文：官方 13/22（正文 16/28）。 */
val CodeTextStyle: TextStyle =
    textStyle(size = 13.8f, lineHeight = 23f, family = FontFamily.Monospace)
