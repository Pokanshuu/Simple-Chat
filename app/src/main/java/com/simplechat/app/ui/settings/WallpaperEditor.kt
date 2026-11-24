package com.simplechat.app.ui.settings

import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import com.simplechat.app.ui.theme.sharedSceneBackground
import com.simplechat.app.ui.theme.LocalBubbleBackdrop
import com.simplechat.app.ui.theme.rememberBubbleBackdrop
import com.simplechat.app.ui.theme.needsLiveSurface
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.material3.Checkbox
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.simplechat.app.data.InterfaceControl
import com.simplechat.app.ui.common.screenTransform
import com.simplechat.app.ui.theme.LocalAcrylicEnabled
import com.simplechat.app.ui.theme.LocalControlEffects
import android.graphics.Bitmap
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.simplechat.app.R
import com.simplechat.app.data.MaxPanelAccentOpacity
import com.simplechat.app.data.MaxWallpaperBlur
import com.simplechat.app.data.PanelAppearance
import com.simplechat.app.ui.common.AppIcons
import com.simplechat.app.ui.common.AppMenu
import com.simplechat.app.ui.common.AppMenuItem
import com.simplechat.app.ui.common.AppSlider
import com.simplechat.app.ui.common.IconCircleButton
import com.simplechat.app.ui.common.PillShape
import com.simplechat.app.ui.common.SoftShape
import com.simplechat.app.ui.common.Space
import com.simplechat.app.ui.theme.LocalChatColors
import com.simplechat.app.ui.theme.LocalAccentColor
import com.simplechat.app.ui.theme.themePageBackground
import com.simplechat.app.ui.theme.WallpaperState
import com.simplechat.app.ui.theme.WallpaperImage
import com.simplechat.app.ui.theme.LocalFloatingBaseOpacity
import com.simplechat.app.ui.theme.LocalFloatingGlass
import com.simplechat.app.ui.theme.LocalPanelAccentOpacity
import com.simplechat.app.ui.theme.LocalIsDarkTheme
import com.simplechat.app.ui.theme.MenuBackdropHost
import com.simplechat.app.ui.theme.drawWallpaper
import com.simplechat.app.ui.theme.drawWallpaperImage
import com.simplechat.app.ui.theme.rememberWallpaperBitmap
import com.simplechat.app.ui.theme.rememberLiveBlurAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import kotlin.math.max

internal data class WallpaperEdit(
    val baseOpacity: Float,
    val dim: Float,
    val blur: Float,
    val brightness: Float,
    val contrast: Float,
    val saturation: Float,
    val flipH: Boolean,
)

internal enum class EditTab { CROP, ADJUST, TEXT, EFFECTS }
private enum class Tool { OPACITY, DIM, BLUR, BRIGHTNESS, CONTRAST, SATURATION }

