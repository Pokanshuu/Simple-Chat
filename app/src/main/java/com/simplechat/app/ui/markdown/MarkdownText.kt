package com.simplechat.app.ui.markdown

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.DisableSelection
import com.simplechat.app.ui.chat.preferHorizontalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.annotator.annotatorSettings
import com.mikepenz.markdown.annotator.buildMarkdownAnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import com.mikepenz.markdown.compose.LocalMarkdownInlineContent
import com.mikepenz.markdown.compose.LocalMarkdownAnnotator
import com.mikepenz.markdown.compose.LocalBulletListHandler
import com.mikepenz.markdown.compose.components.MarkdownComponent
import com.mikepenz.markdown.compose.components.MarkdownComponentModel
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownDivider
import com.mikepenz.markdown.compose.elements.MarkdownText as LibraryMarkdownText
import com.mikepenz.markdown.compose.elements.highlightedCodeBlock
import com.mikepenz.markdown.compose.elements.highlightedCodeFence
import com.mikepenz.markdown.compose.elements.MarkdownCodeFence
import com.mikepenz.markdown.compose.extendedspans.ExtendedSpans
import com.mikepenz.markdown.compose.extendedspans.RoundedCornerSpanPainter
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.State
import com.mikepenz.markdown.model.markdownDimens
import com.mikepenz.markdown.model.markdownExtendedSpans
import com.mikepenz.markdown.model.markdownPadding
import com.mikepenz.markdown.model.parseMarkdown
import com.simplechat.app.R
import com.simplechat.app.data.LocaleHelper
import com.simplechat.app.ui.common.AppIcons
import com.simplechat.app.ui.common.CopyIconButton
import com.simplechat.app.ui.theme.CodeTextStyle
import com.simplechat.app.ui.theme.LocalChatColors
import com.simplechat.app.ui.theme.markdownBodyStyle
import com.simplechat.app.ui.theme.MarkdownHeadingStyles
import com.simplechat.app.ui.theme.MarkdownInlineCodeStyle
import com.simplechat.app.ui.theme.MarkdownTableStyle
import com.simplechat.app.ui.theme.SimpleChatTypography
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.findChildOfType
import org.intellij.markdown.flavours.gfm.GFMElementTypes.HEADER
import org.intellij.markdown.flavours.gfm.GFMElementTypes.ROW
import org.intellij.markdown.flavours.gfm.GFMTokenTypes.CELL

/**
 * AI 正文的 Markdown 渲染。
 *
 * ### 为什么改成实时渲染（原先是"流式纯文本 → 定稿才渲染"两阶段）
 *
 * 两阶段的问题是**版式会跳**：流式时纯文本、定稿后 markdown，
 * 两者的段落间距、对齐方式、标题字重都不一样，定稿那一瞬间整段内容重排。
 * 更要命的是纯文本里"空行"是实打实的一整行，而 markdown 的块间距只有几个 dp ——
 * 用户看到的就是"渲染完段落之间的空没了"。
 *
 * 现在两个阶段**用同一套渲染**，版式从头到尾一致，也就不存在跳变。
 *
 * ### 那性能怎么办（两道闸）
 *
 * **一、控制频率**：[MarkdownWithMath] 在切分公式之前通过 [rememberSmoothReveal]
 * 按帧放出正文前缀。这里收到的已经是本帧应显示的文本，定稿时收到完整正文。
 *
 * **二、降单次量**：真正的大头是"每次提交都重解析**整篇**"。切块渲染
 * （见 [MarkdownTextBlocks]）之后，已完成的块文本不变 → Compose 跳过重组 →
 * 解析器不重解析，只有尾块随 token 变。解析量从 O(全文) 降到 O(尾块)。
 *
 * 两者叠加：频率决定"多久动一次"，分块决定"每次要做多少"。
 * 定稿前后**像素级一致**由"两阶段用同一套渲染"保证，不靠降级。
 */
@Composable
fun MarkdownText(
    content: String,
    modifier: Modifier = Modifier,
    streaming: Boolean = false,
) {
    val document = remember(content) { normalizedMarkdownDocument(content) }

    MarkdownTextBlocks(
        source = document.markdown,
        streaming = streaming,
        modifier = modifier,
        blockGap = MarkdownBlockColumnGap,
    ) { blockText, isTail ->
        val block = remember(document, blockText) { document.forBlock(blockText) }
        MarkdownBlockView(math = block, isTail = isTail)
    }
}

