package com.simplechat.app

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import com.simplechat.app.ui.chat.*
import com.simplechat.app.ui.markdown.*
import com.simplechat.app.ui.theme.SimpleChatTheme
import com.simplechat.app.ui.theme.AccentColor
import com.simplechat.app.ui.theme.primary
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import java.io.File
import kotlinx.coroutines.*

internal object NativeShareRenderRegression {
    suspend fun run(instrumentation: Instrumentation, status: (String) -> Unit) {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        instrumentation.runOnMainSync { activity.setContent { SimpleChatTheme { Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.White)) } } }
        try {
            val bubbleDocument = MessageShareDocument("气泡样式", messageShareGroups(listOf(
                UiMessage("bubble-user", MessageRole.USER, "用户消息靠右显示"),
                UiMessage("bubble-ai", MessageRole.ASSISTANT, "回复直接显示正文。"))))
            for (accent in listOf(AccentColor.GREEN, AccentColor.PURPLE)) {
                val output = exportSharedImages(activity, bubbleDocument, ShareImageMode.LONG, accent).single()
                val image = checkNotNull(BitmapFactory.decodeFile(output.path))
                val expected = lerp(androidx.compose.ui.graphics.Color.White, accent.primary(false), .16f).toArgb()
                var minX = image.width
                var maxX = 0
                var minY = image.height
                for (y in 150 until minOf(400, image.height)) for (x in 64 until image.width - 64) {
                    if (image.getPixel(x, y) == expected) {
                        minX = minOf(minX, x); maxX = maxOf(maxX, x); minY = minOf(minY, y)
                    }
                }
                check(minX > image.width / 2 && maxX >= image.width - 67) { "User bubble did not hug right edge: $minX..$maxX" }
                check(minY < 185) { "User role heading still occupies space before bubble: $minY" }
                output.copyTo(File(activity.cacheDir, "share-bubble-${accent.name.lowercase()}.png"), true)
                image.recycle()
            }
            status("short user messages hug the right edge with green/purple theme bubble colors and no role heading space")
            val hiddenFile = exportSharedImages(activity, bubbleDocument, ShareImageMode.LONG,
                AccentColor.GREEN, hideBranding = true).single()
            val hidden = checkNotNull(BitmapFactory.decodeFile(hiddenFile.path))
            val branded = checkNotNull(BitmapFactory.decodeFile(File(activity.cacheDir, "share-bubble-green.png").path))
            check(hidden.height == branded.height - 89) { "Hidden branding retained header/footer space" }
            for (y in hidden.height - 35 until hidden.height) for (x in 0 until hidden.width) {
                check(hidden.getPixel(x, y) == Color.WHITE) { "Branding footer remains visible" }
            }
            for (y in 0 until 90) for (x in 0 until hidden.width) {
                val pixel = hidden.getPixel(x, y)
                check(!(Color.blue(pixel) > Color.red(pixel) + 50 && Color.blue(pixel) > Color.green(pixel) + 50)) {
                    "Branding header remains visible"
                }
            }
            hiddenFile.copyTo(File(activity.cacheDir, "share-bubble-no-branding.png"), true)
            hidden.recycle(); branded.recycle()
            status("hide-branding removes both brand lines and their vertical spacing, preserving title and messages")
            val cutFixture = Bitmap.createBitmap(952, 400, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(cutFixture)
            val black = android.graphics.Paint().apply { color = Color.BLACK }
            canvas.drawRect(0f, 0f, 4f, 400f, black)
            canvas.drawRect(25f, 360f, 800f, 400f, black)
            check(safeShareTileEnd(cutFixture) in 350..360) { "Pagination cut through ink or got stuck on a quote border" }
            cutFixture.recycle()
            val fonts = loadExportFonts(activity)
            val renderer = ShareComposeRenderer.create(activity)
            try {
                val size = renderer.measure {
                    CompositionLocalProvider(LocalMarkdownExport provides MarkdownExportOptions(fonts)) {
                        ExportFormula("\\frac{1}{2}", source = "\$\$\n\\frac{1}{2}\n\$\$")
                    }
                }
                check(size.height > 45 && size.width == 952) { "Formula looks like raw source or empty content: $size" }
                val bitmap = renderer.capture(0, size.height)
                var ink = 0
                for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                    val pixel = bitmap.getPixel(x, y)
                    if (Color.alpha(pixel) > 100 && Color.red(pixel) < 150) ink++
                }
                check(ink > 80) { "Native graphics capture is blank: $ink" }
                File(activity.cacheDir, "share-native-fraction.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
                status("native fraction uses loaded KaTeX glyphs and produces a nonempty bounded graphics capture")
            } finally { renderer.close() }

            val excerptText = "Area \$x^2\$ and fraction \$\\frac{a}{b}\$.\n\n\\(\\ovalbox{项链还戴着吗}\\quad\\text{\\tiny已读}\\)\n\n\\(\\ovalbox{还是还在抽屉里}\\quad\\text{\\tiny已读}\\)\n\n\\(\\ovalbox{我去感应一下}\\quad\\text{\\tiny已读}\\)\n\nGaussian integral:\n\n\$\$\n\\int_{-\\infty}^{\\infty} e^{-x^2}\\,dx = \\sqrt{\\pi}\n\$\$"
            val excerptBlocks = shareRenderBlocks(excerptText, markdown = true)
            val inlineFormulaCount = excerptBlocks.filterIsInstance<ShareRenderBlock.Text>().sumOf { block ->
                inlineMathDocument(block.source).formulas.size
            }
            check(inlineFormulaCount == 5) { "Excerpt lost inline math tokens: $inlineFormulaCount" }
            val excerptDocument = MessageShareDocument("分享摘录", messageShareGroups(listOf(
                UiMessage("excerpt", MessageRole.ASSISTANT, excerptText))),
                excerptFooter = listOf("摘录于 2026-11-20", "回答由 AI 生成"))
            val excerptPage = withTimeout(60_000) { exportSharedImages(activity, excerptDocument).single() }
            val ordinary = withTimeout(60_000) { exportSharedImages(activity, excerptDocument.copy(excerptFooter = emptyList())).single() }
            val excerptBitmap = checkNotNull(BitmapFactory.decodeFile(excerptPage.path))
            val ordinaryBitmap = checkNotNull(BitmapFactory.decodeFile(ordinary.path))
            check(excerptBitmap.width == 1080 && excerptBitmap.height > 300)
            excerptPage.copyTo(File(activity.cacheDir, "share-native-excerpt-math.png"), true)
            val bodyStart = 150
            val bodyEnd = ordinaryBitmap.height - 100
            check(bodyEnd > bodyStart && (bodyStart until bodyEnd).all { y ->
                (64 until 1016).all { x -> excerptBitmap.getPixel(x, y) == ordinaryBitmap.getPixel(x, y) }
            }) { "Excerpt and ordinary share images render different formula bodies" }
            excerptBitmap.recycle()
            ordinaryBitmap.recycle()
            status("assistant excerpt images render three oval read receipts, inline fractions and display integrals")

            val table = "| 项目 | 分式 | 根式 | 矩阵 | 描述 | 最右列 |\n| --- | --- | --- | --- | --- | --- |\n" +
                "| A | \$\\frac{a+b}{c}\$ | \$\\sqrt{x^2+y^2}\$ | \$\\begin{pmatrix}1&2\\\\3&4\\end{pmatrix}\$ | **完整渲染** | RIGHTMOST |\n" +
                "| B | \$\\frac{1}{2}\$ | \$\\sqrt{2}\$ | 单元格 | 普通正文 | END |"
            val body = "# 原生表格与数学公式\n\n$table\n\n" +
                "\$\$\\int_0^1 x^2\\,dx=\\frac{1}{3}\$\$\n\n" +
                "\$\$\\sum_{i=1}^{n}i=\\frac{n(n+1)}{2}\$\$\n\n" +
                "\\(\\ovalbox{图片分享}\\quad\\text{\\tiny已读}\\)\n\n" +
                "\\[\\Ovalbox{\\sqrt{x}}\\quad\\text{\\small 根式}\\]\n\n" +
                "\$\$\\begin{pmatrix}1&2&3\\\\4&5&6\\end{pmatrix}\$\$\n\n后续正文保持正常。\n\n```mermaid\ngraph TD\nA --> B\n```"
            val document = MessageShareDocument("表格与公式完整导出", messageShareGroups(listOf(
                UiMessage("q", MessageRole.USER, "请把表格和公式一起导出。"), UiMessage("a", MessageRole.ASSISTANT, body))))
            val pages = withTimeout(60_000) { exportSharedImages(activity, document) }
            pages.forEachIndexed { index, page -> page.copyTo(File(activity.cacheDir, "share-native-rich-$index.png"), true) }
            check(pages.isNotEmpty())
            status("mixed rich document exported: wide six-column table, math cells, integral, sum and matrix")

            val longTable = "| Row | Description | Last column |\n| --- | --- | --- |\n" +
                (1..70).joinToString("\n") { "| ROW_$it | 完整行 $it，包含 **粗体** 和 \$\\frac{1}{$it}\$ | LAST_$it |" }
            val longDocument = MessageShareDocument("长表格分页", messageShareGroups(listOf(UiMessage("long", MessageRole.ASSISTANT, longTable))))
            val longPages = withTimeout(60_000) { exportSharedImages(activity, longDocument) }
            check(longPages.size >= 2)
            longPages.forEachIndexed { index, file ->
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, options)
                check(options.outWidth == 1080 && options.outHeight in 300..2400)
                file.copyTo(File(activity.cacheDir, "share-native-table-$index.png"), true)
            }
            status("70-row native table exported across ${longPages.size} bounded PNG pages")

            val longImage = withTimeout(90_000) { exportSharedImages(activity, longDocument, ShareImageMode.LONG) }
            val fitsLongPage = longImage.size < longPages.size
            check(longImage.size <= longPages.size)
            val limit = shareImagePageHeight(ShareImageMode.LONG,
                (activity.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager).memoryClass)
            var tall = false
            longImage.forEachIndexed { index, file ->
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, bounds)
                check(bounds.outHeight <= limit)
                tall = tall || bounds.outHeight > 2400
                file.copyTo(File(activity.cacheDir, "share-native-long-$index.png"), true)
            }
            if (fitsLongPage) check(tall)
            else check(longImage.size == longPages.size && !tall)
            status(if (fitsLongPage) "long screenshot mode combines the same table into ${longImage.size} image(s), bounded at $limit pixels"
                else "long screenshot exceeded the device memory budget and safely fell back to ${longImage.size} standard pages")

            val overflowTable = "| Row | Formula | End |\n| --- | --- | --- |\n" +
                (1..180).joinToString("\n") { "| ROW_$it | \$\\frac{1}{$it}\$ | LAST_$it |" }
            val overflowDocument = MessageShareDocument("长截图自动分页", messageShareGroups(listOf(UiMessage("overflow", MessageRole.ASSISTANT, overflowTable))))
            val overflow = withTimeout(120_000) { exportSharedImages(activity, overflowDocument, ShareImageMode.LONG) }
            check(overflow.size > 1)
            overflow.forEach { file ->
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, bounds)
                check(bounds.outHeight <= 2400) { "Long screenshot overflow did not switch to phone-sized pages" }
            }
            status("over-limit long screenshot automatically paginates to ${overflow.size} complete images")

            val decor = activity.window.decorView as android.view.ViewGroup
            val before = withContext(Dispatchers.Main) { decor.childCount }
            coroutineScope {
                val interrupted = launch { exportSharedImages(activity, overflowDocument, ShareImageMode.LONG) }
                delay(80)
                interrupted.cancelAndJoin()
            }
            delay(100)
            check(withContext(Dispatchers.Main) { decor.childCount } == before) { "Cancelled export leaked its Compose host" }
            status("cancelling image generation releases the private Compose capture host")
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
}
