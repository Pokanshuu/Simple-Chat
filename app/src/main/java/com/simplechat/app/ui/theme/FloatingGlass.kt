package com.simplechat.app.ui.theme

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp

/*
 * 悬浮控件的实时玻璃（docs/32 §32.3.3）。
 *
 * 只给**浮在正文之上**的控件用：输入栏、输入面板。一个用户值 g ∈ [0, 1]
 * 同时决定「混色」（表面不透明度）和「模糊半径」。
 *
 * ── 怎么做到"实时"又不贵 ─────────────────────────────────────
 * 1. 消息区把自己的内容录进 contentLayer（GraphicsLayer = 一个 RenderNode），
 *    屏幕上画的就是这一份 —— 内容**只录一次**。
 * 2. sourceLayer = 页面底色 + 壁纸 + drawLayer(contentLayer)。它只是**引用**
 *    contentLayer，不复制内容；正文流式更新、列表滚动时，子 RenderNode 自己更新，
 *    引用方自动看到新内容，**悬浮控件不需要重新录制**。
 * 3. 悬浮控件自己持有一个 blurLayer：按自身位置平移后引用 sourceLayer，
 *    挂 BlurEffect（RenderEffect，Android 12+）。模糊只在控件那一小块区域上算。
 * 4. g = 0、Android 12 以下、省电模式：**不创建源图层**，绘制路径与旧版一致；
 *    后两者只保留更实的半透明。
 */

/** 用户设置的玻璃质感 0..1。0 = 实色（默认）。 */
val LocalFloatingGlass = staticCompositionLocalOf { 0f }

/** 当前页面提供的模糊源；为空表示不做实时模糊（只混色或实色）。 */
val LocalBackdropSource = compositionLocalOf<BackdropSource?> { null }

/*
 * 下限与模糊上限来自 SurfaceTokens（docs/33）：
 * - floatingFloorBlurred：有模糊时的最透下限，背后已经被抹平，可以更透；
 * - floatingFloorPlain：无模糊时的最透下限，背后的字会直接透出来，必须更实；
 * - floatingMaxBlur：模糊半径上限，再往上视觉收益很小、GPU 开销明显增加。
 */

/** 悬浮控件表面的不透明度（混色）。 */
@Composable
@androidx.compose.runtime.ReadOnlyComposable
fun floatingSurfaceAlpha(glass: Float, blurred: Boolean): Float {
    val surface = com.simplechat.app.ui.theme.tokens.AppTheme.tokens.surface
    return lerp(1f, if (blurred) surface.floatingFloorBlurred else surface.floatingFloorPlain, glass.coerceIn(0f, 1f))
}

/** 模糊半径。 */
@Composable
@androidx.compose.runtime.ReadOnlyComposable
fun floatingBlurRadius(glass: Float): Dp =
    com.simplechat.app.ui.theme.tokens.AppTheme.tokens.surface.floatingMaxBlur * glass.coerceIn(0f, 1f)

/**
 * 当前设备 / 状态下能不能做实时模糊：Android 12+ 且不在省电模式。
 * 省电模式切换时会实时更新（监听系统广播），不需要重启页面。
 */
@Composable
fun rememberLiveBlurAvailable(): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
    val context = LocalContext.current
    val power = remember { context.getSystemService(Context.POWER_SERVICE) as? PowerManager }
    var saving by remember { mutableStateOf(power?.isPowerSaveMode == true) }
    DisposableEffect(power) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                saving = power?.isPowerSaveMode == true
            }
        }
        context.registerReceiver(receiver, IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED))
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }
    return !saving
}

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

/** g > 0 且可以实时模糊时返回一个模糊源，否则返回 null（不创建任何离屏图层）。 */
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

/**
 * 悬浮控件的玻璃底：模糊后的背景 + 半透明表面色。
 *
 * 放在 `.clip(shape)` **之后**、原来 `.background(...)` 的位置。
 * 没有模糊源时只画 [surface]（调用方已经按 [floatingSurfaceAlpha] 算好不透明度）。
 */
@Composable
fun Modifier.floatingGlassBackground(surface: Color, glass: Float): Modifier {
    val source = LocalBackdropSource.current
    if (source == null || glass <= 0f) return this.drawBehind { drawRect(surface) }
    val blurLayer = rememberGraphicsLayer()
    val radiusPx = with(LocalDensity.current) { floatingBlurRadius(glass).toPx() }
    val holder = remember { OriginHolder() }
    return this
        .onGloballyPositioned { holder.origin = it.positionInRoot() }
        .drawBehind {
            val dx = source.origin.x - holder.origin.x
            val dy = source.origin.y - holder.origin.y
            blurLayer.renderEffect = if (radiusPx > 0.5f) BlurEffect(radiusPx, radiusPx, TileMode.Clamp) else null
            blurLayer.record(IntSize(size.width.toInt().coerceAtLeast(1), size.height.toInt().coerceAtLeast(1))) {
                translate(dx, dy) { drawLayer(source.source) }
            }
            drawLayer(blurLayer)
            drawRect(surface)
        }
}

@Stable
private class OriginHolder {
    var origin by mutableStateOf(Offset.Zero)
}
