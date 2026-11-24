package com.simplechat.app.ui.settings

import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.simplechat.app.data.FontSlot
import com.simplechat.app.data.InterfaceControl
import com.simplechat.app.data.ConversationWallpaper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.simplechat.app.ui.chat.ChatTopBar
import com.simplechat.app.ui.chat.ContextStats
import com.simplechat.app.ui.chat.InputBar
import com.simplechat.app.ui.theme.needsLiveSurface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.dp
import com.simplechat.app.R
import com.simplechat.app.data.MaxPanelAccentOpacity
import com.simplechat.app.data.PanelAppearance
import com.simplechat.app.data.WallpaperAppearance
import com.simplechat.app.data.SettingsStore
import com.simplechat.app.data.StoredSettings
import com.simplechat.app.data.WallpaperFiles
import com.simplechat.app.ui.common.BubbleShapeOut
import com.simplechat.app.ui.common.cropTo
import com.simplechat.app.ui.theme.LocalBackdropSource
import com.simplechat.app.ui.theme.LocalChatColors
import com.simplechat.app.ui.theme.userBubbleBackground
import com.simplechat.app.ui.theme.floatingPanelSurface
import com.simplechat.app.ui.theme.glassContrastForeground
import com.simplechat.app.ui.theme.LocalWallpaper
import com.simplechat.app.ui.theme.WallpaperState
import com.simplechat.app.ui.theme.backdropSource
import com.simplechat.app.ui.chat.BackToBottomButton
import com.simplechat.app.ui.theme.rememberBackdropSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

@Composable
internal fun CustomThemeEditor(
    stored: StoredSettings,
    store: SettingsStore,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val currentDark = com.simplechat.app.ui.theme.LocalIsDarkTheme.current
    val systemFontScale = com.simplechat.app.ui.theme.LocalSystemTextScale.current ?: androidx.compose.ui.platform.LocalDensity.current.fontScale
    val scope = rememberCoroutineScope()
    var draft by remember(stored.fontScale, stored.followSystemFont, stored.typography, stored.uiFontId, stored.userFontId, stored.assistantFontId) { mutableStateOf(com.simplechat.app.data.TypographyDraft(
        stored.fontScale, stored.followSystemFont, stored.uiFontId, stored.userFontId, stored.assistantFontId, stored.typography,
    )) }
    var dark by remember(currentDark) { mutableStateOf(currentDark) }
    var mainTab by rememberSaveable { mutableStateOf(EditTab.ADJUST) }
    var textPane by rememberSaveable { mutableStateOf(TextPane.ASSISTANT) }
    var importSlot by remember { mutableStateOf<com.simplechat.app.data.FontSlot?>(null) }
    var managing by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val slot = importSlot
        importSlot = null
        if (uri != null && slot != null) scope.launch {
            val font = withContext(Dispatchers.IO) {
                com.simplechat.app.data.FontFiles.import(context, uri, displayNameOf(context, uri) ?: "Font")
            }
            if (font == null) Toast.makeText(context, R.string.fonts_import_failed, Toast.LENGTH_SHORT).show()
            else {
                store.addImportedFont(font)
                draft = draft.withFont(slot, font.id)
            }
        }
    }
    val fonts = remember(context, stored.fonts, draft.uiFontId, draft.userFontId, draft.assistantFontId) {
        fun resolve(id: String?) = stored.fonts.firstOrNull { it.id == id }
            ?.let { com.simplechat.app.ui.theme.fontFamilyOf(context, it) }
        com.simplechat.app.ui.theme.TypographyFonts(resolve(draft.uiFontId), resolve(draft.userFontId), resolve(draft.assistantFontId))
    }
    WallpaperEditDialog(
        path = stored.wallpaperPath?.takeIf { it.isNotBlank() },
        currentWallpaper = LocalWallpaper.current,
        initialPanels = PanelAppearance(
            acrylicEnabled = stored.acrylicEnabled, floatingGlass = stored.floatingGlass,
            controlEffects = stored.controlEffects, opacity = stored.wallpaperPanelAlpha,
            accentEnabled = stored.panelAccentEnabled, accentOpacity = stored.panelAccentOpacity.coerceIn(0f, MaxPanelAccentOpacity),
        ),
        onDismiss = onDismiss,
        tab = mainTab,
        onTabChange = { mainTab = it },
        textPane = textPane,
        onTextPaneChange = { textPane = it },
        textEditor = { modifier ->
            ThemeTextEditor(
                draft = draft,
                fonts = stored.fonts,
                dark = dark,
                onChange = { draft = it },
                onImportFont = { slot -> importSlot = slot; picker.launch(arrayOf("*/*")) },
                onManageFonts = { managing = true },
                pane = textPane,
                onPaneChange = { textPane = it },
                modifier = modifier,
            )
        },
        previewTheme = { content -> com.simplechat.app.ui.theme.TypographyPreview(draft, fonts, dark, systemFontScale = systemFontScale, content = content) },
        previewDark = dark,
        onPreviewDarkChange = { dark = it },
        onCommit = { path, appearance, panels, recropped ->
            store.saveCustomTheme(path, appearance, panels, recropped, draft)
        },
    )
    if (managing) FontLibraryDialog(stored.fonts, store, onRemove = { id -> FontSlot.entries.forEach { slot -> if (draft.fontId(slot) == id) draft = draft.withFont(slot, null) } }) { managing = false }
}

