package com.simplechat.app.ui.markdown

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.hrm.latex.renderer.measure.rememberLatexMeasurer
import com.hrm.latex.renderer.model.LatexConfig
import com.hrm.latex.renderer.model.LatexFontFamilies
import com.hrm.latex.renderer.model.LatexTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.ceil

/** Export-only options: the ordinary chat renderer keeps its scrolling and controls. */
internal class MarkdownExportOptions(
    val fonts: LatexFontFamilies,
    val tableRows: IntRange? = null,
    val tableMetrics: ExportTableMetrics = ExportTableMetrics(),
    val tableHeader: Boolean = true,
)
internal class ExportTableMetrics { var widths: List<Dp>? = null }
internal val LocalMarkdownExport = staticCompositionLocalOf<MarkdownExportOptions?> { null }

/** Fit all columns / the whole formula. No horizontal viewport or clipping in an exported card. */
@Composable
internal fun ExportFitWidth(content: @Composable () -> Unit) {
    Layout(content = { Box { content() } }) { children, constraints ->
        val child = children.single().measure(Constraints(maxWidth = 16_384))
        val scale = minOf(1f, constraints.maxWidth.toFloat() / child.width.coerceAtLeast(1))
        val width = ceil(child.width * scale).toInt()
        val height = ceil(child.height * scale).toInt()
        layout(width, height) {
            child.placeWithLayer(0, 0) {
                scaleX = scale; scaleY = scale; transformOrigin = TransformOrigin(0f, 0f)
            }
        }
    }
}

/** Same native measure/draw engine as Latex(), with explicit fonts and synchronous readiness. */
@Composable
internal fun ExportFormula(tex: String, source: String = tex, fontSize: androidx.compose.ui.unit.TextUnit = 17.sp) {
    val options = checkNotNull(LocalMarkdownExport.current)
    val presentation = remember(tex) { prepareLatexPresentation(tex) }
    val config = LatexConfig(fontSize = fontSize, fontFamilies = options.fonts,
        theme = LatexTheme.light(color = MaterialTheme.colorScheme.onBackground))
    val measurer = rememberLatexMeasurer(config)
    val color = MaterialTheme.colorScheme.onBackground
    val density = LocalDensity.current
    val inline = remember(presentation, measurer, config, color, density) {
        measurer.inlinePresentation(presentation, config, dark = false, color = color, density = density)
    }
    if (inline == null) {
        // The same unsupported-syntax fallback as chat; supported formulas never go through text.
        BasicText(source, style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onBackground))
    } else ExportFitWidth {
        FormulaInlineText(inline, source, MaterialTheme.typography.bodyLarge.copy(fontSize = fontSize))
    }
}

private val exportFontMutex = Mutex()
private var exportFonts: LatexFontFamilies? = null

/** Await the bundled KaTeX fonts instead of taking a screenshot of the async fallback font. */
internal suspend fun loadExportFonts(context: Context): LatexFontFamilies = withContext(Dispatchers.IO) {
    exportFontMutex.withLock {
        exportFonts ?: run {
            val assets = context.applicationContext.assets
            val root = "composeResources/com.hrm.latex.fonts.katex.resources/font/"
            val bytes = mutableMapOf<String, ByteArray>()
            fun font(name: String, weight: FontWeight = FontWeight.Normal, style: FontStyle = FontStyle.Normal): Font {
                val path = "$root$name.ttf"
                bytes[name] = assets.open(path).use { it.readBytes() }
                return Font(assetManager = assets, path = path, weight = weight, style = style)
            }
            val main = FontFamily(font("katex_main_regular"), font("katex_main_bold", FontWeight.Bold),
                font("katex_main_italic", style = FontStyle.Italic), font("katex_main_bolditalic", FontWeight.Bold, FontStyle.Italic))
            val math = FontFamily(font("katex_math_italic", style = FontStyle.Italic), font("katex_math_bolditalic", FontWeight.Bold, FontStyle.Italic))
            val ams = FontFamily(font("katex_ams_regular"))
            val sans = FontFamily(font("katex_sansserif_regular"), font("katex_sansserif_bold", FontWeight.Bold), font("katex_sansserif_italic", style = FontStyle.Italic))
            val mono = FontFamily(font("katex_typewriter_regular"))
            val cal = FontFamily(font("katex_caligraphic_regular"), font("katex_caligraphic_bold", FontWeight.Bold))
            val fraktur = FontFamily(font("katex_fraktur_regular"), font("katex_fraktur_bold", FontWeight.Bold))
            val script = FontFamily(font("katex_script_regular"))
            val sizes = (1..4).map { FontFamily(font("katex_size${it}_regular")) }
            LatexFontFamilies(main, math, ams, sans, mono, cal, fraktur, script,
                sizes[0], sizes[1], sizes[2], sizes[3],
                bytes["katex_main_regular"], bytes["katex_math_italic"], bytes["katex_ams_regular"],
                bytes["katex_size1_regular"], bytes["katex_size2_regular"], bytes["katex_size3_regular"], bytes["katex_size4_regular"])
                .also { exportFonts = it }
        }
    }
}
