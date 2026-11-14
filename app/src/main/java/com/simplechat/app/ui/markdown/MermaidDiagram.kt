package com.simplechat.app.ui.markdown

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.simplechat.app.R
import com.simplechat.app.ui.common.SoftShape
import com.simplechat.app.ui.theme.LocalChatColors
import kotlinx.serialization.json.JsonPrimitive

/**
 * Mermaid 图表。
 *
 * 与官方 Chat Demo 一样用 **WebView + mermaid.js**，而不是手写一个流程图渲染器：
 * mermaid 的语法族太多（流程图 / 时序图 / 甘特 / 状态 / 类图 / 饼图…），
 * 自己实现等于重写一个项目。
 *
 * ### 三条工程约束
 * 1. **脚本本地打包**（`assets/mermaid/`），不连 CDN —— 这是个离线优先的客户端，
 *    而 CDN 在部分地区还会被挡。
 * 2. **只在真的出现 ```mermaid 时才会走到这里**（由 `collapsibleCodeFence` 分派）。
 *    WebView 是整套界面里最重的一块，每条消息都建一个会立刻把滚动帧率打下来。
 * 3. **`securityLevel: 'strict'`**：图形代码来自模型输出（不可信），
 *    必须禁止其中的脚本与点击事件。
 *
 * 渲染失败时**退回显示源码**，而不是留一块白板 —— 用户至少还能看见写了什么。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MermaidDiagram(
    code: String,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    var heightPx by remember { mutableIntStateOf(0) }
    var failed by remember { mutableStateOf(false) }
    var ready by remember { mutableStateOf(false) }
    var lastPushed by remember { mutableStateOf<String?>(null) }
    var webView by remember { mutableStateOf<WebView?>(null) }

    // mermaid 的主题是**初始化参数**，深色模式下要重画，不能只靠 CSS 覆盖
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    // 用内容 + 主题做去重键：同一段代码不重复下发，换主题 / 换内容就重画
    val renderKey = "$dark|$code"

    fun push(view: WebView?, force: Boolean = false) {
        if (!ready || failed) return
        if (!force && renderKey == lastPushed) return
        lastPushed = renderKey
        // 用 JSON 生成字符串字面量：图形里的引号 / 换行会把手写的脚本打断
        val id = JsonPrimitive(((code.hashCode().toLong() shl 1) or (if (dark) 1 else 0)).toString())
        val script = "render(${JsonPrimitive(code)}, $dark, $id);"
        runCatching { view?.evaluateJavascript(script, null) }
    }

    if (failed) {
        MermaidFallback(code = code, modifier = modifier)
        return
    }

    AndroidView(
        modifier = modifier
            .fillMaxWidth()
            .then(if (heightPx > 0) Modifier.height(with(density) { heightPx.toDp() }) else Modifier.heightIn(min = 120.dp)),
        factory = { context ->
            WebView(context).apply {
                setBackgroundColor(AndroidColor.TRANSPARENT)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = false
                settings.allowFileAccess = false
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        request: WebResourceRequest,
                    ): Boolean = true // 图形里不放链接；万一有，也不许跳走

                    override fun onPageFinished(view: WebView, url: String?) {
                        super.onPageFinished(view, url)
                        ready = true
                        push(view, force = true)
                    }
                }
                // JS 回调在别的线程：一律 post 回主线程再改 Compose 状态
                addJavascriptInterface(
                    MermaidBridge(
                        post = { block -> post(block) },
                        applyHeight = { px -> if (px > 0) heightPx = px },
                        reportError = { failed = true },
                        reportReady = {
                            ready = true
                            push(this, force = true)
                        },
                    ),
                    BridgeName,
                )
                loadUrl(AssetUrl)
                webView = this
            }
        },
        update = { view ->
            webView = view
            push(view)
        },
    )

    DisposableEffect(Unit) {
        onDispose {
            runCatching {
                webView?.removeJavascriptInterface(BridgeName)
                webView?.stopLoading()
                webView?.destroy()
            }
            webView = null
        }
    }
}

/** 渲染失败时的退路：把源码显示出来，别留一块白板。 */
@Composable
private fun MermaidFallback(code: String, modifier: Modifier = Modifier) {
    val colors = LocalChatColors.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(SoftShape)
            .background(colors.fieldBackground)
            .padding(12.dp),
    ) {
        Text(
            text = stringResource(R.string.mermaid_render_failed),
            style = MaterialTheme.typography.labelMedium,
            color = colors.danger,
        )
        Text(
            text = code,
            style = MaterialTheme.typography.bodySmall,
            color = colors.processText,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/**
 * 一段 ```mermaid 围栏的完整卡片：可切换「图表 / 代码」。
 *
 * 默认显示图表；渲染失败时自动退回源码（见 [MermaidDiagram]）。
 * 保留「代码」这一档是刻意的：用户想把图改一改、或者把它复制到别处时，
 * 源码必须够得着。
 */
@Composable
fun MermaidBlock(
    code: String,
    modifier: Modifier = Modifier,
) {
    val colors = LocalChatColors.current
    var showDiagram by remember(code) { mutableStateOf(true) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(SoftShape)
            .background(colors.fieldBackground),
    ) {
        MermaidHeader(showDiagram = showDiagram, onToggle = { showDiagram = !showDiagram })
        if (showDiagram) {
            MermaidDiagram(
                code = code,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
                    .padding(bottom = 10.dp),
            )
        } else {
            Text(
                text = code,
                style = MaterialTheme.typography.bodySmall,
                color = colors.processText,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

/** 图表 / 代码 两种形态的切换头（对齐官方的「图表 · 代码」）。 */@Composable
fun MermaidHeader(
    showDiagram: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalChatColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(32.dp)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.mermaid_label),
            style = MaterialTheme.typography.labelMedium,
            color = colors.processText,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = stringResource(
                if (showDiagram) R.string.mermaid_show_code else R.string.mermaid_show_diagram,
            ),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable(onClick = onToggle)
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

/**
 * JS ↔ Kotlin 的桥。
 *
 * 只暴露三个**无参**回调，不接受任何来自页面的数据 —— 页面里跑的是模型产出的
 * 图表代码，桥暴露得越少，被利用的面越小。[post] 把它切回主线程再改状态。
 *
 * ⚠️ 属性名与方法名**必须不同**：同名时方法体里的 `onHeight(...)` 解析到的是
 * 方法自己，编译器只会报一句"类型检查出现递归问题"，很难看出是自调用。
 */
private class MermaidBridge(
    private val post: (() -> Unit) -> Unit,
    private val applyHeight: (Int) -> Unit,
    private val reportError: () -> Unit,
    private val reportReady: () -> Unit,
) {
    @JavascriptInterface
    fun onHeight(px: Int) = post { applyHeight(px) }

    @JavascriptInterface
    fun onError(message: String) = post { reportError() }

    @JavascriptInterface
    fun onReady() = post { reportReady() }
}

private const val BridgeName = "Bridge"

private const val AssetUrl = "file:///android_asset/mermaid/view.html"

/** 感知亮度，用来决定 mermaid 用亮色还是深色主题。 */
private fun Color.luminance(): Float = 0.299f * red + 0.587f * green + 0.114f * blue
