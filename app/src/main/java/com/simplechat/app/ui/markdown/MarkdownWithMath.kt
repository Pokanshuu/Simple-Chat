package com.simplechat.app.ui.markdown

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import com.simplechat.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
 * 行内公式由 Markdown 的 AnnotatedString 与原生 InlineTextContent 连续排版，
 * 保留引用、列表、标题和标点。只有显式行间公式作为独立公式块渲染。
 */
@Composable
fun MarkdownWithMath(
    content: String,
    modifier: Modifier = Modifier,
    streaming: Boolean = false,
) {
    // 一个回复共用同一条显示进度。公式闭合或插入新段落时继续沿用这条进度。
    val revealed = rememberSmoothReveal(streaming = streaming, value = content)
    val segments = remember(revealed) { displayMathSegments(revealed) }
    if (segments.none { it is ContentSegment.Formula }) {
        MarkdownText(
            content = revealed,
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
        segments.forEachIndexed { index, segment ->
            when (segment) {
                is ContentSegment.Text -> MarkdownText(
                    content = segment.text,
                    streaming = streaming && index == segments.lastIndex,
                )

                is ContentSegment.Formula -> CompatibleFormula(segment, LatexConfig(fontSize = fontSize, theme = latexTheme))
            }
        }
    }
}

@Composable
private fun CompatibleFormula(segment: ContentSegment.Formula, config: LatexConfig) {
    if (LocalMarkdownExport.current != null) {
        ExportFormula(segment.tex, config.fontSize)
        return
    }
    val prepared by produceState<LatexPresentation?>(null, segment.tex) {
        value = withContext(Dispatchers.Default) { prepareLatexPresentation(segment.tex) }
    }
    var sourceVisible by remember(segment.tex) { mutableStateOf(false) }
    val presentation = prepared
    Column(Modifier.fillMaxWidth().padding(vertical = if (segment.display) 4.dp else 0.dp)) {
        if (presentation?.simplified == true) DisableSelection {
            TextButton(onClick = { sourceVisible = !sourceVisible }) {
                Text(stringResource(if (sourceVisible) R.string.latex_show_rendered else R.string.latex_compatible_source))
            }
        }
        if (presentation?.nativeTex != null && !sourceVisible) {
            // Horizontal scrolling keeps wide formulas accessible instead of clipping content.
            Latex(latex = presentation.nativeTex, config = config,
                modifier = Modifier.horizontalScroll(rememberScrollState()))
        } else {
            if (presentation != null && presentation.nativeTex == null) {
                DisableSelection {
                    Text(stringResource(R.string.latex_source_fallback), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            // Join the message's selection registrar, so ranges can cross formula source.
            Text(segment.tex, style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace))
        }
    }
}

/**
 * 公式字号。与正文同档（17sp 左右），不随系统字号线性放大 ——
 * 公式排版对字号敏感，过大会撑破行宽，过小则看不清上下标。
 */
private val LatexFontSize = 17.sp
