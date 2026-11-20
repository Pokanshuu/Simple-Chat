package com.simplechat.app

import android.app.Instrumentation
import android.content.Intent
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.simplechat.app.ui.markdown.*
import com.simplechat.app.ui.theme.SimpleChatTheme
import com.simplechat.app.ui.theme.ThemeMode
import java.io.File
import kotlinx.coroutines.*

internal object MathLayoutRegression {
    suspend fun run(instrumentation: Instrumentation, status: (String) -> Unit) {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val source = """
            ## 💯 考试时的标准书写（直接抄这个格式）：

            > 解：对于任意给定的 \(\varepsilon > 0\)，
            > 要使 \(|a_n - 0| < \varepsilon\)，即 \(\left|\frac{1+(-1)^n}{n}\right| < \varepsilon\)。
            >
            > 由于 \((-1)^n\) 的值只能为 \(1\) 或 \(-1\)，
            > 所以 \(|1+(-1)^n|\le 2\)。
            > 因此，只需 \(\frac{2}{n}<\varepsilon\)，即 \(n>\frac{2}{\varepsilon}\) 即可。
            >
            > 故取 \(N=\left\lceil\frac{2}{\varepsilon}\right\rceil\)（注：考试时写 \(N=\lfloor\frac{2}{\varepsilon}\rfloor+1\) 更严谨）。
            > 当 \(n>N\) 时，必定有 \(|a_n-0|\le\frac{2}{n}<\varepsilon\) 成立。

            - 列表内公式 \(x^2+y^2=z^2\) 与 **加粗正文**连续排版。
            - 行内代码 `echo ${'$'}PATH` 保持原样。

            \(\ovalbox{项链还戴着吗}\quad\text{\tiny已读}\)
            \(\ovalbox{还是还在抽屉里}\quad\text{\tiny已读}\)
            \(\ovalbox{我去感应一下}\quad\text{\tiny已读}\)

            \[\ovalbox{\frac{1}{2}}\quad\text{\small 中文}\]

            \(\underbrace{${"x+".repeat(60)}x}_{\text{一行长公式}}\)
        """.trimIndent()
        try {
            instrumentation.waitForIdleSync(); delay(1000)
            for (theme in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) {
                val hits = SelectionTextHits()
                instrumentation.runOnMainSync { activity.setContent { SimpleChatTheme(themeMode = theme) {
                    Surface { Column(Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp)) {
                        CompositionLocalProvider(LocalSelectionTextHits provides hits) { MarkdownWithMath(source) }
                    } }
                } } }
                instrumentation.waitForIdleSync()
                withTimeout(10_000) { while (withContext(Dispatchers.Main) { hits.layouts().none { it.placeholderRects.size >= 3 } }) delay(100) }
                val layouts = withContext(Dispatchers.Main) { hits.layouts() }
                val proof = layouts.first { it.layoutInput.text.text.contains("解：") }
                check(proof.placeholderRects.filterNotNull().size >= 3)
                val before = proof.layoutInput.text.text.indexOf("给定的")
                val epsilon = proof.layoutInput.text.text.indexOf("varepsilon")
                check(before >= 0 && epsilon > before)
                check(proof.getLineForOffset(before) == proof.getLineForOffset(epsilon)) { "Inline epsilon was split from its sentence" }
                val bubbles = layouts.filter { it.layoutInput.text.text.contains("\\ovalbox") }
                check(bubbles.size == 3 && bubbles.all { it.placeholderRects.singleOrNull() != null }) {
                    "Oval boxes and their labels must be rendered together"
                }
                val wide = layouts.single { it.layoutInput.text.text.contains("\\underbrace") }
                val wideSlot = checkNotNull(wide.placeholderRects.singleOrNull()) { "Oversized inline formula disappeared" }
                check(wideSlot.right <= wide.size.width + 1) { "Inline formula exceeds the paragraph" }
                check(layouts.sumOf { it.lineCount } < 24) { "Math layout is still fragmented" }
                delay(300)
                instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                    File(instrumentation.targetContext.cacheDir, "math-mixed-${theme.name.lowercase()}.png")
                        .outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
                }
                status("$theme: inline fractions, oval boxes, long formulas, quotes and lists preserved; ${layouts.sumOf { it.lineCount }} text lines")
            }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
}
