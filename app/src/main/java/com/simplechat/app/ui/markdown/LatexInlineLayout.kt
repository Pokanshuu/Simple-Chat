package com.simplechat.app.ui.markdown

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
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
    presentation.nativeTex?.let { return inlineContent(it, config, dark) }
    if (presentation.ovalParts.isEmpty()) return null
    val horizontalPadding = with(density) { 10.dp.roundToPx() }
    val verticalPadding = with(density) { 6.dp.roundToPx() }
    val parts = presentation.ovalParts.map { part ->
        val content = inlineContent(part.tex, config, dark) ?: return null
        val dimensions = measure(part.tex, config, dark) ?: return null
        val horizontal = if (part.outline != null) horizontalPadding else 0
        val vertical = if (part.outline != null) verticalPadding else 0
        MeasuredPart(
            content,
            (ceil(dimensions.widthPx).toInt() + 2 * horizontal).coerceAtLeast(1),
            (ceil(dimensions.heightPx).toInt() + 2 * vertical).coerceAtLeast(1),
            ceil(dimensions.baselinePx).toInt() + vertical,
            part.outline,
        )
    }
    // Align the text and small labels on the math baseline, keeping the box padding on both sides.
    val top = parts.maxOf { it.baseline }
    val width = parts.sumOf { it.width }.coerceAtLeast(1)
    val height = top + parts.maxOf { it.height - it.baseline }
    val placeholder = with(density) {
        Placeholder(width.toSp(), height.coerceAtLeast(1).toSp(), PlaceholderVerticalAlign.TextCenter)
    }
    return InlineTextContent(placeholder) {
        Layout(content = {
            for (part in parts) {
                val outline = part.outline
                val modifier = if (outline == null) Modifier else Modifier
                    .border(if (outline == OvalOutline.THICK) 2.dp else 1.dp, color, CircleShape)
                    .padding(horizontal = 10.dp, vertical = 6.dp)
                Box(modifier) {
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
    val outline: OvalOutline?,
)

@Composable
internal fun FormulaInlineText(inline: InlineTextContent, source: String, style: TextStyle, modifier: Modifier = Modifier) {
    BasicText(
        text = buildAnnotatedString { appendInlineContent("math", source.ifEmpty { "formula" }) },
        inlineContent = mapOf("math" to inline),
        style = style,
        modifier = modifier,
    )
}

/** An inline placeholder wider than its paragraph can disappear entirely. Scale the drawing with its slot. */
internal fun InlineTextContent.fitWidth(maxWidth: Int, density: Density): InlineTextContent? {
    if (maxWidth == Constraints.Infinity) return this
    val width = with(density) { ceil(placeholder.width.toPx()).toInt() }
    if (width <= maxWidth) return this
    val height = with(density) { ceil(placeholder.height.toPx()).toInt() }.coerceAtLeast(1)
    // Extremely large native layouts exceed Compose's representable child constraints.
    if (maxWidth <= 0 || width > 16_384 || height > 16_384) return null
    val scale = maxWidth.toFloat() / width
    val scaledHeight = ceil(height * scale).toInt().coerceAtLeast(1)
    val slot = with(density) { Placeholder((maxWidth - 0.5f).toSp(), scaledHeight.toSp(), placeholder.placeholderVerticalAlign) }
    return InlineTextContent(slot) { text ->
        Layout(content = { Box { children(text) } }) { children, constraints ->
            val child = children.single().measure(Constraints.fixed(width, height))
            layout(constraints.constrainWidth(maxWidth), constraints.constrainHeight(scaledHeight)) {
                child.placeWithLayer(0, 0) {
                    transformOrigin = TransformOrigin(0f, 0f)
                    scaleX = scale
                    scaleY = scale
                }
            }
        }
    }
}
