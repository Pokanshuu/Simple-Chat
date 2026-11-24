package com.simplechat.app.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.input.pointer.pointerInput

import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.simplechat.app.R
import com.simplechat.app.data.FontSlot
import com.simplechat.app.data.ImportedFont
import com.simplechat.app.data.TypographyDraft
import com.simplechat.app.ui.common.AppIcons
import com.simplechat.app.ui.common.AppSlider
import com.simplechat.app.ui.common.AppMenu
import com.simplechat.app.ui.common.AppSwitch
import com.simplechat.app.ui.common.CapsuleButton
import com.simplechat.app.ui.common.PillShape
import com.simplechat.app.ui.common.Space
import com.simplechat.app.ui.theme.*
import kotlin.math.abs
import kotlin.math.roundToInt

internal fun TypographyDraft.fontId(slot: FontSlot): String? = when (slot) {
    FontSlot.USER -> userFontId
    FontSlot.ASSISTANT -> assistantFontId
    FontSlot.UI -> uiFontId
}

internal fun TypographyDraft.withFont(slot: FontSlot, id: String?): TypographyDraft = when (slot) {
    FontSlot.USER -> copy(userFontId = id)
    FontSlot.ASSISTANT -> copy(assistantFontId = id)
    FontSlot.UI -> copy(uiFontId = id)
}

internal enum class TextPane(val label: Int, val slot: FontSlot? = null) {
    SIZE(R.string.theme_text_size), USER(R.string.theme_text_user, FontSlot.USER),
    ASSISTANT(R.string.theme_text_assistant, FontSlot.ASSISTANT), UI(R.string.theme_text_ui, FontSlot.UI),
}

@Composable
internal fun ThemeTextSubtabs(pane: TextPane, onPaneChange: (TextPane) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
            .padding(horizontal = Space.Inline, vertical = Space.Gap),
        horizontalArrangement = Arrangement.spacedBy(Space.Gap, Alignment.CenterHorizontally),
    ) {
        TextPane.entries.forEach { item ->
            CapsuleButton(stringResource(item.label), onClick = { onPaneChange(item) }, selected = pane == item)
        }
    }
}

@Composable
internal fun ThemeTextEditor(
    draft: TypographyDraft,
    fonts: List<ImportedFont>,
    dark: Boolean,
    onChange: (TypographyDraft) -> Unit,
    onImportFont: (FontSlot) -> Unit,
    onManageFonts: () -> Unit,
    pane: TextPane,
    onPaneChange: (TextPane) -> Unit,
    modifier: Modifier = Modifier,
) {
    var advanced by rememberSaveable(pane) { mutableStateOf(false) }
    val slot = pane.slot
    Column(modifier.padding(vertical = Space.Gap), verticalArrangement = Arrangement.spacedBy(Space.Gap)) {
        if (slot == null) {
            val enabled = !draft.typography.hasFontSizeOverride
            TextSwitch(R.string.value_follow_system, draft.followSystemFont, enabled) {
                onChange(draft.copy(followSystemFont = it))
            }
            FontScaleSlider(draft.fontScale, enabled && !draft.followSystemFont) { onChange(draft.copy(fontScale = it)) }
            if (!enabled) Text(stringResource(R.string.theme_text_custom_size_hint), Modifier.padding(horizontal = Space.Screen),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            val category = draft.typography[slot]
            val selectedId = draft.fontId(slot)
            TypefacePicker(LocalContext.current, fonts, selectedId, { onChange(draft.withFont(slot, it)) },
                { onImportFont(slot) }, onManageFonts)
            fun update(updated: com.simplechat.app.data.CategoryTypographySettings) {
                onChange(draft.copy(typography = draft.typography.withSlot(slot, updated)))
            }
            if (slot == FontSlot.USER) {
                TextSwitch(R.string.theme_text_use_accent, category.useThemeColor, !category.customColorEnabled) {
                    update(category.copy(useThemeColor = it))
                }
            }
            // Same dampened accent the chat uses, so the swatch matches the rendered text.
            val accent = LocalAccentColor.current.primary(dark)
                .let { if (slot == FontSlot.USER) bubbleThemeTextColor(it) else it }
            val defaultColor = if (dark) TextPrimaryDark else TextPrimaryLight
            val initialColor = if (slot == FontSlot.USER && category.useThemeColor) accent else defaultColor
            val storedColor = if (dark) category.darkTextColorArgb else category.lightTextColorArgb
            fun setColor(argb: Int) = if (dark) category.copy(darkTextColorArgb = argb) else category.copy(lightTextColorArgb = argb)
            TextSwitch(R.string.theme_text_custom_color, category.customColorEnabled) { enabled ->
                val initialized = if (enabled && storedColor == null) setColor(initialColor.toArgb()) else category
                update(initialized.copy(customColorEnabled = enabled))
            }
            var colorMenu by remember(slot, dark, category.customColorEnabled) { mutableStateOf(false) }
            val colors = LocalChatColors.current
            Row(
                Modifier.fillMaxWidth().heightIn(min = Space.Row).padding(horizontal = Space.Screen),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.theme_text_color_value), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                val swatch = if (category.customColorEnabled) storedColor?.let(::Color) ?: initialColor else initialColor
                Box {
                    // A value swatch, not a control: keep it at the 28dp swatch size on the
                    // Space grid instead of a 40dp icon button.
                    Box(
                        Modifier.size(28.dp).clip(CircleShape)
                            .background(swatch)
                            .border(1.dp, colors.outline, CircleShape)
                            .clickable(enabled = category.customColorEnabled) { colorMenu = true },
                    )
                    AppMenu(expanded = colorMenu, onDismissRequest = { colorMenu = false }, alignEnd = true, width = 320.dp) {
                        Column(Modifier.padding(horizontal = Space.Gap, vertical = Space.Gap), verticalArrangement = Arrangement.spacedBy(Space.Gap)) {
                            ColorPicker(swatch) { selected ->
                                update(setColor(selected.toArgb()))
                                colorMenu = false
                            }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().clickable { advanced = !advanced }.heightIn(min = 48.dp)
                .padding(horizontal = Space.Screen), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.theme_advanced), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                Icon(if (advanced) AppIcons.ChevronUp else AppIcons.ChevronDown, null)
            }
            if (advanced) {
                val globalScale = if (draft.followSystemFont) 1f else draft.fontScale
                TextSwitch(R.string.theme_text_custom_size, category.overrideFontSizeEnabled) { enabled ->
                    update(category.copy(overrideFontSizeEnabled = enabled, fontScale = category.fontScale ?: globalScale))
                }
                FontScaleSlider(category.fontScale ?: globalScale, category.overrideFontSizeEnabled) {
                    update(category.copy(fontScale = it))
                }
            }
        }
    }
}

@Composable
private fun TextSwitch(label: Int, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = Space.Screen), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(label), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else LocalChatColors.current.placeholder)
        AppSwitch(checked, onChange, enabled = enabled)
    }
}