/**
 * 已解析的 Markdown 状态缓存。
 *
 * ### 为什么必须有它
 *
 * 库的 `Markdown(content = …)` 内部走 `rememberMarkdownState(content)`：
 * 它在 `Dispatchers` 上**异步**解析，状态先 `Loading` 再 `Success`
 * （见 `MarkdownStateImpl` / `parseMarkdownFlow`）。
 *
 * 于是条目一进视口，先按 `Loading` 的高度量一次，解析完成才长到真实高度。
 * 实测同一条消息：**2946px → 13431px → 23355px**，第一次只有真实高度的 **10%**
 * （真实高度 ≈ 1.7px/字，第一次 ≈ 0.17px/字，两者都严格跟字数成正比）。
 *
 * 而 LazyList 的锚点是 `(下标, 偏移)`，**偏移是内容里的绝对像素位置** ——
 * 内容从 10% 长到 100% 时，同一个偏移落到完全不同的地方，视口就跳。
 * 表现就是「向上滑有概率飞到很上面」，长消息（解析久）更容易中。
 *
 * ### 解法
 *
 * 用库的**同步**入口 [parseMarkdown] 自己解析，再走 `Markdown(state = …)` 重载渲染：
 * 高度**第一次测量就是真实的**，没有 `Loading` 那一帧，也就没有那次增长。
 *
 * 同步解析的代价落在主线程，所以再加一层**按块文本的缓存**：同一条消息滚出去
 * 再滚回来、或同一段文字出现在别处，都直接命中，不重复解析。
 * 键是**块文本**（切块后内容稳定），命中率很高。
 */
/**
 * 按**块文本**缓存的解析结果（LRU）。
 *
 * 键是切块后的文本（内容稳定），命中率很高：同一条消息滚出再滚回、
 * 或同一段文字出现在别处，都直接命中。
 *
 * ### 为什么按"字符总量"淘汰，而不是按条数
 *
 * 早先是"条数上限 256，满了 `clear()` 整体丢弃"。两个问题：
 *
 * 1. **条数与内存无关**：一块可能是 20 个字的一行，也可能是 3 万字的长回复；
 *    同样 256 条，后者占的内存是前者的上千倍。
 * 2. **整体清空会抖动**：长会话滚动时，缓存满 → 全清 → 接下来每块都要重解析 →
 *    又迅速填满 → 再全清。真机表现就是"长会话上下滑一顿一顿的"。
 *
 * 按字符总量做 LRU（访问序）淘汰：容量与内存成正比，且只淘汰最久没被用到的那些。
 */
private val MarkdownStateCache = LinkedHashMap<String, State>(128, 0.75f, true)

/** 缓存里所有块文本的字符总数（与 [MarkdownStateCache] 一起在锁内维护）。 */
private var cachedChars = 0

/** 缓存上限：40 万字符 ≈ 十几条长回复的解析结果，够几屏用，又不会自己变成内存大头。 */
private const val MarkdownStateCacheMaxChars = 400_000

/**
 * 取（没有就同步解析）一段文本的 Markdown 状态。
 *
 * ⚠️ **解析必须在锁外做**。
 *
 * 早先把 `parseMarkdown(text)` 写在 `synchronized` 块里，两个线程同时要解析时
 * 后到的那个会**持锁等前一个解析完**。而解析一条长消息可能要几百毫秒到几秒：
 * 主线程（组合时取缓存）等后台（预热）的锁 → 界面直接卡住。
 * 真机反馈"同一个长会话第一次打开顺畅、第二次打开卡死"正是这个模式 ——
 * 第一次缓存是空的、只有后台在解析；第二次后台与主线程同时进来抢锁。
 *
 * 现在改成：锁内只做查/存，解析在锁外。极端情况下同一段文本可能被解析两次
 * （两个线程同时 miss），代价是一次重复解析，换来的是主线程永远不会被解析挡住。
 */
private fun markdownStateOf(text: String): State {
    synchronized(MarkdownStateCache) {
        MarkdownStateCache[text]?.let { return it }
    }
    val state = parseMarkdown(text)
    synchronized(MarkdownStateCache) {
        // 另一个线程可能已经放进去了：以先到的那份为准，避免缓存里出现两份状态
        MarkdownStateCache[text]?.let { return it }
        MarkdownStateCache[text] = state
        cachedChars += text.length
        // 按"最久未使用"淘汰，直到回到预算内（至少留一条：这条刚解析完，马上要渲染）
        while (cachedChars > MarkdownStateCacheMaxChars && MarkdownStateCache.size > 1) {
            val eldest = MarkdownStateCache.keys.firstOrNull() ?: break
            MarkdownStateCache.remove(eldest)
            cachedChars -= eldest.length
        }
    }
    return state
}

internal const val MarkdownPrewarmMaxChars = 24_000
private const val MarkdownPrewarmMaxBlocks = 48

