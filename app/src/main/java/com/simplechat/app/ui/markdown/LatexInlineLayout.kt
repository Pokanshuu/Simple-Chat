package com.simplechat.app.ui.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.wrapContentSize
import com.simplechat.app.ui.chat.preferHorizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.constrainHeight
import com.hrm.latex.renderer.measure.LatexMeasurerState
import com.hrm.latex.renderer.model.LatexConfig
import kotlin.math.ceil

internal fun LatexMeasurerState.inlinePresentation(
    presentation: LatexPresentation,
    config: LatexConfig,
    dark: Boolean,
    color: Color,
    density: Density,
): InlineTextContent? {
    presentation.nativeTex?.let { tex ->
        val content = inlineContent(tex, config, dark) ?: return null
        return content.takeIf { it.hasValidFormulaSize(density) }
    }
    if (presentation.ovalParts.isEmpty()) return null
    val parts = presentation.ovalParts.map { part ->
        val frame = part.frame
        fun LatexLength.pixels() = with(density) {
            when (unit) {
                "em" -> value * config.fontSize.toPx()
                "ex" -> value * config.fontSize.toPx() * 0.5f
                "pt" -> (value * 96f / 72f).dp.toPx()
                "cm" -> (value * 96f / 2.54f).dp.toPx()
                "mm" -> (value * 96f / 25.4f).dp.toPx()
                "in" -> (value * 96f).dp.toPx()
                else -> value.dp.toPx()
            }
        }
        val background = frame?.background?.let { latexBoxColor(it, color) ?: return null }
        val foreground = background?.let { if (it.luminance() > 0.45f) Color.Black else Color.White } ?: color
        val partConfig = if (background == null) config else config.copy(theme =
            if (dark) com.hrm.latex.renderer.model.LatexTheme.dark(color = foreground)
            else com.hrm.latex.renderer.model.LatexTheme.light(color = foreground))
        val content = inlineContent(part.tex, partConfig, dark) ?: return null
        val dimensions = measure(part.tex, partConfig, dark) ?: return null
        if (!dimensions.widthPx.isFinite() || !dimensions.heightPx.isFinite() || !dimensions.baselinePx.isFinite() ||
            dimensions.widthPx > MaxFormulaDimension || dimensions.heightPx > MaxFormulaDimension) return null
        val padding = frame?.padding?.pixels()?.let { with(density) { it.toDp() } }
        val horizontal = padding ?: if (part.outline != null) 10.dp else if (frame != null) 3.dp else 0.dp
        val vertical = padding ?: if (part.outline != null) 6.dp else if (frame != null) 3.dp else 0.dp
        val paddingX = with(density) { horizontal.roundToPx() }
        val paddingY = with(density) { vertical.roundToPx() }
        val naturalHeight = ceil(dimensions.heightPx).toInt() + 2 * paddingY
        val width = maxOf(ceil(dimensions.widthPx).toInt() + 2 * paddingX, frame?.width?.pixels()?.let { ceil(it).toInt() } ?: 0)
        val height = maxOf(naturalHeight, frame?.height?.pixels()?.let { ceil(it).toInt() } ?: 0)
        val radius = frame?.radius?.pixels()?.let { with(density) { it.toDp() } }
        val shape = when {
            part.outline != null -> CircleShape
            radius != null -> RoundedCornerShape(radius)
            else -> RectangleShape
        }
        val border = when (part.outline) {
            OvalOutline.THICK -> 2.dp
            OvalOutline.THIN -> 1.dp
            null -> frame?.border?.pixels()?.let { with(density) { it.toDp() } } ?: 0.dp
        }
        val borderColor = frame?.borderColor?.let { latexBoxColor(it, color) ?: return null } ?: color
        val alignment = when (frame?.alignment) {
            "l" -> Alignment.CenterStart
            "r" -> Alignment.CenterEnd
            else -> Alignment.Center
        }
        MeasuredPart(content, width.coerceAtLeast(1), height.coerceAtLeast(1),
            ceil(dimensions.baselinePx).toInt() + paddingY + (height - naturalHeight) / 2,
            horizontal, vertical, shape, border, borderColor, background, alignment)
    }
    // Align the text and small labels on the math baseline, keeping the box padding on both sides.
    val top = parts.maxOf { it.baseline }
    val width = parts.sumOf { it.width }.coerceAtLeast(1)
    val height = top + parts.maxOf { it.height - it.baseline }
    if (width > MaxFormulaDimension || height > MaxFormulaDimension) return null
    val placeholder = with(density) {
        Placeholder(width.toSp(), height.coerceAtLeast(1).toSp(), PlaceholderVerticalAlign.TextCenter)
    }
    return InlineTextContent(placeholder) {
        Layout(content = {
            for (part in parts) {
                var modifier: Modifier = Modifier
                part.background?.let { modifier = modifier.background(it, part.shape) }
                if (part.border > 0.dp) modifier = modifier.border(part.border, part.borderColor, part.shape)
                Box(modifier.padding(horizontal = part.paddingX, vertical = part.paddingY), contentAlignment = part.alignment) {
                    part.content.children("")
                }
            }
        }) { children, constraints ->
            val measured = children.mapIndexed { index, child ->
                val part = parts[index]
                child.measure(Constraints.fixed(part.width, part.height))
            }
            layout(constraints.constrainWidth(width), constraints.constrainHeight(height)) {
                var x = 0
                measured.forEachIndexed { index, child ->
                    val part = parts[index]
                    child.place(x, top - part.baseline)
                    x += part.width
                }
            }
        }
    }
}