@Composable
private fun FontScaleSlider(scale: Float, enabled: Boolean, onChange: (Float) -> Unit) {
    val index = fontSizeSteps.indices.minBy { abs(fontSizeSteps[it].first - scale) }
    Column(Modifier.padding(horizontal = Space.Screen)) {
        Text(stringResource(fontSizeSteps[index].second), style = MaterialTheme.typography.labelMedium,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else LocalChatColors.current.placeholder)
        AppSlider(index.toFloat(), { onChange(fontSizeSteps[it.roundToInt().coerceIn(fontSizeSteps.indices)].first) },
            valueRange = 0f..fontSizeSteps.lastIndex.toFloat(), intervals = fontSizeSteps.lastIndex, enabled = enabled)
    }
}

internal fun hslToColor(hue: Float, saturation: Float, lightness: Float): Color =
    Color.hsl(hue.coerceIn(0f, 360f) % 360f, saturation.coerceIn(0f, 1f), lightness.coerceIn(0f, 1f))

@Composable
private fun ColorPicker(color: Color, onConfirm: (Color) -> Unit) {
    val hsv = remember(color) { FloatArray(3).also { android.graphics.Color.colorToHSV(color.toArgb(), it) } }
    var hue by remember(color) { mutableFloatStateOf(hsv[0]) }
    var saturation by remember(color) { mutableFloatStateOf(hsv[1]) }
    var value by remember(color) { mutableFloatStateOf(hsv[2]) }
    val selected = Color.hsv(hue % 360f, saturation, value)
    val spectrum = remember { (0..6).map { Color.hsv(it * 60f % 360f, 1f, 1f) } }
    Column(Modifier.padding(horizontal = Space.Gap), verticalArrangement = Arrangement.spacedBy(Space.Gap)) {
        Canvas(Modifier.fillMaxWidth().height(136.dp).clip(RoundedCornerShape(8.dp)).pickPosition { x, y ->
            saturation = x
            value = 1f - y
        }) {
            drawRect(Brush.horizontalGradient(listOf(Color.White, Color.hsv(hue % 360f, 1f, 1f))))
            drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
            val inset = 8.dp.toPx().coerceAtMost(size.minDimension / 2f)
            val point = Offset(inset + (size.width - 2 * inset) * saturation, inset + (size.height - 2 * inset) * (1f - value))
            drawCircle(Color.Black, 7.dp.toPx(), point, style = Stroke(3.dp.toPx()))
            drawCircle(Color.White, 7.dp.toPx(), point, style = Stroke(1.5.dp.toPx()))
        }
        Canvas(Modifier.fillMaxWidth().height(28.dp).clip(PillShape).pickPosition { x, _ -> hue = x * 360f }) {
            drawRect(Brush.horizontalGradient(spectrum))
            val inset = 8.dp.toPx().coerceAtMost(size.width / 2f)
            val point = Offset(inset + (size.width - 2 * inset) * hue / 360f, center.y)
            drawCircle(Color.Black, 7.dp.toPx(), point, style = Stroke(3.dp.toPx()))
            drawCircle(Color.White, 7.dp.toPx(), point, style = Stroke(1.5.dp.toPx()))
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.Gap)) {
            Box(Modifier.size(22.dp).background(selected, PillShape))
            Text("#${selected.toArgb().toUInt().toString(16).uppercase().takeLast(6)}", Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium)
            CapsuleButton(stringResource(R.string.action_save), onClick = { onConfirm(selected) }, selected = true)
        }
    }
}

@Composable
private fun Modifier.pickPosition(onChange: (Float, Float) -> Unit): Modifier {
    val update by rememberUpdatedState(onChange)
    return pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown()
            val inset = 8.dp.toPx().coerceAtMost(minOf(size.width, size.height) / 2f)
            fun select(point: Offset) = update(
                ((point.x - inset) / (size.width - 2 * inset).coerceAtLeast(1f)).coerceIn(0f, 1f),
                ((point.y - inset) / (size.height - 2 * inset).coerceAtLeast(1f)).coerceIn(0f, 1f),
            )
            down.consume()
            select(down.position)
            while (true) {
                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break
                select(change.position)
                change.consume()
            }
        }
    }
}