internal fun normalizedMarkdownDocument(content: String): InlineMathDocument {
    // TeX can contain HTML-looking inequalities, emphasis markers and table pipes.
    val document = inlineMathDocument(content)
    return document.copy(markdown = normalizeSoftBreaks(normalizeHtml(document.markdown)))
}

/** Prepare the same AST keys as MarkdownWithMath/MarkdownBlockView on a background dispatcher. */
internal suspend fun warmMarkdownCache(content: String, warmBlock: (String) -> Unit = { markdownStateOf(it) }) {
    if (content.length !in 1..MarkdownPrewarmMaxChars) return
    val context = currentCoroutineContext()
    context.ensureActive()
    var remainingBlocks = MarkdownPrewarmMaxBlocks
    var remainingChars = MarkdownPrewarmMaxChars
    for (segment in displayMathSegments(content)) {
        context.ensureActive()
        if (segment !is ContentSegment.Text) continue // Display LaTeX has its own renderer.
        val normalized = normalizedMarkdownDocument(segment.text).markdown
        if (normalized.length > MarkdownPrewarmMaxChars) return
        for (block in splitMarkdownBlocks(normalized)) {
            context.ensureActive()
            val key = block.text
            if (key.length > remainingChars) return
            warmBlock(key)
            remainingChars -= key.length
            if (--remainingBlocks == 0) return
        }
    }
}

/**
 * 单个 Markdown 块的渲染。
 *
 * ### 参数只传块文本和是否为流式尾块
 *
 * Compose 的"跳过重组"是按**参数**判的：只有参数全稳定、且与上次相等，
 * 才会整个跳过这个 composable 的函数体。样式（颜色 / 字体 / 内边距 / 组件）
 * 如果当参数传进来，它们是每次重组都新建的对象 —— 参数永不相等，于是
 * **每一个块、每一帧**都要重走一遍「解析 → 建 AnnotatedString → 排版」。
 * 分块渲染想省的正是这个（只重解析尾块）；一旦样式走参数，就全白做了。
 *
 * 样式从主题 / CompositionLocal 里读，块的两个参数都是稳定值。流式期间
 * 只有**尾块**的 `text` 在变，前面所有块的参数不变 → **整个跳过**，
 * 解析与排版一起省掉。样式对象也顺带只在真的重组时才新建一份。
 *
 * （块的 `text` 每次切块都是新 `String` 实例，但 `String` 按值比较，
 * 内容没变的块参数仍然相等 —— 跳过照样成立。）
 */