@Composable
internal fun ConversationWallpaperEditor(
    current: ConversationWallpaper?,
    onCommit: suspend (ConversationWallpaper?) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val state = current?.toWallpaperState(context) ?: WallpaperState(opacity = 1f)
    WallpaperEditDialog(
        path = current?.fileName,
        currentWallpaper = state,
        initialPanels = PanelAppearance(
            acrylicEnabled = com.simplechat.app.ui.theme.LocalAcrylicEnabled.current,
            floatingGlass = com.simplechat.app.ui.theme.LocalFloatingGlass.current,
            controlEffects = com.simplechat.app.ui.theme.LocalControlEffects.current,
            opacity = com.simplechat.app.ui.theme.LocalFloatingBaseOpacity.current,
            accentEnabled = true,
            accentOpacity = com.simplechat.app.ui.theme.LocalPanelAccentOpacity.current,
        ),
        showInterfaceTab = false,
        tab = EditTab.CROP,
        onTabChange = {},
        textPane = TextPane.SIZE,
        onTextPaneChange = {},
        previewDark = com.simplechat.app.ui.theme.LocalIsDarkTheme.current,
        onDismiss = onDismiss,
        onCommit = { path, appearance, _, _ -> onCommit(path?.let { ConversationWallpaper(it, appearance) }) },
    )
}

internal fun ConversationWallpaper.toWallpaperState(context: android.content.Context): WallpaperState = WallpaperState(
    file = WallpaperFiles.fileOf(context, fileName),
    opacity = appearance.opacity, blur = appearance.blur.coerceIn(0f, com.simplechat.app.data.MaxWallpaperBlur),
    brightness = appearance.brightness, contrast = appearance.contrast, saturation = appearance.saturation,
    dim = appearance.dim, flipH = appearance.flipH,
)

@Composable
private fun WallpaperEditDialog(
    path: String?,
    currentWallpaper: WallpaperState,
    initialPanels: PanelAppearance,
    onDismiss: () -> Unit,
    showInterfaceTab: Boolean = true,
    tab: EditTab,
    onTabChange: (EditTab) -> Unit,
    textPane: TextPane,
    onTextPaneChange: (TextPane) -> Unit,
    textEditor: (@Composable (Modifier) -> Unit)? = null,
    previewTheme: @Composable (@Composable () -> Unit) -> Unit = { it() },
    previewDark: Boolean = false,
    onPreviewDarkChange: (Boolean) -> Unit = {},
    onCommit: suspend (String?, WallpaperAppearance, PanelAppearance, Boolean) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var original by remember(path) { mutableStateOf<WallpaperFiles.CropSource?>(null) }
    var picked by remember { mutableStateOf<Bitmap?>(null) }
    var removed by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var imageAction by remember { mutableStateOf(0) }
    var loadOriginal by remember(path) { mutableStateOf(false) }
    LaunchedEffect(path, loadOriginal) {
        if (path != null && loadOriginal) original = withContext(Dispatchers.IO) {
            WallpaperFiles.loadSource(context, path)
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val image = withContext(Dispatchers.IO) { WallpaperFiles.decodeSource(context, uri) }
            if (image == null) {
                Toast.makeText(context, R.string.wallpaper_import_failed, Toast.LENGTH_SHORT).show()
            } else {
                picked = image
                removed = false
                imageAction++
            }
        }
    }
    val savedCrop = original.takeIf { !removed && picked == null }
    val source = if (removed) null else picked ?: savedCrop?.bitmap
    ThemeEditorLayout(
        textEditor = textEditor,
        tab = tab,
        onTabChange = onTabChange,
        textPane = textPane,
        onTextPaneChange = onTextPaneChange,
        previewTheme = previewTheme,
        previewDark = previewDark,
        onPreviewDarkChange = onPreviewDarkChange,
        source = source,
        savedCrop = savedCrop?.rect,
        onPrepareCrop = { if (!removed && picked == null) loadOriginal = true },
        isNewImage = picked != null,
        imageAction = imageAction,
        currentWallpaper = if (removed || picked != null) WallpaperState() else currentWallpaper,
        initial = WallpaperEdit(
            baseOpacity = 1f - currentWallpaper.opacity,
            dim = currentWallpaper.dim,
            blur = currentWallpaper.blur,
            brightness = currentWallpaper.brightness,
            contrast = currentWallpaper.contrast,
            saturation = currentWallpaper.saturation,
            flipH = currentWallpaper.flipH,
        ),
        initialPanels = initialPanels,
        showInterfaceTab = showInterfaceTab,
        saving = saving,
        onDismiss = onDismiss,
        onPick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        onRemove = { removed = true; picked = null; imageAction++ },
        onSave = { rect, edit, panels ->
            val cropSource = source
            val previousPath = path
            val previousSource = path.takeIf { savedCrop?.usesOriginal == true }
            val remove = removed
            saving = true
            scope.launch {
                var newPath: String? = null
                try {
                    if (rect != null && cropSource != null) {
                        newPath = withContext(Dispatchers.IO) {
                            val cropped = cropSource.cropTo(rect)
                            try {
                                WallpaperFiles.saveCropped(context, cropped, cropSource, rect, previousSource)
                            } finally {
                                if (cropped !== cropSource) cropped.recycle()
                            }
                        } ?: throw IOException("Could not save wallpaper")
                    }
                    val savedPath = if (remove) null else newPath ?: previousPath
                    onCommit(
                        savedPath,
                        WallpaperAppearance(
                            opacity = 1f - edit.baseOpacity,
                            blur = edit.blur,
                            brightness = edit.brightness,
                            contrast = edit.contrast,
                            saturation = edit.saturation,
                            dim = edit.dim,
                            flipH = edit.flipH,
                        ),
                        panels,
                        savedPath != previousPath,
                    )
                    if (previousPath != null && previousPath != savedPath) {
                        withContext(Dispatchers.IO) { WallpaperFiles.deleteWallpaper(context, previousPath) }
                    }
                    onDismiss()
                } catch (error: kotlinx.coroutines.CancellationException) {
                    throw error
                } catch (_: Exception) {
                    newPath?.let { withContext(Dispatchers.IO) { WallpaperFiles.deleteWallpaper(context, it) } }
                    Toast.makeText(context, R.string.diagnostics_save_failed, Toast.LENGTH_SHORT).show()
                } finally {
                    saving = false
                }
            }
        },
    )
}