private data class MeasuredPart(
    val content: InlineTextContent,
    val width: Int,
    val height: Int,
    val baseline: Int,
    val paddingX: Dp,
    val paddingY: Dp,
    val shape: androidx.compose.ui.graphics.Shape,
    val border: Dp,
    val borderColor: Color,
    val background: Color?,
    val alignment: Alignment,
)

@Composable
internal fun FormulaInlineText(inline: InlineTextContent, source: String, style: TextStyle, modifier: Modifier = Modifier) {
    // A Compose placeholder must fit in one paragraph, even when its drawing has several rows.
    val alternate = androidx.compose.runtime.remember(source) { source.lineSequence().joinToString(" ") }
    BasicText(
        text = buildAnnotatedString { appendInlineContent("math", alternate.ifEmpty { "formula" }) },
        inlineContent = mapOf("math" to inline),
        style = style,
        modifier = modifier,
    )
}

private const val MaxFormulaDimension = 8_192
private fun InlineTextContent.hasValidFormulaSize(density: Density): Boolean = with(density) {
    placeholder.width.toPx() in 0f..MaxFormulaDimension.toFloat() &&
        placeholder.height.toPx() in 0f..MaxFormulaDimension.toFloat()
}

/** A formula wider than its paragraph gets a bounded viewport at natural size in chat. */
internal fun InlineTextContent.fitWidth(maxWidth: Int, density: Density, exporting: Boolean): InlineTextContent {
    if (maxWidth <= 0 || maxWidth == Constraints.Infinity) return this
    val width = with(density) { ceil(placeholder.width.toPx()).toInt().coerceAtLeast(1) }
    val height = with(density) { ceil(placeholder.height.toPx()).toInt().coerceAtLeast(1) }
    if (width <= maxWidth) return this
    val slotWidth = (maxWidth - 1).coerceAtLeast(1)
    val scale = if (exporting) slotWidth.toFloat() / width else 1f
    val slotHeight = ceil(height * scale).toInt().coerceAtLeast(1)
    val slot = with(density) { Placeholder(slotWidth.toSp(), slotHeight.toSp(), placeholder.placeholderVerticalAlign) }
    return InlineTextContent(slot) { text ->
        if (exporting) {
            Layout(content = { Box { children(text) } }) { children, constraints ->
                val child = children.single().measure(Constraints.fixed(width, height))
                layout(constraints.constrainWidth(slotWidth), constraints.constrainHeight(slotHeight)) {
                    child.placeWithLayer(0, 0) {
                        transformOrigin = TransformOrigin(0f, 0f)
                        scaleX = scale
                        scaleY = scale
                    }
                }
            }
        } else {
            Box(Modifier.preferHorizontalScroll().horizontalScroll(rememberScrollState())) {
                val naturalWidth = with(density) { width.toDp() }
                val naturalHeight = with(density) { height.toDp() }
                Box(Modifier.requiredSize(naturalWidth, naturalHeight).wrapContentSize(Alignment.TopStart)) {
                    children(text)
                }
            }
        }
    }
}

private fun latexBoxColor(value: String, foreground: Color): Color? {
    if (value.equals("currentColor", ignoreCase = true)) return foreground
    val color = when (value.lowercase()) {
        "lightblue" -> "#add8e6"
        "pink" -> "#ffc0cb"
        "purple" -> "#800080"
        "orange" -> "#ffa500"
        "violet" -> "#ee82ee"
        "transparent" -> return Color.Transparent
        else -> if (value.startsWith('#') && value.length == 4) "#" + value.drop(1).map { "$it$it" }.joinToString("") else value
    }
    return runCatching { Color(android.graphics.Color.parseColor(color)) }.getOrNull()
}