@Composable
private fun MarkdownBlockView(math: InlineMathDocument, isTail: Boolean) {
    val text = math.markdown
    val colors = LocalChatColors.current
    val scheme = MaterialTheme.colorScheme

    // 正文样式按语种分档（多语言 §4：中文两端对齐、西文左对齐）。
    // 两份样式都是常量，不产生分配；也不进参数 —— 参数变了就不会跳过重组。
    val baseBodyStyle = markdownBodyStyle(LocaleHelper.isCjkUi(LocalContext.current))
    val categoryColor = com.simplechat.app.ui.theme.LocalTypographyTextColor.current
    val bodyStyle = if (categoryColor == null) baseBodyStyle else baseBodyStyle.copy(color = categoryColor)

    // 同步解析（见 MarkdownStateCache 的说明）：高度第一次测量就是真实的。
    val state = remember(math.markdown, isTail) {
        if (isTail) parseMarkdown(math.markdown) else markdownStateOf(math.markdown)
    }
    // A code card can shrink on purpose, including when it first becomes collapsible.
    // Keeping the earlier block height would leave empty space under the folded card.
    val modifier = if (isTail && state is State.Success && remember(state) { !state.node.containsCodeFence() }) {
        Modifier.retainMinHeightWhileStreaming()
    } else {
        Modifier
    }
    if (isTail && math.formulas.isEmpty() && state is State.Success &&
        LocalSearchHighlight.current == null && LocalMarkdownExport.current == null &&
        remember(state) { isLiteralParagraph(text, state.node) }
    ) {
        StreamingProse(text = text, style = bodyStyle, color = scheme.onBackground, modifier = modifier)
        return
    }
    val inlineMath = if (math.formulas.isNotEmpty()) rememberInlineMath(math, Constraints.Infinity) else null

    /*
     * 符号与引用条的颜色跟主题走，**字号/行高必须与正文一致**：
     * 符号与正文并排放在 Row 顶部，字号不同基线就错开
     * （真机反馈：无序符号和正文不对齐、数字大一圈）。
     * 符号列的具体排版在 [MarkdownListLayout] 里，这里只提供样式。
     */
    val markerStyle = markdownMarkerStyle()
    // 引用条的颜色取自 `quote` 的 color，正文仍走 paragraph（官方就是灰竖线 + 正常正文色）
    val quoteStyle = bodyStyle.copy(color = colors.markdownQuoteBar)

    // 行内代码的胶囊形状/留白只能由 ExtendedSpans 画（SpanStyle 不支持 padding）。
    // ⚠️ 必须**每个块各建一个**：它内部持有 lastLayout 的绘制指令（可变状态），
    // 全局共用一个实例时，A 块的指令会被画到 B 块上 —— 表现就是"公式/标题后面
    // 莫名多出灰色方块"（真机反馈的浅灰 / 深灰色块）。
    val extendedSpans = markdownExtendedSpans {
        val searchPainter = rememberSearchSpanPainter()
        val citationPainter = rememberCitationChipPainter()
        val selectionPainter = rememberSelectionLayoutPainter()
        remember(searchPainter, citationPainter, selectionPainter) {
                val backgroundPainter = RoundedCornerSpanPainter(
                    cornerRadius = 3.sp,
                    /*
                     * ⚠️ 上下各留 5sp 的"负空间"（margin）把胶囊**收进文字行内**。
                     *
                     * 库给的 `box` 是**整行行框**（行高 30sp），而行内代码只有 15sp：
                     * margin 给 0 时画出来是一个比字高一倍的灰色大块（真机截图里
                     * "1.0.0" 那个方块），比没画还难看。官方 Web 的胶囊只包住字身。
                     */
                    padding = RoundedCornerSpanPainter.TextPaddingValues(horizontal = 2.sp, vertical = 0.sp),
                    topMargin = 5.sp,
                    bottomMargin = 5.sp,
                )
            ExtendedSpans(*listOfNotNull(backgroundPainter, citationPainter, searchPainter, selectionPainter).toTypedArray())
        }
    }

    CompositionLocalProvider(
        LocalBulletListHandler provides BulletMarkers,
        LocalInlineMathDocument provides math,
    ) {
        Markdown(
            state,
            // 大标题（h1–h3）上方补白：官方 32px，块间距已给 16dp，这里再补 16dp
            modifier = modifier.padding(top = headingTopGap(text)),
            colors = markdownColor(
                text = categoryColor ?: scheme.onBackground,
                codeBackground = colors.markdownCodeBlock,
                inlineCodeBackground = colors.markdownInlineCode,
                dividerColor = colors.markdownRule,
                // 官方表格没有整块底色，只有行间横线；这里也不画卡片
                tableBackground = Color.Transparent,
            ),
            /*
             * 段落间距：官方 `margin: 16px 0`（相邻段落折叠后仍是 16px）。
             *
             * 分块渲染之后这 16dp 由**外层 Column**（[MarkdownBlockColumnGap]）统一给，
             * 库自己那一层置 0 —— 两边都给就叠成 24dp，长文读起来"一段一段断开"。
             */
            padding = markdownPadding(
                block = 0.dp,
                /*
                 * 列表：块级上下留白由外层 Column 统一给（官方 ul/ol 上下 16px）；
                 * 项距、符号列宽、嵌套缩进全部由自绘列表 [MarkdownListLayout] 控制，
                 * 库这几个参数因此都置 0（留着会与自绘那份叠加）。
                 */
                list = 0.dp,
                listItemTop = 0.dp,
                listItemBottom = 0.dp,
                listIndent = 0.dp,
                codeBlock = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                // 引用：官方 border-left 2px + padding-left 14px，没有额外上下内边距
                blockQuote = PaddingValues(start = 16.dp),
                blockQuoteText = PaddingValues(0.dp),
                blockQuoteBar = PaddingValues.Absolute(left = 0.dp, right = 0.dp),
            ),
            dimens = markdownDimens(
                dividerThickness = 1.dp,
                codeBackgroundCornerSize = CodeBlockCornerRadius,
                blockQuoteThickness = 2.dp,
                // 官方单元格 10/16 的内边距；库只支持统一值，取中间 12dp 近似
                tableCellPadding = 12.dp,
                // 列宽是库的"等宽列"参数：官方按内容宽度排，库做不到，取小一点让两列表格不发散
                tableCellWidth = 120.dp,
                tableCornerSize = 0.dp,
            ),
            extendedSpans = extendedSpans,
            annotator = inlineMath?.first ?: com.mikepenz.markdown.model.markdownAnnotator(),
            inlineContent = inlineMath?.second ?: com.mikepenz.markdown.model.markdownInlineContent(),
            animations = com.mikepenz.markdown.model.markdownAnimations { this },
            typography = markdownTypography(
                h1 = MarkdownHeadingStyles[0],
                h2 = MarkdownHeadingStyles[1],
                h3 = MarkdownHeadingStyles[2],
                h4 = MarkdownHeadingStyles[3],
                h5 = MarkdownHeadingStyles[4],
                h6 = MarkdownHeadingStyles[5],
                // 正文与流式阶段用**同一个**样式，否则定稿瞬间字号会跳一下
                text = bodyStyle,
                paragraph = bodyStyle,
                quote = quoteStyle,
                ordered = markerStyle,
                bullet = markerStyle,
                list = bodyStyle,
                table = MarkdownTableStyle,
                code = CodeTextStyle,
                inlineCode = MarkdownInlineCodeStyle,
                alertTitle = SimpleChatTypography.titleSmall,
                // 官方链接：品牌色、默认无下划线（悬停才加）。移动端没有 hover，
                // 下划线常驻会让中文正文里到处是"着重线"，很吵。
                textLink = TextLinkStyles(style = SpanStyle(color = scheme.primary)),
            ),
            components = selectionMeasuredComponents(rememberSearchableMarkdownComponents(widthAwareMathComponents(markdownComponents(
                codeFence = collapsibleCodeFence,
                codeBlock = highlightedCodeBlock,
                table = adaptiveTable,
                blockQuote = continuousMarkdownQuote,
                unorderedList = markdownBulletList,
                orderedList = markdownOrderedList,
                checkbox = taskCheckbox,
                horizontalRule = spacedHorizontalRule,
            )))),
        )
    }
}

