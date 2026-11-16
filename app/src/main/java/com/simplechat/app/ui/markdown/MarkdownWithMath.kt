package com.simplechat.app.ui.markdown

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hrm.latex.renderer.Latex
import com.hrm.latex.renderer.model.LatexConfig
import com.hrm.latex.renderer.model.LatexTheme
import com.simplechat.app.ui.theme.LocalIsDarkTheme

/**
 * 带公式渲染的正文：按 [splitMath] 把内容切成文本段与公式段，交替渲染。
 *
 * - 文本段交给 [MarkdownText]（原有的一整套 Markdown / 代码块 / 流式渲染都不变）
 * - 公式段交给 `latex-renderer`（Compose 原生 Canvas 渲染，**不用 WebView**）
 *
 * ### 为什么不用 WebView + KaTeX
 * 每个公式一个 WebView 实例，在长消息列表里滚动会明显掉帧 —— 而那正是本项目
 * 要解决的第一个问题（见 PRD「轻量流畅是第一要义」）。原生 Canvas 渲染没有这层开销。
 *
 * ### 代价：行内公式不跟着文字走
 * 公式是独立的一块，行内公式也会**独自占一行**。这是"不引 WebView"必然的取舍：
 * 要做到真正的行内混排，得把公式排版成一段 `AnnotatedString` 并塞进
 * Markdown 渲染器的内联流里，那是另一个量级的工程。对长文创作来说，
 * 公式偶尔出现，独占一行读得懂；而 WebView 卡顿是每一条消息都要付的代价。
 */
@Composable
fun MarkdownWithMath(
    content: String,
    modifier: Modifier = Modifier,
    streaming: Boolean = false,
) {
    // 切分是纯字符串操作，跟着内容走；正文没变就不重切
    val segments = remember(content) { splitMath(content) }

    // 没有公式：直接走原来的路径，一个字节的额外开销都没有
    if (segments.size == 1 && segments.first() is ContentSegment.Text) {
        MarkdownText(
            content = (segments.first() as ContentSegment.Text).text,
            modifier = modifier,
            streaming = streaming,
        )
        return
    }

    val fontSize = LatexFontSize
    /*
     * 公式主题：**透明底 + 正文色**。
     *
     * ⚠️ 不能用库的 `LatexTheme.material3()` —— 那个会把 `colorScheme.surface`
     * 当作公式画布底色（浅色下是纯白、深色下是卡片灰），于是每段公式后面
     * 拖着一整条色块（真机反馈的灰底就是这么来的）。官方 KaTeX 是透明底。
     *
     * 也不能用 `LatexTheme.auto()`：它按**系统**深浅色选色板，而本项目允许
     * 强制浅色 / 深色，两者可能不一致 —— 会出现"深色页面上一段黑公式"。
     */
    val dark = LocalIsDarkTheme.current
    val textColor = MaterialTheme.colorScheme.onBackground
    val latexTheme = remember(dark, textColor) {
        if (dark) LatexTheme.dark(color = textColor) else LatexTheme.light(color = textColor)
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        segments.forEach { segment ->
            when (segment) {
                is ContentSegment.Text -> MarkdownText(
                    content = segment.text,
                    streaming = streaming,
                )

                is ContentSegment.Formula -> Latex(
                    latex = segment.tex,
                    modifier = Modifier
                        .fillMaxWidth()
                        // 行间公式上下多给一点空：它是一整块内容，不是一句话里的符号
                        .padding(vertical = if (segment.display) 4.dp else 0.dp),
                    config = LatexConfig(
                        fontSize = fontSize,
                        theme = latexTheme,
                    ),
                    /*
                     * 居中交给排版下方（`fillMaxWidth` + 组件自身居中），
                     * 这里只把行内公式按左对齐处理 —— 行内公式独占一行时左对齐更自然。
                     */
                )
            }
        }
    }
}

/**
 * 公式字号。与正文同档（17sp 左右），不随系统字号线性放大 ——
 * 公式排版对字号敏感，过大会撑破行宽，过小则看不清上下标。
 */
private val LatexFontSize = 17.sp
