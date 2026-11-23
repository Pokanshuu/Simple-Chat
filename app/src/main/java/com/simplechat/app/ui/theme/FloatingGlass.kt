package com.simplechat.app.ui.theme

import androidx.compose.foundation.background
import com.simplechat.app.data.ControlEffects
import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import com.simplechat.app.data.InterfaceControl
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import kotlin.math.ceil

/** 亚克力强度 0..1；最小值仍有一层柔化及混色。 */
val LocalFloatingGlass = staticCompositionLocalOf { 0f }

/** 悬浮控件的底色不透明度，不参与亚克力采样或混色。 */
val LocalFloatingBaseOpacity = staticCompositionLocalOf { 0.72f }

internal val LocalAcrylicEnabled = staticCompositionLocalOf { false }
internal val LocalControlEffects = staticCompositionLocalOf { ControlEffects() }

/** 当前页面提供的实时背景源。 */
val LocalBackdropSource = compositionLocalOf<BackdropSource?> { null }

internal fun acrylicMixColor(color: Color, strength: Float): Color =
    color.copy(alpha = lerp(0.08f, 0.24f, strength.coerceIn(0f, 1f)))

@Composable
internal fun floatingPanelSurface(control: InterfaceControl): Color {
    val colors = LocalChatColors.current
    // Menus sit one surface level above cards in the dark palette.
    val base = when (control) {
        InterfaceControl.MENU -> colors.menuBackground
        InterfaceControl.USER_BUBBLE -> colors.userBubble
        else -> colors.card
    }
    val effects = LocalControlEffects.current
    val plain = base.copy(alpha = if (effects.usesOpacity(control)) LocalFloatingBaseOpacity.current else 1f)
    return if (effects.usesAccent(control)) panelTint(plain) else plain
}

@Composable
private fun floatingBlurRadius(strength: Float): Dp {
    val max = com.simplechat.app.ui.theme.tokens.AppTheme.tokens.surface.floatingMaxBlur.value.coerceAtLeast(4f)
    return lerp(4f, max, strength.coerceIn(0f, 1f)).dp
}