/** Lists and quotes can be narrower than the Markdown block that owns the formula map. */
private fun widthAwareMathComponents(original: com.mikepenz.markdown.compose.components.MarkdownComponents) =
    object : com.mikepenz.markdown.compose.components.MarkdownComponents by original {
        override val text = widthAwareMath(original.text)
        override val paragraph = widthAwareMath(original.paragraph)
        override val heading1 = widthAwareMath(original.heading1)
        override val heading2 = widthAwareMath(original.heading2)
        override val heading3 = widthAwareMath(original.heading3)
        override val heading4 = widthAwareMath(original.heading4)
        override val heading5 = widthAwareMath(original.heading5)
        override val heading6 = widthAwareMath(original.heading6)
        override val setextHeading1 = widthAwareMath(original.setextHeading1)
        override val setextHeading2 = widthAwareMath(original.setextHeading2)
    }

private fun widthAwareMath(original: MarkdownComponent): MarkdownComponent = { model ->
    val document = LocalInlineMathDocument.current
    val math = remember(document, model.content, model.node) {
        if (document == null || document.formulas.isEmpty()) null
        else document.forBlock(model.content.substring(model.node.startOffset, model.node.endOffset))
    }
    if (math == null || math.formulas.isEmpty()) original(model)
    else BoxWithConstraints {
        val inherited = LocalMarkdownInlineContent.current.inlineContent
        val exporting = LocalMarkdownExport.current != null
        val density = LocalDensity.current
        val width = constraints.maxWidth
        val fitted = remember(inherited, math.formulas.keys, width, density, exporting) {
            inherited.filterKeys(math.formulas::containsKey).mapValues { (_, formula) ->
                formula.fitWidth(width, density, exporting)
            }
        }
        val inline = com.mikepenz.markdown.model.markdownInlineContent(fitted)
        CompositionLocalProvider(LocalMarkdownInlineContent provides inline) { original(model) }
    }
}

private fun ASTNode.containsCodeFence(): Boolean =
    type == MarkdownElementTypes.CODE_FENCE || children.any { it.containsCodeFence() }

/** 大标题上方补白（见 [MarkdownBlockView]）。 */
private fun headingTopGap(text: String): Dp {
    val firstLine = text.trimStart()
    return if (
        firstLine.startsWith("# ") || firstLine.startsWith("## ") || firstLine.startsWith("### ")
    ) {
        16.dp
    } else {
        0.dp
    }
}

/**
 * 块间距（**外层 Column 那一层**）：块与块之间统一 16dp。
 *
 * 官方 Markdown 的段落是 `margin: 16px 0`，相邻段落折叠后仍是 16px。
 * 分块渲染之后，库内那层（`markdownPadding.block`）**置 0**，
 * 全部由这里给 —— 否则两份叠加成 24dp，长文读起来"一段一段断开"。
 *
 * 唯一例外是大标题（h1–h3）：官方上边距 32px，见 [headingTopGap] 补的那 16dp。
 */
private val MarkdownBlockColumnGap = 16.dp

// ══════════════════════════════════════════════════════════
//  代码块：语言标签 + 复制 + 超长折叠
// ══════════════════════════════════════════════════════════

