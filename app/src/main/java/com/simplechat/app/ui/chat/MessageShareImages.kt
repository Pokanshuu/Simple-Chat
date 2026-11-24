package com.simplechat.app.ui.chat

import android.content.Context
import android.app.ActivityManager
import android.graphics.*
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Base64
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import com.simplechat.app.R
import com.simplechat.app.data.Attachment
import com.simplechat.app.data.AttachmentFiles
import com.simplechat.app.ui.markdown.*
import com.simplechat.app.ui.theme.LocalChatColors
import com.simplechat.app.ui.theme.AccentColor
import com.simplechat.app.ui.theme.TypographyFonts
import com.simplechat.app.data.TypographySettings
import com.simplechat.app.data.FontSlot
import com.simplechat.app.ui.theme.TypographyCategory
import com.simplechat.app.ui.theme.ProcessTextStyle
import com.simplechat.app.ui.common.BubbleShapeOut
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.ceil

/** Native chat rendering, captured in bounded tiles and assembled into share pages. */
internal suspend fun exportSharedImages(context: Context, document: MessageShareDocument,
    mode: ShareImageMode = ShareImageMode.PAGES,
    accent: AccentColor = AccentColor.BLUE,
    hideBranding: Boolean = false,
    hideReasoning: Boolean = true,
    typography: TypographySettings? = null,
    typographyFonts: TypographyFonts = TypographyFonts(),
    fontScale: Float = 1f,
    systemFontScale: Float = 1f,
): List<File> = withContext(Dispatchers.Default) {
    val characterCount = document.messages.sumOf { message ->
        shareImageText(message, hideReasoning).sumOf { it.text.length.toLong() } +
            message.attachments.filterNot { it.isImage }.sumOf { it.data.length.toLong() }
    }
    require(characterCount <= 200_000) { context.getString(R.string.share_image_too_long) }
    val fonts = loadExportFonts(context)
    val directory = shareDirectory(context)
    val renderer = try {
        ShareComposeRenderer.create(context, accent, typography, typographyFonts, fontScale, systemFontScale)
    }
        catch (error: Throwable) { directory.deleteRecursively(); throw error }
    val memoryClass = (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).memoryClass
    val writer = ShareImageWriter(context, directory, document.title, shareImagePageHeight(mode, memoryClass), mode == ShareImageMode.LONG, hideBranding, document.excerptFooter)
    val result = try {
        suspend fun render(block: ShareRenderBlock, user: Boolean = false, reasoning: Boolean = false,
            reasoningContinues: Boolean = false) {
            val metrics = ExportTableMetrics()
            var tablePage = -1
            suspend fun part(rows: IntRange? = null) {
                currentCoroutineContext().ensureActive()
                val options = MarkdownExportOptions(fonts, rows, metrics,
                    tableHeader = rows?.first == 0 || tablePage != writer.pageNumber)
                val size = renderer.measure {
                    TypographyCategory(if (user) FontSlot.USER else FontSlot.ASSISTANT) {
                    CompositionLocalProvider(LocalMarkdownExport provides options) {
                        when (block) {
                            is ShareRenderBlock.Formula -> ExportFormula(block.tex, block.source)
                            is ShareRenderBlock.Table -> MarkdownText(block.source)
                            is ShareRenderBlock.Text -> if (block.markdown) MarkdownText(block.source)
                                else if (user) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopEnd) {
                                    Text(block.source, style = MaterialTheme.typography.bodyLarge,
                                        color = LocalChatColors.current.onUserBubble,
                                        modifier = Modifier.widthIn(max = 380.dp)
                                            .background(LocalChatColors.current.userBubble, BubbleShapeOut)
                                            .padding(horizontal = 14.dp, vertical = 11.dp))
                                } else if (reasoning) {
                                    Text(
                                        block.source,
                                        style = ProcessTextStyle,
                                        color = LocalChatColors.current.processText,
                                        modifier = Modifier.fillMaxWidth()
                                            .padding(bottom = if (reasoningContinues) 12.dp else 0.dp),
                                    )
                                } else Text(block.source, style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.fillMaxWidth())
                        }
                    }
                    }
                }
                val atomic = block is ShareRenderBlock.Formula || block is ShareRenderBlock.Table
                val atomicHeight = minOf(writer.pageBodyHeight, CaptureTileHeight)
                if (block is ShareRenderBlock.Table && rows != null && rows.count() > 1 && size.height > atomicHeight) {
                    val middle = (rows.first + rows.last) / 2
                    part(rows.first..middle)
                    part(middle + 1..rows.last)
                    return
                }
                if (atomic) {
                    val scale = minOf(1f, atomicHeight.toFloat() / size.height)
                    val height = ceil(size.height * scale).toInt().coerceAtLeast(1)
                    if (block is ShareRenderBlock.Table && height > writer.remaining) {
                        writer.nextPage()
                        part(rows) // Remeasure with a repeated header on the new physical page.
                        return
                    }
                    writer.ensureRoom(height)
                    val image = renderer.capture(0, height, scale)
                    try { writer.append(image, height) } finally { image.recycle() }
                    if (block is ShareRenderBlock.Table) tablePage = writer.pageNumber
                } else {
                    var top = 0
                    while (top < size.height) {
                        currentCoroutineContext().ensureActive()
                        writer.ensureRoom(minOf(180, size.height - top))
                        val height = minOf(writer.remaining, size.height - top, CaptureTileHeight)
                        val image = renderer.capture(top, height)
                        try {
                            val used = if (top + height == size.height) height else safeShareTileEnd(image)
                            check(used > 0) { context.getString(R.string.share_image_too_long) }
                            writer.append(image, used)
                            top += used
                        } finally { image.recycle() }
                    }
                }
                if (!reasoningContinues) writer.space(24)
            }
            if (block is ShareRenderBlock.Table && block.rowCount > 0) {
                var start = 0
                while (start < block.rowCount) {
                    val last = minOf(start + 12, block.rowCount) - 1
                    part(start..last)
                    start = last + 1
                }
            } else part()
        }
        for ((index, group) in document.groups.withIndex()) {
            if (index > 0) writer.space(24)
            for (message in group.messages) {
                currentCoroutineContext().ensureActive()
                val excerpt = document.excerptFooter.isNotEmpty()
                val user = message.role == MessageRole.USER && !excerpt
                for (section in shareImageText(message, hideReasoning)) {
                    val blocks = shareRenderBlocks(
                        section.text,
                        markdown = shareSectionUsesMarkdown(user, excerpt, section.reasoning),
                    )
                    for ((blockIndex, block) in blocks.withIndex()) {
                        render(block, user, section.reasoning,
                            reasoningContinues = section.reasoning && blockIndex < blocks.lastIndex)
                    }
                }
                for (attachment in message.attachments) {
                    render(ShareRenderBlock.Text(attachment.name, false))
                    if (attachment.isImage) writer.image(attachment)
                    else for (chunk in boundedShareChunks(attachment.data)) render(ShareRenderBlock.Text(chunk, false))
                }
                writer.space(16)
            }
        }
        writer.finish()
    } catch (_: ShareLongImageOverflow) {
        writer.dispose()
        directory.deleteRecursively()
        null
    } catch (error: Throwable) {
        writer.dispose()
        directory.deleteRecursively()
        throw error
    } finally { renderer.close() }
    // Rerender at the ordinary page size so row boundaries and repeated headers remain correct.
    result ?: exportSharedImages(context, document, ShareImageMode.PAGES, accent, hideBranding, hideReasoning,
        typography, typographyFonts, fontScale, systemFontScale)
}

