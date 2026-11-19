package com.simplechat.app

import android.app.Instrumentation
import android.content.Intent
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.simplechat.app.ui.markdown.MarkdownText
import com.simplechat.app.ui.theme.SimpleChatTheme
import com.simplechat.app.ui.theme.ThemeMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

internal object CodeFoldRegression {
    suspend fun run(instrumentation: Instrumentation, status: (String) -> Unit) {
        fun openFence(lines: Int) = (1..lines).joinToString("\n", prefix = "```kotlin\n") { "val line$it = $it" }
        val content = mutableStateOf(openFence(20))
        val streaming = mutableStateOf(true)
        val height = AtomicInteger()
        val measuredRevision = AtomicInteger(-1)
        val revision = mutableStateOf(0)
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        suspend fun measuredHeight(expectedRevision: Int): Int {
            withTimeout(5_000) { while (measuredRevision.get() != expectedRevision) delay(20) }
            return height.get().also { check(it > 0) { "Markdown was not measured" } }
        }
        suspend fun update(change: () -> Unit): Int {
            var nextRevision = 0
            instrumentation.runOnMainSync {
                change()
                nextRevision = revision.value + 1
                revision.value = nextRevision
            }
            return measuredHeight(nextRevision)
        }
        try {
            instrumentation.runOnMainSync { activity.setContent {
                SimpleChatTheme(themeMode = ThemeMode.LIGHT) {
                    val density = LocalDensity.current.density
                    CompositionLocalProvider(LocalDensity provides Density(density, fontScale = 1f)) {
                        Column(Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState())) {
                            val currentRevision = revision.value
                            LaunchedEffect(currentRevision) {
                                // Wait for a layout pass even when the new content has the same height.
                                repeat(2) { withFrameNanos { } }
                                measuredRevision.set(currentRevision)
                            }
                            MarkdownText(
                                content = content.value,
                                streaming = streaming.value,
                                modifier = Modifier.fillMaxWidth().onSizeChanged { height.set(it.height) },
                            )
                        }
                    }
                }
            } }
            val beforeFold = measuredHeight(0)
            val atFold = update { content.value = openFence(21) }
            check(atFold < beforeFold) { "The live Markdown kept its pre-collapse height ($beforeFold → $atFold px)" }

            for (lineCount in listOf(40, 120, 300)) {
                val foldedHeight = update { content.value = openFence(lineCount) }
                check(abs(foldedHeight - atFold) <= 1) {
                    "Folded Markdown height changed to $foldedHeight px after $lineCount lines (initially $atFold px)"
                }
            }

            val stoppedHeight = update { streaming.value = false }
            check(abs(stoppedHeight - atFold) <= 1) {
                "Stopping changed the folded height ($atFold → $stoppedHeight px)"
            }
            status("Open code fence shrinks while streaming ($beforeFold to $atFold px), stays folded through 300 lines and on stop")
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