/** 超过这个行数就收起来，避免一段代码吃掉整屏。 */
private const val CollapseLineThreshold = 20

/** 收起时代码区保留的高度（约 12 行等宽文字）。 */
private val CollapsedCodeHeight = 300.dp

/**
 * 自绘顶栏 + 库自带的高亮渲染。
 *
 * 库的 `highlightedCodeFence` 插槽没打开它内部的 `showHeader`
 * （源码里那个参数确实存在，但插槽传的是关），所以语言标签与复制按钮
 * 一个都不显示。与其去凑它的内部状态，不如自己拼一条顶栏 ——
 * 两块用**同一个**背景色，接缝看不出来。
 *
 * ### ⚠️ 代码文本必须从 AST 节点里取
 *
 * `MarkdownComponentModel.content` 是**整篇文档**，不是这一段代码 ——
 * 库内部是 `getTextInNode(node, content)` 才切出代码的。
 * 早先直接 `splitFence(model.content)`，于是"行数"数的是全文行数：
 * 一段 5 行的代码显示成「共 24 行」，还错误地判定为"需要折叠" ——
 * 表现就是**展开/收起点了没反应**（本来就没被裁掉，展开自然没变化）。
 */
/**
 * 水平分割线：官方 `margin: 32px 0`。
 *
 * 外层 Column 已经给 16dp，块内再补 16dp —— 上下各 32dp。
 */
private val spacedHorizontalRule: MarkdownComponent = { _ ->
    Column {
        Spacer(Modifier.height(16.dp))
        MarkdownDivider()
        Spacer(Modifier.height(16.dp))
    }
}

/** Content-sized columns share one horizontal scroller, so headers and body remain aligned. */
private val adaptiveTable: MarkdownComponent = { model ->
    val exporting = LocalMarkdownExport.current
    val style = model.typography.table
    val header = remember(model.node) { model.node.findChildOfType(HEADER) }
    val rows = remember(model.node) { model.node.children.filter { it.type == ROW } }
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val inlineMath = LocalMarkdownInlineContent.current.inlineContent
    val columnWidths = exporting?.tableMetrics?.widths ?: remember(model.content, model.node, style, density, inlineMath) {
        val tableRows = listOfNotNull(header) + rows
        val columnCount = tableRows.maxOfOrNull { row -> row.children.count { it.type == CELL } } ?: 0
        val widths = FloatArray(columnCount) { with(density) { 88.dp.toPx() } }
        tableRows.forEach { row ->
            row.children.filter { it.type == CELL }.forEachIndexed { index, cell ->
                // Width sampling is bounded; rendering below still includes the entire cell.
                val raw = model.content.substring(cell.startOffset, cell.endOffset).take(256)
                fun textWidth(text: String): Float {
                    val measured = measurer.measure(text = text, style = style.copy(fontWeight = FontWeight.Medium),
                        softWrap = false, constraints = Constraints(maxWidth = with(density) { 320.dp.roundToPx() }))
                    return (0 until measured.lineCount).maxOfOrNull { measured.getLineRight(it) } ?: 0f
                }
                val formulaWidth = inlineMath.filterKeys { id -> id in raw }.values.sumOf {
                    with(density) { it.placeholder.width.toPx() }.toDouble()
                }.toFloat()
                val longest = maxOf(textWidth(raw), formulaWidth)
                widths[index] = maxOf(widths[index], (longest + with(density) { 24.dp.toPx() })
                    .coerceAtMost(with(density) { 320.dp.toPx() }))
            }
        }
        widths.map { with(density) { it.toDp() } }
    }
    if (exporting != null) exporting.tableMetrics.widths = columnWidths
    val totalWidth = columnWidths.fold(0.dp) { sum, width -> sum + width }
    val table: @Composable () -> Unit = {
        Column(Modifier.width(totalWidth)) {
            @Composable fun TableRow(row: ASTNode, heading: Boolean) {
                val cells = row.children.filter { it.type == CELL }
                Row {
                    columnWidths.forEachIndexed { index, width ->
                        Box(Modifier.width(width).padding(horizontal = 12.dp, vertical = 10.dp)) {
                            cells.getOrNull(index)?.let { cell ->
                                SearchMarkdownAnchor(MarkdownComponentModel(model.content, cell, model.typography)) {
                                    SearchableTableText(model.content, cell,
                                        if (heading) style.copy(fontWeight = FontWeight.Medium) else style)
                                }
                            }
                        }
                    }
                }
                MarkdownDivider()
            }
            if (exporting?.tableHeader != false) header?.let { TableRow(it, true) }
            rows.forEachIndexed { index, row ->
                if (exporting?.tableRows == null || index in exporting.tableRows) TableRow(row, false)
            }
        }
    }
    if (exporting != null) ExportFitWidth { table() }
    else Box(Modifier.fillMaxWidth().preferHorizontalScroll().horizontalScroll(rememberScrollState())) { table() }
}

