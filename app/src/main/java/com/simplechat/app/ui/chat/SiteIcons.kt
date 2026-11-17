package com.simplechat.app.ui.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.simplechat.app.App
import com.simplechat.app.ui.theme.LocalChatColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * 站点小图标（favicon）的加载与缓存。
 *
 * 只为来源条上那几枚 16dp 的圆点，**不值得为它引 Coil**（见 `Attachments.kt` 与设计文档 §8）。
 * 做法很朴素：
 * - 复用应用里那一个 `OkHttpClient`（共享连接池），只另设一个短的总超时；
 * - 解码时按目标尺寸降采样，内存里放一份小 LRU；
 * - 原始字节落在 `cacheDir/site_icons/`，系统空间紧张时可以随手清掉。
 *
 * 拉不到（404、超时、不是图片）就记一个"失败"，同一次进程内不再重试，
 * 界面退回首字母圆点 —— 宁可朴素，不要反复转圈。
 */
private object SiteIconCache {
    private const val MaxBytes = 256 * 1024
    private const val TargetPx = 64

    private val memory = LruCache<String, ImageBitmap>(64)
    private val failed = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    @Volatile
    private var client: OkHttpClient? = null

    fun cached(url: String): ImageBitmap? = memory.get(url)

    fun hasFailed(url: String): Boolean = url in failed

    suspend fun load(context: Context, url: String): ImageBitmap? = withContext(Dispatchers.IO) {
        memory.get(url)?.let { return@withContext it }
        if (url in failed) return@withContext null
        val file = File(File(context.cacheDir, "site_icons"), sha1(url))
        val bytes = runCatching { if (file.isFile) file.readBytes() else null }.getOrNull()
            ?: fetch(context, url)?.also { data ->
                runCatching {
                    file.parentFile?.mkdirs()
                    file.writeBytes(data)
                }
            }
        val bitmap = bytes?.let(::decode)
        if (bitmap == null) {
            failed += url
            runCatching { file.delete() }
            null
        } else {
            bitmap.asImageBitmap().also { memory.put(url, it) }
        }
    }

    private fun fetch(context: Context, url: String): ByteArray? {
        if (!url.startsWith("https://") && !url.startsWith("http://")) return null
        val http = client ?: synchronized(this) {
            client ?: (context.applicationContext as App).container.httpClient.newBuilder()
                .callTimeout(8, TimeUnit.SECONDS)
                .readTimeout(8, TimeUnit.SECONDS)
                .build()
                .also { client = it }
        }
        return runCatching {
            http.newCall(Request.Builder().url(url).build()).execute().use { response ->
                val body = response.body
                if (!response.isSuccessful) return null
                if (body.contentLength() > MaxBytes) return null
                // Unknown/chunked lengths must be bounded while reading, not afterwards.
                val buffer = okio.Buffer()
                val source = body.source()
                while (buffer.size <= MaxBytes) {
                    if (source.read(buffer, minOf(8192L, MaxBytes + 1L - buffer.size)) < 0) break
                }
                if (buffer.size > MaxBytes) null else buffer.readByteArray()
            }
        }.getOrNull()
    }

    private fun decode(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= TargetPx && bounds.outHeight / (sample * 2) >= TargetPx) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun sha1(text: String): String =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}

/**
 * 一枚站点图标：有 favicon 画 favicon，没有（或还没到）就画站点名首字母。
 *
 * [ring] 是外圈描边的颜色 —— 叠放时用它把相邻两枚隔开（与所在背景同色即可）。
 */
@Composable
internal fun SiteIcon(
    source: SourceRef,
    size: Dp,
    modifier: Modifier = Modifier,
    ring: Color? = null,
) {
    val colors = LocalChatColors.current
    val context = LocalContext.current
    val url = source.siteIcon
    var bitmap by remember(url) { mutableStateOf(url?.let(SiteIconCache::cached)) }
    if (url != null && bitmap == null && !SiteIconCache.hasFailed(url)) {
        LaunchedEffect(url) { bitmap = SiteIconCache.load(context, url) }
    }

    val shaped = modifier
        .let { if (ring != null) it.border(1.5.dp, ring, CircleShape) else it }
        .clip(CircleShape)
    val image = bitmap
    if (image != null) {
        Image(
            bitmap = image,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = shaped.background(Color.White).size(size),
        )
    } else {
        Box(
            modifier = shaped.background(colors.fieldBackground).size(size),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = initialOf(source),
                style = TextStyle(fontSize = (size.value * 0.5f).sp, fontWeight = FontWeight.Medium),
                color = colors.processText,
            )
        }
    }
}

/** 站点名首字母（去掉 `www.`）；什么都没有就用编号。 */
internal fun initialOf(source: SourceRef): String {
    val name = source.siteName.ifBlank { source.title }.removePrefix("www.").trim()
    val first = name.firstOrNull { it.isLetterOrDigit() } ?: return source.index.toString()
    return first.uppercaseChar().toString()
}