/** Reuse the chat chrome and render a static, scrollable sample conversation. */
@Composable
internal fun ThemeChatSample(
    drawBackground: DrawScope.() -> Unit,
    liveBlur: Boolean,
    allowScroll: Boolean,
) {
    val colors = LocalChatColors.current
    val bubbleSurface = floatingPanelSurface(InterfaceControl.USER_BUBBLE)
    val backdrop = rememberBackdropSource(liveBlur && needsLiveSurface(InterfaceControl.INPUT, InterfaceControl.SCROLL_BUTTON))
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().backdropSource(backdrop, drawBackground)) {
            Box(Modifier.clearAndSetSemantics {}) {
                ChatTopBar(stringResource(R.string.conversation_default_title), {}, {}, applyStatusBarInset = false)
                PreviewTouchShield(Modifier.matchParentSize())
            }
            Column(
                Modifier.weight(1f).widthIn(max = 640.dp).fillMaxWidth()
                    .align(Alignment.CenterHorizontally)
                    .verticalScroll(rememberScrollState(), enabled = allowScroll)
                    .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 128.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                com.simplechat.app.ui.theme.TypographyCategory(FontSlot.USER) {
                    Text(
                        stringResource(R.string.wallpaper_preview_user),
                        color = com.simplechat.app.ui.theme.LocalTypographyTextColor.current
                            ?: glassContrastForeground(bubbleSurface, colors.pageBackground, MaterialTheme.colorScheme.onSurface),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.align(Alignment.End).fillMaxWidth(0.78f)
                            .userBubbleBackground(BubbleShapeOut, bubbleSurface).padding(horizontal = 14.dp, vertical = 10.dp),
                    )
                }
                com.simplechat.app.ui.theme.TypographyCategory(FontSlot.ASSISTANT) {
                    Text(
                        stringResource(R.string.wallpaper_preview_reply),
                        color = MaterialTheme.colorScheme.onBackground,
                        style = MaterialTheme.typography.bodyLarge.copy(lineBreak = LineBreak.Paragraph),
                    )
                }
            }
        }
        CompositionLocalProvider(LocalBackdropSource provides backdrop) {
            Box(Modifier.align(Alignment.BottomCenter).widthIn(max = 800.dp).fillMaxWidth().clearAndSetSemantics {}) {
                Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                    BackToBottomButton(true, false, {}, Modifier.align(Alignment.End).padding(end = 16.dp, bottom = 8.dp))
                    InputBar(
                        fieldState = rememberTextFieldState(), expanded = false, onToggleExpanded = null,
                        thinkingEnabled = false, thinkingPanelOpen = false, onOpenThinkingPanel = {},
                        settingsPanelOpen = false, onOpenSettingsPanel = {}, streaming = false, onSend = {},
                        onStop = {}, onAttachClick = {}, contextStats = remember { ContextStats(0, 1, 0, 0, 0, null, null, null, false) },
                        statsPanelOpen = false, onOpenStatsPanel = {}, holdToTalkEnabled = true,
                    )
                }
                PreviewTouchShield(Modifier.matchParentSize())
            }
        }
    }
}

/** Keep preview chrome non-interactive without consuming the enclosing crop gesture. */
@Composable
private fun PreviewTouchShield(modifier: Modifier) {
    Box(modifier.pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } })
}