/** Use the same AST annotator as the stock table, with the normal text layout/span callback. */
@Composable
private fun SearchableTableText(content: String, cell: ASTNode, style: TextStyle) {
    val raw = content.substring(cell.startOffset, cell.endOffset)
    val document = LocalInlineMathDocument.current
    val cellMath = remember(document, raw) { document?.forBlock(raw) }
    if (cellMath == null || cellMath.formulas.isEmpty()) {
        TableCellText(content, cell, style)
    } else {
        BoxWithConstraints {
            val (annotator, inline) = rememberInlineMath(cellMath, constraints.maxWidth, style.fontSize)
            CompositionLocalProvider(LocalMarkdownInlineContent provides inline, LocalMarkdownAnnotator provides annotator) {
                TableCellText(content, cell, style)
            }
        }
    }
}

@Composable
private fun TableCellText(content: String, cell: ASTNode, style: TextStyle) {
    val settings = annotatorSettings()
    val annotated = buildAnnotatedString {
        pushStyle(style.toSpanStyle())
        buildMarkdownAnnotatedString(content, cell, settings)
        pop()
    }
    LibraryMarkdownText(content = annotated, node = cell, style = style, sourceContent = content)
}

/**
 * 任务列表的复选框：自绘方框。
 *
 * 库的默认指示器是**等宽字体的文字** `[ ]` / `[x]` —— 与正文混排时像代码、不像勾选。
 * 这里画一个真正的方框：未勾选只有描边，勾选填主色并打勾。
 *
 * 横向位置与对齐由自绘列表的符号列给定（见 [MarkdownListLayout]），这里只画方框本身。
 */
private val taskCheckbox: MarkdownComponent = { model ->
    TaskCheckBox(checked = fenceTextOf(model).trim().contains('x', ignoreCase = true))
}

@Composable
private fun TaskCheckBox(checked: Boolean) {
    val colors = LocalChatColors.current
    val accent = MaterialTheme.colorScheme.primary
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            // 与正文首行**光学居中**：15dp 的方框是顶对齐塞进行框里的，
            // 而汉字的视觉中心比行框顶低 —— 实测差 3dp（density 3 下 9px）。
            .padding(top = 3.dp)
            .size(15.dp)
            .background(if (checked) accent else Color.Transparent, TaskBoxShape)
            .border(1.5.dp, if (checked) accent else colors.divider, TaskBoxShape),
    ) {
        if (checked) {
            Text("✓", color = Color.White, fontSize = 11.sp, lineHeight = 12.sp)
        }
    }
}

private val TaskBoxShape = RoundedCornerShape(4.dp)

private val collapsibleCodeFence: @Composable (MarkdownComponentModel) -> Unit = { model ->
    val fenceText = remember(model.content, model.node) { fenceTextOf(model) }
    val (language, code) = splitFence(fenceText)
    val lineCount = code.count { it == '\n' } + 1
    val collapsible = lineCount > CollapseLineThreshold

    /*
     * ```mermaid 不走代码块，走图表卡片。
     *
     * 判据是**语言标记**而不是去猜内容：猜错的下场是一段普通代码被执行成图，
     * 那比不渲染糟得多。
     */
    if (language.equals("mermaid", ignoreCase = true) && LocalMarkdownExport.current == null) {
        MermaidBlock(code = code.trim(), modifier = Modifier.fillMaxWidth())
    } else {
        CodeFenceCard(model = model, language = language, code = code, lineCount = lineCount, collapsible = collapsible)
    }
}

/** 代码块圆角：官方 12px。 */
private val CodeBlockCornerRadius = 12.dp