/** Cut only in a blank horizontal band between native text lines, never through glyph ink. */
internal fun safeShareTileEnd(bitmap: Bitmap): Int {
    val start = maxOf(0, bitmap.height - 180)
    val height = bitmap.height - start
    val pixels = IntArray(bitmap.width * height)
    bitmap.getPixels(pixels, 0, bitmap.width, 0, start, bitmap.width, height)
    fun ink(pixel: Int) = Color.alpha(pixel) > 40 && minOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel)) < 180
    // A blockquote's vertical border spans the whole band; it is not a glyph crossing a cut.
    val verticalRule = BooleanArray(bitmap.width) { x ->
        (0 until height).count { row -> ink(pixels[row * bitmap.width + x]) } >= height * .95f
    }
    var blank = 0
    for (y in bitmap.height - 1 downTo start) {
        val hasInk = (0 until bitmap.width).any { x -> !verticalRule[x] && ink(pixels[(y - start) * bitmap.width + x]) }
        blank = if (hasInk) 0 else blank + 1
        if (blank >= 3) return y + 2
    }
    return 0
}

private const val CaptureTileHeight = 2048
private class ShareLongImageOverflow : RuntimeException()

private class ShareImageWriter(private val context: Context, private val directory: File, private val title: String,
    private val height: Int, private val longScreenshot: Boolean, private val hideBranding: Boolean,
    private val excerptFooter: List<String>,
) {
    private val width = 1080
    private val margin = 64
    private val footerHeight = if (excerptFooter.isEmpty()) 100 else 84 + excerptFooter.size * 36
    private val bottom = height - if (hideBranding) 64 else footerHeight + 10
    private var bitmap: Bitmap? = null
    private lateinit var canvas: Canvas
    private var y = 0
    private var bodyTop = 0
    private val files = mutableListOf<File>()
    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    val remaining: Int get() { if (bitmap == null) page(); return (bottom - y).coerceAtLeast(0) }
    val pageNumber: Int get() = files.size
    val pageBodyHeight: Int get() { if (bitmap == null) page(); return bottom - bodyTop }
    private fun page() {
        check(files.size < 80) { context.getString(R.string.share_image_too_long) }
        bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        canvas = Canvas(bitmap!!)
        canvas.drawColor(Color.WHITE)
        paint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        if (!hideBranding) {
            paint.textSize = 26f; paint.color = Color.rgb(77, 107, 254)
            canvas.drawText("SimpleChat", margin.toFloat(), 60f, paint)
        }
        paint.textSize = 32f; paint.color = Color.rgb(32, 34, 40)
        val heading = StaticLayout.Builder.obtain(title, 0, title.length, paint, width - margin * 2)
            .setMaxLines(2).setEllipsize(android.text.TextUtils.TruncateAt.END).setIncludePad(false).build()
        val titleTop = if (hideBranding) 48 else 85
        canvas.save(); canvas.translate(margin.toFloat(), titleTop.toFloat()); heading.draw(canvas); canvas.restore()
        y = titleTop + heading.height + 36
        bodyTop = y
    }
    fun space(amount: Int) { y += amount }
    fun ensureRoom(required: Int) { if (remaining < required) nextPage() }
    fun nextPage() {
        if (longScreenshot) throw ShareLongImageOverflow()
        flush(); page()
    }
    fun append(image: Bitmap, usedHeight: Int) {
        canvas.drawBitmap(image, Rect(0, 0, image.width, usedHeight),
            Rect(margin, y, margin + image.width, y + usedHeight), imagePaint)
        y += usedHeight
    }
    fun image(attachment: Attachment) {
        fun source() = if (attachment.isStored) AttachmentFiles.open(context, attachment)
            else Base64.decode(attachment.data, Base64.DEFAULT).inputStream()
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        source().use { BitmapFactory.decodeStream(it, null, options) }
        check(options.outWidth > 0 && options.outHeight > 0) { context.getString(R.string.error_export_attachment_missing) }
        options.inJustDecodeBounds = false
        options.inSampleSize = 1
        while (maxOf(options.outWidth, options.outHeight) / options.inSampleSize > 1600) options.inSampleSize *= 2
        val image = source().use { BitmapFactory.decodeStream(it, null, options) }
            ?: error(context.getString(R.string.error_export_attachment_missing))
        try {
            val scale = minOf(1f, 952f / image.width, pageBodyHeight.toFloat() / image.height)
            val fittedWidth = (image.width * scale).toInt().coerceAtLeast(1)
            val fittedHeight = (image.height * scale).toInt().coerceAtLeast(1)
            ensureRoom(fittedHeight)
            canvas.drawBitmap(image, null, Rect(margin, y, margin + fittedWidth, y + fittedHeight), imagePaint)
            y += fittedHeight + 24
        } finally { image.recycle() }
    }
    private fun flush() {
        val current = bitmap ?: return
        val actualHeight = minOf(height, y + if (hideBranding) 48 else footerHeight).coerceAtLeast(300)
        if (!hideBranding) {
            paint.color = Color.GRAY; paint.textSize = 23f; paint.typeface = Typeface.DEFAULT
            if (excerptFooter.isEmpty()) {
                canvas.drawText("SimpleChat · ${files.size + 1}", margin.toFloat(), actualHeight - 35f, paint)
            } else {
                excerptFooter.forEachIndexed { index, line ->
                    canvas.drawText(line, margin.toFloat(), actualHeight - 38f - (excerptFooter.lastIndex - index) * 36f, paint)
                }
            }
        }
        val output = if (actualHeight < height) Bitmap.createBitmap(current, 0, 0, width, actualHeight) else current
        try {
            val file = File(directory, "SimpleChat-${(files.size + 1).toString().padStart(3, '0')}.png")
            file.outputStream().use { check(output.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            files += file
        } finally { if (output !== current) output.recycle(); current.recycle(); bitmap = null }
    }
    fun finish(): List<File> { flush(); return files.toList() }
    fun dispose() { bitmap?.recycle(); bitmap = null }
}