/** Live blur is supported from Android 12, including in Battery Saver. */
fun rememberLiveBlurAvailable(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/**
 * 模糊源：消息区的内容图层 + 带底色的源图层 + 它在窗口里的位置。
 * 位置用 State 保存，只在**绘制阶段**读取 —— 键盘弹起等位置变化只触发重绘，不触发重组。
 */
@Stable
class BackdropSource internal constructor(
    internal val content: GraphicsLayer,
    internal val source: GraphicsLayer,
) {
    internal var origin by mutableStateOf(Offset.Zero)

    /** 消息区在窗口里的位置（绘制阶段读取）。 */
    val originInRoot: Offset get() = origin
}

@Composable
internal fun needsLiveSurface(vararg controls: InterfaceControl): Boolean {
    val effects = LocalControlEffects.current
    return LocalAcrylicEnabled.current && LocalFloatingBaseOpacity.current < 1f && controls.any { effects.usesOpacity(it) && effects.usesAcrylic(it) }
}

/** 可以实时模糊时返回一个模糊源，否则返回 null（不创建任何离屏图层）。 */
@Composable
fun rememberBackdropSource(enabled: Boolean): BackdropSource? {
    if (!enabled) return null
    val content = rememberGraphicsLayer()
    val source = rememberGraphicsLayer()
    return remember(content, source) { BackdropSource(content, source) }
}

/**
 * 挂在消息区根节点上：把内容录进图层，并准备好供悬浮控件引用的源。
 *
 * @param background 源图层里垫在内容下面的东西（页面底色 + 壁纸）。
 *   屏幕上这两样由外层画，这里只画进**源**，不会重复画到屏幕上。
 */
fun Modifier.backdropSource(
    source: BackdropSource?,
    background: DrawScope.() -> Unit,
): Modifier {
    if (source == null) return this
    return this
        .onGloballyPositioned { source.origin = it.positionInRoot() }
        .drawWithContent {
            val outer = this
            source.content.record { outer.drawContent() }
            source.source.record {
                background()
                drawLayer(source.content)
            }
            drawLayer(source.content)
        }
}

/** Retain the wallpaper drawing commands until its size or visual parameters change. */
internal fun Modifier.sharedSceneBackground(
    layer: GraphicsLayer?,
    alpha: () -> Float = { 1f },
    bubbleBackdrop: BubbleBackdrop? = null,
    draw: DrawScope.() -> Unit,
): Modifier {
    if (layer == null) return drawBehind(draw)
    val placement = if (bubbleBackdrop == null) this else onGloballyPositioned {
        bubbleBackdrop.origin = it.positionInRoot()
    }
    return placement.drawWithCache {
        layer.record { draw() }
        bubbleBackdrop?.layer?.record { drawLayer(layer) }
        onDrawBehind {
            layer.alpha = alpha()
            drawLayer(layer)
        }
    }
}

// A separate, wallpaper-only layer can be sampled from within the recorded message list.
internal class BubbleBackdrop(val layer: GraphicsLayer) {
    var origin by mutableStateOf(Offset.Unspecified)
}

internal val LocalBubbleBackdrop = compositionLocalOf<BubbleBackdrop?> { null }

@Composable
internal fun rememberBubbleBackdrop(wallpaperLayer: GraphicsLayer?): BubbleBackdrop? {
    if (wallpaperLayer == null || !rememberLiveBlurAvailable() || !needsLiveSurface(InterfaceControl.USER_BUBBLE)) return null
    val layer = rememberGraphicsLayer()
    val radius = with(LocalDensity.current) { floatingBlurRadius(LocalFloatingGlass.current).toPx() }
    val effect = remember(radius) { BlurEffect(radius, radius, TileMode.Clamp) }
    androidx.compose.runtime.SideEffect { layer.renderEffect = effect }
    return remember(layer) { BubbleBackdrop(layer) }
}

@Composable
internal fun Modifier.userBubbleBackground(shape: Shape, surface: Color): Modifier {
    if (!LocalAcrylicEnabled.current || !LocalControlEffects.current.usesAcrylic(InterfaceControl.USER_BUBBLE) || surface.alpha >= 1f) {
        return background(surface, shape)
    }
    val backdrop = LocalBubbleBackdrop.current
    val mix = acrylicMixColor(LocalChatColors.current.card, LocalFloatingGlass.current)
    val holder = remember { OriginHolder() }
    return onGloballyPositioned { holder.origin = it.positionInRoot() }.drawWithCache {
        val outline = shape.createOutline(size, layoutDirection, this)
        val path = Path().apply {
            when (outline) {
                is Outline.Rectangle -> addRect(outline.rect)
                is Outline.Rounded -> addRoundRect(outline.roundRect)
                is Outline.Generic -> addPath(outline.path)
            }
        }
        onDrawBehind {
            if (backdrop != null && backdrop.origin != Offset.Unspecified && holder.origin != Offset.Unspecified) {
                val delta = backdrop.origin - holder.origin
                clipPath(path) { translate(delta.x, delta.y) { drawLayer(backdrop.layer) } }
            }
            drawOutline(outline, mix)
            drawOutline(outline, surface)
        }
    }
}

/** Capture only the scene behind the drawer, never the drawer or its scrim. */
internal fun Modifier.recordBackdrop(source: BackdropSource?): Modifier {
    if (source == null) return this
    return onGloballyPositioned { source.origin = it.positionInRoot() }.drawWithContent {
        val scene = this
        source.source.record { scene.drawContent() }
        drawLayer(source.source)
    }
}

/** Popup 有独立窗口，菜单和背景源通过屏幕坐标对齐。 */
private val LocalMenuBackdrop = compositionLocalOf<MenuBackdrop?> { null }

private class MenuBackdrop(val layer: GraphicsLayer) {
    var activeMenus by mutableStateOf(0)
    var screenOrigin by mutableStateOf(Offset.Unspecified)
    var ready by mutableStateOf(false)
}

@Composable
internal fun MenuBackdropHost(content: @Composable () -> Unit) {
    val liveBlur = rememberLiveBlurAvailable()
    val backdrop = if (liveBlur && needsLiveSurface(InterfaceControl.MENU)) {
        val layer = rememberGraphicsLayer()
        remember(layer) { MenuBackdrop(layer) }
    } else null

    val capture = if (backdrop == null) Modifier else Modifier
        .onGloballyPositioned { backdrop.screenOrigin = it.localToScreen(Offset.Zero) }
        .drawWithContent {
            if (backdrop.activeMenus == 0) {
                drawContent()
            } else {
                val page = this
                backdrop.layer.record { page.drawContent() }
                drawLayer(backdrop.layer)
                backdrop.ready = true
            }
        }
    CompositionLocalProvider(LocalMenuBackdrop provides backdrop) {
        Box(Modifier.fillMaxSize().then(capture)) { content() }
    }
}

/** 菜单在独立窗口，只在打开时录制背景。 */
@Composable
internal fun Modifier.menuGlassBackground(base: Color): Modifier {
    val strength = LocalFloatingGlass.current
    val surface = floatingPanelSurface(InterfaceControl.MENU)
    if (!LocalAcrylicEnabled.current || !LocalControlEffects.current.usesAcrylic(InterfaceControl.MENU) || surface.alpha == 1f) return background(surface)
    val mix = acrylicMixColor(base, strength)
    val backdrop = LocalMenuBackdrop.current
    DisposableEffect(backdrop) {
        if (backdrop != null) backdrop.activeMenus++
        onDispose {
            if (backdrop != null) {
                backdrop.activeMenus--
                if (backdrop.activeMenus == 0) backdrop.ready = false
            }
        }
    }
    if (backdrop == null) return acrylicFallback(mix, surface)
    return blurBackdrop(
        layer = backdrop.layer,
        strength = strength,
        mix = mix,
        surface = surface,
        sourceOrigin = { if (backdrop.ready) backdrop.screenOrigin else Offset.Unspecified },
        findOrigin = { it.localToScreen(Offset.Zero) },
    )
}

private fun Modifier.acrylicFallback(mix: Color, surface: Color): Modifier = drawBehind {
    drawRect(mix)
    drawRect(surface)
}

/** 底色绘制在亚克力层之上；调底色 alpha 不会降低模糊图层的 alpha。 */
@Composable
internal fun Modifier.floatingGlassBackground(
    base: Color,
    control: InterfaceControl,
    surface: Color = floatingPanelSurface(control),
    source: BackdropSource? = LocalBackdropSource.current,
    extendIntoBottomInset: Int = 0,
): Modifier {
    if (!LocalAcrylicEnabled.current || !LocalControlEffects.current.usesAcrylic(control) || surface.alpha == 1f) return background(surface)
    val strength = LocalFloatingGlass.current
    val mix = acrylicMixColor(base, strength)
    if (source == null) return acrylicFallback(mix, surface)
    return blurBackdrop(source.source, strength, mix, surface, { source.origin }, { it.positionInRoot() }, extendIntoBottomInset)
}

@Composable
private fun Modifier.blurBackdrop(
    layer: GraphicsLayer,
    strength: Float,
    mix: Color,
    surface: Color,
    sourceOrigin: () -> Offset,
    findOrigin: (LayoutCoordinates) -> Offset,
    extendIntoBottomInset: Int = 0,
): Modifier {
    val blurLayer = rememberGraphicsLayer()
    val radiusPx = with(LocalDensity.current) { floatingBlurRadius(strength).toPx() }
    val effect = remember(radiusPx) {
        if (radiusPx > 0.5f) BlurEffect(radiusPx, radiusPx, TileMode.Clamp) else null
    }
    // 给模糊核留出控件边缘之外的取样范围，避免边缘过早夹取。
    val padding = if (effect == null) 0 else ceil(radiusPx * 3f).toInt()
    val holder = remember { OriginHolder() }
    return this
        .onGloballyPositioned { holder.origin = findOrigin(it) }
        .drawBehind {
            val source = sourceOrigin()
            val origin = holder.origin
            if (source != Offset.Unspecified && origin != Offset.Unspecified) {
                val delta = source - origin
                val layerSize = IntSize(
                    size.width.toInt().coerceAtLeast(1) + padding * 2,
                    size.height.toInt().coerceAtLeast(1) + padding * 2,
                )
                // Re-record the live source on every draw. Reusing its display-list reference
                // can form a cyclic RenderNode graph on HWUI during conversation scrolling.
                blurLayer.renderEffect = effect
                blurLayer.record(layerSize) {
                    val sourceX = delta.x + padding
                    val sourceY = delta.y + padding
                    val edge = minOf(size.height - padding - extendIntoBottomInset, sourceY + layer.size.height)
                    if (extendIntoBottomInset > 0 && edge >= sourceY + 1f && edge > padding) {
                        // Extend a row of the same backdrop instead of exposing sharp chat text
                        // or overlaying a different solid color in the system gesture area.
                        clipRect(bottom = edge) { translate(sourceX, sourceY) { drawLayer(layer) } }
                        clipRect(top = edge) {
                            val row = edge - 1f
                            scale(1f, size.height - row, pivot = Offset(0f, row)) {
                                translate(sourceX, sourceY) { drawLayer(layer) }
                            }
                        }
                    } else {
                        translate(sourceX, sourceY) { drawLayer(layer) }
                    }
                }
                translate(-padding.toFloat(), -padding.toFloat()) { drawLayer(blurLayer) }
            }
            drawRect(mix)
            drawRect(surface)
        }
}

@Stable
private class OriginHolder {
    var origin by mutableStateOf(Offset.Unspecified)
}