/** 普通代码块卡片（渲染 / 高亮 / 超长折叠）。 */
@Composable
private fun CodeFenceCard(
    model: MarkdownComponentModel,
    language: String?,
    code: String,
    lineCount: Int,
    collapsible: Boolean,
) {
    val exporting = LocalMarkdownExport.current != null
    // 内容一变（换版本 / 重新生成）就重新收起，免得上一条的展开状态串到这一条
    var expanded by remember(model.content) { mutableStateOf(false) }
    val search = LocalSearchHighlight.current
    LaunchedEffect(search?.request) {
        if (search != null && code.contains(search.query, ignoreCase = true)) expanded = true
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            /*
             * 官方的代码块是"浅灰底 + 12 圆角 + 顶部语言条"，底色与条同色，
             * 所以看上去只有一块圆角面板。我们用独立 token 而不是输入框那种
             * `fieldBackground` —— 后者深一档，整段代码会显得发灰、压页面。
             *
             * ⚠️ 不用 `clip`：代码块展开后可能很长，带裁剪的图形层会让下半部分点不动（§31）
             */
            .background(LocalChatColors.current.markdownCodeBlock, RoundedCornerShape(CodeBlockCornerRadius)),
    ) {
        if (!exporting) DisableSelection {
            CodeHeader(language = language.orEmpty(), code = code, lineCount = lineCount)
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (!exporting && collapsible && !expanded) Modifier.heightIn(max = CollapsedCodeHeight)
                    else Modifier,
                )
                .clipToBounds(),
        ) {
            /*
             * 围栏解析仍然交给库 —— `MarkdownCodeFence` 会做 `replaceIndent()`
             * （去掉公共缩进），与改造前逐字一致。换掉的只是**正文怎么渲染**：
             * 增量高亮 + 收起态只排版看得见的行，见 [CodeFenceText]。
             */
            MarkdownCodeFence(content = model.content, node = model.node) { fenceCode, fenceLanguage, style ->
                CodeFenceText(
                    code = fenceCode,
                    language = fenceLanguage,
                    style = style,
                    truncated = !exporting && collapsible && !expanded,
                )
            }
        }

        if (collapsible && !exporting) {
            DisableSelection {
                CodeExpandToggle(
                    lineCount = lineCount,
                    expanded = expanded,
                    onToggle = { expanded = !expanded },
                )
            }
        }
    }
}

/**
 * 从 AST 节点切出这一段围栏代码。
 *
 * 节点偏移是相对**整篇文档**的，所以用 `content.substring(...)` 一定对；
 * 各种偏移越界都钳一下（数据异常时宁可退化成空，也不要崩在渲染里）。
 */
private fun fenceTextOf(model: MarkdownComponentModel): String {
    val text = model.content
    val start = model.node.startOffset.coerceIn(0, text.length)
    val end = model.node.endOffset.coerceIn(start, text.length)
    return text.substring(start, end)
}

/** 语言标签 + 行数（左），复制（右）。 */
@Composable
private fun CodeHeader(language: String, code: String, lineCount: Int) {
    val colors = LocalChatColors.current
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 官方 banner：13/20 字号、左右 16、上 8
            .padding(start = 16.dp, end = 8.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = language.ifBlank { stringResource(R.string.md_code_label) },
            style = MaterialTheme.typography.bodySmall,
            color = colors.processText,
            maxLines = 1,
        )
        // 行数**总是**显示 —— 它同时是"这段有多长"和"折叠了多少"的答案
        Text(
            text = stringResource(R.string.md_line_count, lineCount),
            style = MaterialTheme.typography.bodySmall,
            color = colors.placeholder,
            maxLines = 1,
            modifier = Modifier
                .weight(1f)
                .padding(start = 6.dp),
        )
        CopyIconButton(28.dp, 14.dp, colors.processText, stringResource(R.string.md_copy_code)) {
            copyToClipboard(context, code)
        }
    }
}

/** 「展开全部」/「收起」。行数在顶栏里已经写着了，这里只在展开时留个"收起来"的出口。 */
@Composable
private fun CodeExpandToggle(
    lineCount: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val colors = LocalChatColors.current

    Spacer(Modifier.size(6.dp))
    Text(
        text = if (expanded) stringResource(R.string.md_collapse) else stringResource(R.string.md_expand_all, lineCount),
        style = MaterialTheme.typography.labelMedium,
        color = colors.processText,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = 8.dp),
    )
}

/**
 * 拆开围栏代码块，得到语言与正文。
 *
 * 刻意对「已去掉围栏」和「还带着围栏」两种输入都成立 ——
 * 库在不同版本里对 `MarkdownComponentModel.content` 的裁剪程度不一样，
 * 这里不做假设，谁来了都能处理。
 */
private fun splitFence(raw: String): Pair<String, String> {
    val lines = raw.trim('\n').lines()
    val first = lines.firstOrNull().orEmpty()

    if (!first.trimStart().startsWith("```")) return "" to raw.trim('\n')

    val language = first.trimStart().removePrefix("```").trim()
    val body = lines.drop(1).dropLastWhile { it.trimStart().startsWith("```") }
    return language to body.joinToString("\n")
}

/** 复制到系统剪贴板。用平台 API —— Compose 的剪贴板封装近年改过两轮签名。 */
private fun copyToClipboard(context: Context, text: String) {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    manager.setPrimaryClip(ClipData.newPlainText("code", text))
}