/** 沿用壁纸编辑器的画幅和工具栏，所有分类共用这一块聊天预览。 */
@Composable
internal fun ThemeEditorLayout(
    source: Bitmap?,
    savedCrop: Rect?,
    onPrepareCrop: () -> Unit,
    isNewImage: Boolean,
    imageAction: Int,
    currentWallpaper: WallpaperState,
    tab: EditTab,
    onTabChange: (EditTab) -> Unit,
    textPane: TextPane,
    onTextPaneChange: (TextPane) -> Unit,
    initial: WallpaperEdit,
    initialPanels: PanelAppearance,
    showInterfaceTab: Boolean = true,
    saving: Boolean,
    onDismiss: () -> Unit,
    onPick: () -> Unit,
    onRemove: () -> Unit,
    textEditor: (@Composable (Modifier) -> Unit)? = null,
    previewTheme: @Composable (@Composable () -> Unit) -> Unit = { it() },
    previewDark: Boolean = false,
    onPreviewDarkChange: (Boolean) -> Unit = {},
    onSave: (Rect?, WallpaperEdit, PanelAppearance) -> Unit,
) {
    val colors = LocalChatColors.current
    val window = LocalWindowInfo.current.containerSize
    val screenRatio = if (window.height > 0) window.width.toFloat() / window.height else 9f / 19.5f
    val hasWallpaper = isNewImage || currentWallpaper.active
    val currentImage = rememberWallpaperBitmap(currentWallpaper)
    var edit by remember { mutableStateOf(initial) }
    var panels by remember { mutableStateOf(initialPanels) }
    LaunchedEffect(imageAction, isNewImage, hasWallpaper) { if (isNewImage || !hasWallpaper) onTabChange(EditTab.CROP) }
    var tool by remember { mutableStateOf(Tool.OPACITY) }
    var comparing by remember { mutableStateOf(false) }
    var zoom by remember(imageAction) { mutableFloatStateOf(1f) }
    // 以画幅宽度为单位，切换工具或旋转时仍保持裁切位置。
    var pan by remember(imageAction) { mutableStateOf(Offset.Zero) }
    var cropChanged by remember(imageAction) { mutableStateOf(isNewImage) }
    var prepareCrop by remember(imageAction) { mutableStateOf(isNewImage) }
    LaunchedEffect(tab, imageAction) {
        if (tab == EditTab.CROP) {
            prepareCrop = true
            onPrepareCrop()
        }
    }
    val sourceImage by androidx.compose.runtime.key(source, prepareCrop) {
        produceState<WallpaperImage?>(null) {
            if (prepareCrop && source != null) {
                value = withContext(Dispatchers.Default) { WallpaperImage(source) { ensureActive() } }
            }
        }
    }
    var frameSize by remember { mutableStateOf(Size.Zero) }
    var wallpaperMenu by remember { mutableStateOf(false) }
    val liveBlur = rememberLiveBlurAvailable()
    val cropActive = tab == EditTab.CROP && sourceImage != null
    // The saved preview remains visible while the source loads and until an actual edit.
    val useCrop = cropChanged
    val image = if (useCrop) sourceImage else currentImage
    val imageW = (source?.width ?: 1).toFloat()
    val imageH = (source?.height ?: 1).toFloat()

    fun coverScale(frame: Size) = max(frame.width / imageW, frame.height / imageH)
    fun clampPan(p: Offset, z: Float, frame: Size): Offset {
        val scale = coverScale(frame) * z
        val maxX = ((imageW * scale - frame.width) / 2f).coerceAtLeast(0f)
        val maxY = ((imageH * scale - frame.height) / 2f).coerceAtLeast(0f)
        return Offset(p.x.coerceIn(-maxX, maxX), p.y.coerceIn(-maxY, maxY))
    }
    fun restoreCrop(frame: Size) {
        if (cropChanged || savedCrop == null) return
        val scale = max(frame.width / savedCrop.width, frame.height / savedCrop.height)
        zoom = (scale / coverScale(frame)).coerceAtLeast(1f)
        val sourceCenter = Offset(imageW / 2f, imageH / 2f)
        pan = clampPan((sourceCenter - savedCrop.center) * scale, zoom, frame) / frame.width
    }
    fun cropRect(frame: Size): Rect {
        val offset = clampPan(pan * frame.width, zoom, frame)
        val scale = coverScale(frame) * zoom
        val w = frame.width / scale
        val h = frame.height / scale
        val left = (imageW / 2f - offset.x / scale - w / 2f).coerceIn(0f, (imageW - w).coerceAtLeast(0f))
        val top = (imageH / 2f - offset.y / scale - h / 2f).coerceIn(0f, (imageH - h).coerceAtLeast(0f))
        return Rect(left, top, (left + w).coerceAtMost(imageW), (top + h).coerceAtMost(imageH))
    }
    fun resetCrop() {
        zoom = 1f
        pan = Offset.Zero
        cropChanged = true
    }

    Dialog(
        onDismissRequest = { if (!saving) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        CompositionLocalProvider(
            LocalControlEffects provides panels.controlEffects,
            LocalAcrylicEnabled provides panels.acrylicEnabled,
            LocalFloatingGlass provides panels.floatingGlass,
            LocalFloatingBaseOpacity provides panels.opacity,
            LocalPanelAccentOpacity provides if (panels.accentEnabled) panels.accentOpacity else 0f,
        ) {
            MenuBackdropHost {
                BoxWithConstraints(
                    Modifier.fillMaxSize().background(colors.settingsBackground)
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                        .statusBarsPadding().navigationBarsPadding(),
                ) {
                    val sideBySide = maxWidth >= 680.dp || (maxWidth >= 580.dp && maxWidth > maxHeight)
                    val toolsWidth = minOf(360.dp, maxWidth * 0.48f)
                    // Panels scroll past this cap; 220dp cut the text tools off far too early.
                    val effectsHeight = minOf(360.dp, maxHeight * 0.5f)
                    Column(Modifier.fillMaxSize()) {
                        val canSave = !saving && (!cropChanged || (source != null && frameSize.width > 0f && frameSize.height > 0f))
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = Space.Inline, vertical = Space.Gap),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconCircleButton(AppIcons.Close, stringResource(R.string.action_cancel), onDismiss, enabled = !saving)
                            Text(
                                stringResource(if (showInterfaceTab) R.string.wallpaper_editor_title else R.string.conversation_wallpaper_title),
                                style = MaterialTheme.typography.titleMedium,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.weight(1f),
                            )
                            Box(
                                Modifier.clip(PillShape)
                                    .background(if (canSave) MaterialTheme.colorScheme.primary else colors.card)
                                    .clickable(enabled = canSave) { onSave(if (cropChanged) cropRect(frameSize) else null, edit, panels) }
                                    .padding(horizontal = 18.dp, vertical = 8.dp),
                            ) {
                                Text(
                                    stringResource(R.string.action_save),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = if (canSave) MaterialTheme.colorScheme.onPrimary else colors.placeholder,
                                )
                            }
                        }

                        val preview: @Composable (Modifier) -> Unit = { modifier ->
                            BoxWithConstraints(
                                modifier.fillMaxSize().padding(horizontal = Space.Screen, vertical = Space.Gap),
                                contentAlignment = if (sideBySide) Alignment.Center else Alignment.BottomCenter,
                            ) {
                                val density = LocalDensity.current
                                val frameWidth = minOf(maxWidth, maxHeight * screenRatio)
                                val frameHeight = frameWidth / screenRatio
                                val frame = with(density) { Size(frameWidth.toPx(), frameHeight.toPx()) }
                                val previewBackground = themePageBackground(previewDark, LocalAccentColor.current)
                                androidx.compose.runtime.SideEffect { frameSize = frame }
                                val drawBackground: DrawScope.() -> Unit = {
                                    drawRect(previewBackground)
                                    if (image != null && size.width > 0f && size.height > 0f) {
                                        val state = if (comparing) currentWallpaper.copy(opacity = 1f, brightness = 1f, contrast = 1f, saturation = 1f, blur = 0f, dim = 0f)
                                        else currentWallpaper.copy(
                                            opacity = 1f - edit.baseOpacity, blur = edit.blur, brightness = edit.brightness,
                                            contrast = edit.contrast, saturation = edit.saturation,
                                            dim = edit.dim, flipH = edit.flipH,
                                        )
                                        if (useCrop) {
                                            val drawFrame = size
                                            val offset = clampPan(pan * drawFrame.width, zoom, drawFrame)
                                            val scale = coverScale(drawFrame) * zoom
                                            val w = imageW * scale
                                            val h = imageH * scale
                                            val left = (size.width - w) / 2f + offset.x
                                            val top = (size.height - h) / 2f + offset.y
                                            drawWallpaperImage(image, state, RectF(left, top, left + w, top + h), previewBackground, comparing)
                                            if (state.dim > 0f) drawRect(Color.Black.copy(alpha = state.dim))
                                        } else {
                                            drawWallpaper(image, state, previewBackground, comparing)
                                        }
                                    }
                                }
                                val previewLayer = if (liveBlur && needsLiveSurface(InterfaceControl.INPUT, InterfaceControl.SCROLL_BUTTON, InterfaceControl.USER_BUBBLE)) rememberGraphicsLayer() else null
                                val bubbleBackdrop = rememberBubbleBackdrop(previewLayer)
                                Box(
                                    Modifier.size(frameWidth, frameHeight).clip(SoftShape).sharedSceneBackground(previewLayer, bubbleBackdrop = bubbleBackdrop, draw = drawBackground)
                                        .pointerInput(cropActive, savedCrop, frame, imageAction) {
                                            if (!cropActive || frame.width <= 0f || frame.height <= 0f) return@pointerInput
                                            detectTransformGestures { centroid, translation, zoomChange, _ ->
                                                restoreCrop(frame)
                                                val newZoom = (zoom * zoomChange).coerceIn(1f, maxOf(5f, zoom))
                                                val pivot = centroid - Offset(frame.width / 2f, frame.height / 2f)
                                                val signedPivot = if (edit.flipH) Offset(-pivot.x, pivot.y) else pivot
                                                val signedTranslation = if (edit.flipH) Offset(-translation.x, translation.y) else translation
                                                val next = signedPivot + (pan * frame.width - signedPivot) * (newZoom / zoom) + signedTranslation
                                                pan = clampPan(next, newZoom, frame) / frame.width
                                                zoom = newZoom
                                                cropChanged = true
                                            }
                                        }.pointerInput(cropActive, imageAction) {
                                            if (cropActive) detectTapGestures(onDoubleTap = { resetCrop() })
                                        },
                                ) {
                                    if (!comparing && frame.width > 0f) {
                                        val shrink = (frame.width / window.width.coerceAtLeast(1)).coerceIn(0.1f, 1f)
                                        CompositionLocalProvider(
                                            LocalDensity provides Density(density.density * shrink, density.fontScale),
                                            LocalBubbleBackdrop provides bubbleBackdrop,
                                            LocalIsDarkTheme provides previewDark,
                                        ) {
                                            previewTheme {
                                                ThemeChatSample({ previewLayer?.let { drawLayer(it) } }, previewLayer != null, allowScroll = tab != EditTab.CROP)
                                            }
                                        }
                                        if (cropActive) CropGuides()
                                    }
                                }
                            }
                        }

                        val toolContent: @Composable (Modifier) -> Unit = { modifier ->
                            AnimatedContent(
                                modifier = modifier.fillMaxWidth(),
                                targetState = tab,
                                transitionSpec = { screenTransform(targetState.ordinal > initialState.ordinal) },
                                label = "themeTools",
                            ) { displayedTab ->
                                Column {
                                    when (displayedTab) {
                                        EditTab.EFFECTS -> Column(
                                            Modifier.fillMaxWidth().then(
                                                if (sideBySide) Modifier else Modifier.heightIn(max = effectsHeight).verticalScroll(rememberScrollState()),
                                            ),
                                        ) {
                                            PanelControls(panels, liveBlur) { panels = it }
                                        }
                                        EditTab.TEXT -> textEditor?.invoke(
                                            Modifier.fillMaxWidth().then(
                                                if (sideBySide) Modifier else Modifier.heightIn(max = effectsHeight).verticalScroll(rememberScrollState()),
                                            ),
                                        )
                                        EditTab.CROP -> {
                                            FlowRow(
                                                Modifier.fillMaxWidth().padding(horizontal = Space.Screen, vertical = Space.Gap),
                                                horizontalArrangement = if (hasWallpaper) Arrangement.spacedBy(Space.Gap, Alignment.End) else Arrangement.Center,
                                                verticalArrangement = Arrangement.spacedBy(Space.Gap),
                                            ) {
                                                EditorPill(stringResource(if (hasWallpaper) R.string.wallpaper_change else R.string.wallpaper_pick), onClick = onPick)
                                                if (hasWallpaper) {
                                                    if (!sideBySide) Spacer(Modifier.weight(1f))
                                                    val scheme = MaterialTheme.colorScheme
                                                    IconCircleButton(
                                                        AppIcons.FlipHorizontal, stringResource(R.string.wallpaper_flip_h),
                                                        { edit = edit.copy(flipH = !edit.flipH) },
                                                        background = if (edit.flipH) scheme.primary else colors.card,
                                                        tint = if (edit.flipH) scheme.onPrimary else scheme.onSurface,
                                                    )
                                                    IconCircleButton(AppIcons.Refresh, stringResource(R.string.wallpaper_editor_reset_crop), ::resetCrop, enabled = cropActive, background = colors.card)
                                                    Box {
                                                        IconCircleButton(AppIcons.More, stringResource(R.string.theme_wallpaper_options), { wallpaperMenu = true })
                                                        AppMenu(wallpaperMenu, { wallpaperMenu = false }) {
                                                            AppMenuItem(stringResource(if (showInterfaceTab) R.string.wallpaper_remove else R.string.conversation_wallpaper_use_global), destructive = true) {
                                                                wallpaperMenu = false
                                                                onRemove()
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                        EditTab.ADJUST -> if (hasWallpaper) {
                                            Row(
                                                Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = Space.Screen),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                Box(Modifier.weight(1f)) { ToolSlider(tool, edit) { edit = it } }
                                                Spacer(Modifier.size(Space.Inline))
                                                CompareButton { comparing = it }
                                            }
                                            FlowRow(
                                                Modifier.fillMaxWidth().then(if (sideBySide) Modifier else Modifier.horizontalScroll(rememberScrollState()))
                                                    .padding(horizontal = Space.Screen, vertical = Space.Gap),
                                                horizontalArrangement = Arrangement.spacedBy(Space.Gap),
                                                verticalArrangement = Arrangement.spacedBy(Space.Gap),
                                            ) {
                                                Tool.entries.forEach { item ->
                                                    ToolTile(stringResource(item.label()), item.display(edit), tool == item) { tool = item }
                                                }
                                            }
                                        } else {
                                            Box(Modifier.fillMaxWidth().padding(vertical = Space.Gap), contentAlignment = Alignment.Center) {
                                                EditorPill(stringResource(R.string.wallpaper_pick), onClick = onPick)
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        val tabs: @Composable () -> Unit = {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = Space.Inline, vertical = Space.Gap),
                                horizontalArrangement = Arrangement.spacedBy(Space.Gap),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Row(
                                    Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(Space.Gap),
                                ) {
                                    listOf(EditTab.CROP, EditTab.ADJUST, EditTab.TEXT, EditTab.EFFECTS)
                                        .filter { showInterfaceTab || it != EditTab.EFFECTS && it != EditTab.TEXT }
                                        .forEach { item ->
                                        val label = when (item) {
                                            EditTab.CROP -> R.string.wallpaper_editor_tab_crop
                                            EditTab.ADJUST -> R.string.wallpaper_editor_tab_adjust
                                            EditTab.EFFECTS -> R.string.theme_glass_and_panels
                                            EditTab.TEXT -> R.string.theme_text_tab
                                        }
                                        EditorPill(stringResource(label), selected = item == tab) { onTabChange(item) }
                                    }
                                }
                                if (showInterfaceTab && textEditor != null) {
                                    IconCircleButton(
                                        icon = if (previewDark) AppIcons.Sun else AppIcons.Moon,
                                        contentDescription = stringResource(if (previewDark) R.string.theme_text_switch_to_light else R.string.theme_text_switch_to_dark),
                                        onClick = { onPreviewDarkChange(!previewDark) },
                                        size = 40.dp,
                                        iconSize = 20.dp,
                                        outlined = true,
                                    )
                                }
                            }
                        }

                        if (sideBySide) {
                            Row(Modifier.weight(1f).fillMaxWidth()) {
                                preview(Modifier.weight(1f).fillMaxHeight())
                                Column(Modifier.width(toolsWidth).fillMaxHeight().padding(end = Space.Inline)) {
                                    toolContent(Modifier.weight(1f).verticalScroll(rememberScrollState()))
                                    if (tab == EditTab.TEXT) ThemeTextSubtabs(textPane, onTextPaneChange)
                                    tabs()
                                }
                            }
                        } else {
                            // Content keeps its natural height; the preview absorbs the rest.
                            // Giving it weight(1f) left a large blank band under short panels.
                            preview(Modifier.weight(1f).fillMaxWidth())
                            toolContent(Modifier.fillMaxWidth())
                            if (tab == EditTab.TEXT) ThemeTextSubtabs(textPane, onTextPaneChange)
                            tabs()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PanelControls(panels: PanelAppearance, liveBlur: Boolean, onChange: (PanelAppearance) -> Unit) {
    ThemeEffectSlider(
        R.string.theme_base_opacity, panels.opacity, 0f..1f,
        detentPercents = listOf(50, 85),
        detentLabels = listOf(stringResource(R.string.theme_acrylic_clear), stringResource(R.string.theme_acrylic_heavy)),
    ) {
        onChange(panels.copy(opacity = it))
    }
    SettingsSwitchRow(stringResource(R.string.floating_glass_title) + " " + stringResource(R.string.theme_recommended), panels.acrylicEnabled, { onChange(panels.copy(acrylicEnabled = it)) })
    ThemeEffectSlider(R.string.theme_acrylic_strength, panels.floatingGlass, 0f..1f, panels.acrylicEnabled, defaultPercent = 25, detentPercents = listOf(25, 75), detentLabels = listOf(stringResource(R.string.theme_acrylic_clear), stringResource(R.string.theme_acrylic_heavy))) {
        onChange(panels.copy(floatingGlass = it))
    }
    if (panels.acrylicEnabled && !liveBlur) {
        Text(
            stringResource(R.string.floating_glass_note_fallback),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Space.Screen),
        )
    }
    RowDivider(inset = Space.Screen)
    SettingsSwitchRow(stringResource(R.string.theme_apply_accent), panels.accentEnabled, { onChange(panels.copy(accentEnabled = it)) })
    ThemeEffectSlider(R.string.theme_panel_accent_opacity, panels.accentOpacity, 0f..MaxPanelAccentOpacity, panels.accentEnabled) {
        onChange(panels.copy(accentOpacity = it))
    }
    AdvancedControlOptions(panels, onChange)
}

@Composable
private fun AdvancedControlOptions(panels: PanelAppearance, onChange: (PanelAppearance) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    RowDivider(inset = Space.Screen)
    Row(
        Modifier.fillMaxWidth().clickable { expanded = !expanded }.heightIn(min = 48.dp).padding(horizontal = Space.Screen),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.theme_advanced), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
        Icon(AppIcons.ChevronDown, null, Modifier.size(20.dp).rotate(if (expanded) 180f else 0f))
    }
    AnimatedVisibility(expanded) {
        Column(Modifier.fillMaxWidth().padding(horizontal = Space.Screen)) {
            val opacity = stringResource(R.string.theme_opacity_column)
            val acrylic = stringResource(R.string.floating_glass_title)
            val accent = stringResource(R.string.theme_accent_column)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1.4f))
                for (title in listOf(opacity, acrylic, accent)) Text(title, Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelMedium)
            }
            val effects = panels.controlEffects
            for (control in InterfaceControl.entries) {
                val label = stringResource(when (control) {
                    InterfaceControl.INPUT -> R.string.theme_input
                    InterfaceControl.INPUT_PANEL -> R.string.theme_input_panel
                    InterfaceControl.SIDEBAR -> R.string.theme_sidebar
                    InterfaceControl.MENU -> R.string.theme_popup
                    InterfaceControl.SCROLL_BUTTON -> R.string.theme_scroll_bottom_button
                    InterfaceControl.USER_BUBBLE -> R.string.theme_user_bubble
                    InterfaceControl.MESSAGE_INDEX -> R.string.theme_message_index
                })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(label, Modifier.weight(1.4f), style = MaterialTheme.typography.bodyMedium)
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        Checkbox(
                            effects.usesOpacity(control),
                            { onChange(panels.copy(controlEffects = effects.withOpacity(control, it))) },
                            Modifier.semantics { contentDescription = "$label · $opacity" },
                        )
                    }
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        Checkbox(effects.usesAcrylic(control), { onChange(panels.copy(controlEffects = effects.withAcrylic(control, it))) },
                            Modifier.semantics { contentDescription = "$label · $acrylic" }, enabled = panels.acrylicEnabled)
                    }
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        Checkbox(effects.usesAccent(control), { onChange(panels.copy(controlEffects = effects.withAccent(control, it))) },
                            Modifier.semantics { contentDescription = "$label · $accent" }, enabled = panels.accentEnabled)
                    }
                }
            }
        }
    }
}

@Composable
private fun ThemeEffectSlider(label: Int, value: Float, range: ClosedFloatingPointRange<Float>, enabled: Boolean = true, defaultPercent: Int? = null, detentPercents: List<Int> = emptyList(), detentLabels: List<String> = emptyList(), onChange: (Float) -> Unit) {
    val defaultValue = defaultPercent?.div(100f)
    Column(Modifier.fillMaxWidth().padding(horizontal = Space.Screen, vertical = Space.Gap)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(label),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else LocalChatColors.current.placeholder,
            )
            val percent = (value * 100).roundToInt()
            val detentIndex = detentPercents.indexOfFirst { value == it / 100f }
            val namedStop = detentLabels.getOrNull(detentIndex)
            val labelText = when {
                value == defaultValue -> stringResource(R.string.theme_slider_default)
                namedStop != null -> "$namedStop $percent%"
                else -> "$percent%"
            }
            Text(labelText, style = MaterialTheme.typography.labelMedium)
        }
        AppSlider(
            value, onChange, valueRange = range, enabled = enabled, detent = defaultValue,
            detents = detentPercents.map { it / 100f },
            tickLabels = detentLabels.takeIf { it.isNotEmpty() },
            tickPositions = detentPercents.map { it / 100f },
        )
    }
}

private fun Tool.label(): Int = when (this) {
    Tool.OPACITY -> R.string.theme_wallpaper_overlay
    Tool.DIM -> R.string.wallpaper_dim
    Tool.BLUR -> R.string.floating_glass_title
    Tool.BRIGHTNESS -> R.string.wallpaper_brightness
    Tool.CONTRAST -> R.string.wallpaper_contrast
    Tool.SATURATION -> R.string.wallpaper_saturation
}

@Composable
private fun Tool.display(e: WallpaperEdit): String = when (this) {
    Tool.OPACITY -> "${(e.baseOpacity * 100).toInt()}%"
    Tool.DIM -> "${(e.dim * 100).toInt()}%"
    Tool.BLUR -> {
        val percentage = (e.blur * 100f / MaxWallpaperBlur).roundToInt()
        if (e.blur > 0f && percentage == 0) "<1%" else "$percentage%"
    }
    Tool.BRIGHTNESS -> signed(e.brightness)
    Tool.CONTRAST -> signed(e.contrast)
    Tool.SATURATION -> signed(e.saturation)
}

/** 1.0 为中点，显示成 −40 … +40 这种相册式的相对值。 */
private fun signed(v: Float): String {
    val n = ((v - 1f) * 100).toInt()
    return if (n > 0) "+$n" else "$n"
}

@Composable
private fun ToolSlider(tool: Tool, e: WallpaperEdit, onChange: (WallpaperEdit) -> Unit) {
    fun snapMid(v: Float) = if (kotlin.math.abs(v - 1f) < 0.03f) 1f else v
    when (tool) {
        Tool.OPACITY -> AppSlider(e.baseOpacity, { onChange(e.copy(baseOpacity = it)) })
        Tool.DIM -> AppSlider(e.dim, { onChange(e.copy(dim = it)) }, valueRange = 0f..0.6f)
        Tool.BLUR -> AppSlider(e.blur, { onChange(e.copy(blur = it)) }, valueRange = 0f..MaxWallpaperBlur)
        Tool.BRIGHTNESS -> AppSlider(e.brightness, { onChange(e.copy(brightness = snapMid(it))) }, valueRange = 0.6f..1.4f)
        Tool.CONTRAST -> AppSlider(e.contrast, { onChange(e.copy(contrast = snapMid(it))) }, valueRange = 0.6f..1.4f)
        Tool.SATURATION -> AppSlider(e.saturation, { onChange(e.copy(saturation = snapMid(it))) }, valueRange = 0f..1.6f)
    }
}

/** 按住看原图（不带调色、不带界面），松手恢复。 */
@Composable
private fun CompareButton(onComparing: (Boolean) -> Unit) {
    Box(
        Modifier
            .size(40.dp)
            .clip(PillShape)
            .background(LocalChatColors.current.card)
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    onComparing(true)
                    tryAwaitRelease()
                    onComparing(false)
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            AppIcons.Contrast,
            contentDescription = stringResource(R.string.wallpaper_editor_compare),
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun ToolTile(label: String, value: String, selected: Boolean, onClick: () -> Unit) {
    val colors = LocalChatColors.current
    val fill = if (selected) MaterialTheme.colorScheme.primary else colors.card
    val content = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    Column(
        Modifier
            .widthIn(min = 76.dp)
            .clip(SoftShape)
            .background(fill)
            .clickable(onClick = onClick)
            .padding(horizontal = Space.Inline, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(value, style = MaterialTheme.typography.labelSmall, color = content.copy(alpha = 0.72f), maxLines = 1)
        Text(label, style = MaterialTheme.typography.labelLarge, color = content, maxLines = 1)
    }
}

@Composable
private fun EditorPill(
    text: String,
    selected: Boolean = false,
    filled: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = LocalChatColors.current
    val fill = when {
        selected && filled -> MaterialTheme.colorScheme.primary
        selected -> colors.capsuleSelected
        else -> colors.card
    }
    val content = when {
        selected && filled -> MaterialTheme.colorScheme.onPrimary
        selected -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurface
    }
    Box(
        Modifier
            .clip(PillShape)
            .background(fill)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 9.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = content)
    }
}

/** 裁切时的三分线，便于构图。 */
@Composable
private fun CropGuides() {
    Canvas(Modifier.fillMaxSize()) {
        val c = Color.White.copy(alpha = 0.35f)
        for (i in 1..2) {
            val x = size.width * i / 3f
            val y = size.height * i / 3f
            drawLine(c, Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
            drawLine(c, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
        }
    }
}
