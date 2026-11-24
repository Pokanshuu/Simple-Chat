package com.simplechat.app.ui.chat

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import com.simplechat.app.data.TypographySettings
import com.simplechat.app.ui.theme.TypographyFonts
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import com.simplechat.app.ui.theme.SimpleChatTheme
import com.simplechat.app.ui.theme.ThemeMode
import com.simplechat.app.ui.theme.AccentColor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A one-pixel, non-interactive host. Only records into a private graphics layer; draws no UI. */
internal class ShareComposeRenderer private constructor(context: Context, private val parent: ViewGroup,
    private val accent: AccentColor, private val typography: TypographySettings?, private val fonts: TypographyFonts,
    private val globalScale: Float, private val systemScale: Float) {
    private class Content(val body: @Composable () -> Unit) {
        var size by mutableStateOf(IntSize.Zero)
    }
    private class Capture(val top: Int, val height: Int, val scale: Float) {
        val recorded = CompletableDeferred<GraphicsLayer>()
    }
    private var content by mutableStateOf<Content?>(null)
    private var capture by mutableStateOf<Capture?>(null)
    private val compose = ComposeView(context)
    private val host = object : ViewGroup(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            compose.measure(MeasureSpec.makeMeasureSpec(952, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            setMeasuredDimension(1, 1)
        }
        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            compose.layout(0, 0, compose.measuredWidth, compose.measuredHeight)
        }
    }

    init {
        host.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        host.clipChildren = false
        host.addView(compose)
        parent.addView(host, ViewGroup.LayoutParams(1, 1))
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(2f, systemScale)) {
                SimpleChatTheme(themeMode = ThemeMode.LIGHT, accent = accent, typographySettings = typography,
                    typographyFonts = fonts, fontScale = globalScale / systemScale) {
                    val layer = rememberGraphicsLayer()
                    val item = content
                    key(item) {
                        Box(Modifier.onSizeChanged { item?.size = it }.drawWithContent {
                            val request = capture
                            if (item != null && request != null && !request.recorded.isCompleted) {
                                layer.record(size = IntSize(952, request.height)) {
                                    translate(top = -request.top.toFloat()) {
                                        scale(request.scale, request.scale, pivot = androidx.compose.ui.geometry.Offset.Zero) {
                                            this@drawWithContent.drawContent()
                                        }
                                    }
                                }
                                host.post { request.recorded.complete(layer) }
                            }
                            // No drawContent()/drawLayer(): export must not flash over the conversation.
                        }) { item?.body?.invoke() }
                    }
                }
            }
        }
    }

    suspend fun measure(body: @Composable () -> Unit): IntSize = withContext(Dispatchers.Main.immediate) {
        capture = null
        val item = Content(body)
        content = item
        host.requestLayout()
        withTimeout(15_000) {
            while (item.size.width == 0 || item.size.height == 0) nextFrame()
            nextFrame()
            item.size
        }
    }

    suspend fun capture(top: Int, height: Int, scale: Float = 1f): Bitmap = withContext(Dispatchers.Main.immediate) {
        val request = Capture(top, height, scale)
        capture = request
        compose.invalidate()
        val layer = withTimeout(15_000) { request.recorded.await() }
        val bitmap = layer.toImageBitmap().asAndroidBitmap()
        // Canvas page composition is software; hardware bitmaps must be copied before drawing.
        if (bitmap.config == Bitmap.Config.HARDWARE) {
            checkNotNull(bitmap.copy(Bitmap.Config.ARGB_8888, false)).also { bitmap.recycle() }
        } else bitmap
    }

    suspend fun close() = withContext(NonCancellable + Dispatchers.Main.immediate) {
        closeNow()
    }

    private fun closeNow() {
        capture?.recorded?.cancel()
        compose.disposeComposition()
        parent.removeView(host)
    }

    companion object {
        suspend fun create(
            context: Context,
            accent: AccentColor = AccentColor.BLUE,
            typography: TypographySettings? = null,
            fonts: TypographyFonts = TypographyFonts(),
            globalScale: Float = 1f,
            systemScale: Float = 1f,
        ): ShareComposeRenderer = suspendCancellableCoroutine { continuation ->
            val handler = Handler(Looper.getMainLooper())
            val create = Runnable {
                if (continuation.isActive) {
                    try {
                        var owner = context
                        while (owner is ContextWrapper && owner !is Activity) owner = owner.baseContext
                        val activity = owner as? Activity ?: error("Image export requires a visible activity")
                        val renderer = ShareComposeRenderer(activity, activity.window.decorView as ViewGroup, accent, typography, fonts, globalScale, systemScale)
                        continuation.resume(renderer, onCancellation = { _, resource, _ -> handler.post { resource.closeNow() } })
                    } catch (error: Exception) { continuation.resumeWithException(error) }
                }
            }
            handler.post(create)
            continuation.invokeOnCancellation { handler.removeCallbacks(create) }
        }
    }
}

private suspend fun nextFrame() = suspendCancellableCoroutine { continuation ->
    val choreographer = Choreographer.getInstance()
    val callback = Choreographer.FrameCallback { if (continuation.isActive) continuation.resume(Unit) }
    choreographer.postFrameCallback(callback)
    continuation.invokeOnCancellation { choreographer.removeFrameCallback(callback) }
}
