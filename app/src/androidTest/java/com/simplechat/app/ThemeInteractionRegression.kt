package com.simplechat.app

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.simplechat.app.ui.RootScreen
import com.simplechat.app.ui.chat.ContextStats
import com.simplechat.app.ui.chat.InputBar
import com.simplechat.app.ui.chat.VoiceInputState
import com.simplechat.app.ui.theme.AccentColor
import com.simplechat.app.ui.theme.LocalAcrylicEnabled
import com.simplechat.app.ui.theme.LocalFloatingBaseOpacity
import com.simplechat.app.ui.theme.LocalPanelAccentOpacity
import com.simplechat.app.ui.theme.LocalWallpaper
import com.simplechat.app.ui.theme.SimpleChatTheme
import com.simplechat.app.ui.theme.ThemeMode
import com.simplechat.app.ui.theme.WallpaperState
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

internal object ThemeInteractionRegression {
    suspend fun run(instrumentation: Instrumentation, passed: (String) -> Unit) {
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as MainActivity
        try {
            verifyVoice(instrumentation, activity)
            passed("tinted composer keeps recording across the contrast change and submits on release")
            verifySidebar(instrumentation, activity)
            passed("tablet sidebar shows wallpaper with acrylic off and on, and covers it at full opacity")
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    private suspend fun verifyVoice(instrumentation: Instrumentation, activity: MainActivity) {
        var holding by mutableStateOf(false)
        val finishes = mutableListOf<Boolean>()
        instrumentation.runOnMainSync {
            activity.setContent {
                SimpleChatTheme(themeMode = ThemeMode.LIGHT, accent = AccentColor.RED) {
                    CompositionLocalProvider(LocalPanelAccentOpacity provides 0.25f, LocalFloatingBaseOpacity provides 1f) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            InputBar(
                                fieldState = remember { TextFieldState() }, expanded = false, onToggleExpanded = null,
                                thinkingEnabled = false, thinkingPanelOpen = false, onOpenThinkingPanel = {},
                                settingsPanelOpen = false, onOpenSettingsPanel = {}, streaming = false,
                                onSend = {}, onStop = {}, onAttachClick = {},
                                contextStats = ContextStats(0, 0, 0, 0, 0, null, null, null, false),
                                statsPanelOpen = false, onOpenStatsPanel = {}, holdToTalkEnabled = true,
                                voiceState = VoiceInputState(holding = holding),
                                onVoiceHoldStart = { holding = true; true },
                                onVoiceHoldFinish = { finishes.add(it); holding = false },
                            )
                        }
                    }
                }
            }
        }
        val bounds = Rect().also { awaitNode(instrumentation) { it.isEditable }.getBoundsInScreen(it) }
        val down = SystemClock.uptimeMillis()
        fun inject(action: Int) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
                bounds.centerX().toFloat(), bounds.centerY().toFloat(), 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { check(instrumentation.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
        }
        inject(MotionEvent.ACTION_DOWN)
        try {
            delay(ViewConfiguration.getLongPressTimeout() + 450L)
            instrumentation.runOnMainSync {
                check(holding && finishes.isEmpty()) { "Hold ended before release: $finishes" }
            }
        } finally {
            inject(MotionEvent.ACTION_UP)
        }
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync { check(!holding && finishes == listOf(true)) { "Unexpected release: $finishes" } }
    }

    private suspend fun verifySidebar(instrumentation: Instrumentation, activity: MainActivity) {
        val config = activity.resources.configuration
        check(config.screenWidthDp >= 720 && config.screenWidthDp > config.screenHeightDp) { "Run on a wide landscape emulator" }
        val settings = (activity.application as App).container.settingsStore
        check(settings.flow.first().onboardingComplete) { "Complete onboarding on the test emulator first" }
        val file = File.createTempFile("sidebar-wallpaper-", ".png", activity.cacheDir)
        val bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.MAGENTA)
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            var acrylic by mutableStateOf(false)
            var opacity by mutableStateOf(0f)
            instrumentation.runOnMainSync {
                activity.setContent {
                    SimpleChatTheme(themeMode = ThemeMode.LIGHT) {
                        CompositionLocalProvider(
                            LocalWallpaper provides WallpaperState(file = file, opacity = 1f),
                            LocalFloatingBaseOpacity provides opacity,
                            LocalAcrylicEnabled provides acrylic,
                        ) { RootScreen() }
                    }
                }
            }
            val hint = activity.getString(R.string.search_hint)
            val bounds = Rect().also { awaitNode(instrumentation) { it.text?.toString() == hint }.getBoundsInScreen(it) }
            fun seesWallpaper(): Boolean {
                val shot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
                return try {
                    val pixel = shot.getPixel(bounds.centerX(), shot.height * 4 / 5)
                    Color.red(pixel) > 200 && Color.blue(pixel) > 200 && Color.green(pixel) < 100
                } finally { shot.recycle() }
            }
            withTimeout(5_000) { while (!seesWallpaper()) delay(100) }
            instrumentation.runOnMainSync { acrylic = true }
            delay(350)
            check(seesWallpaper()) { "Acrylic hid the wallpaper" }
            instrumentation.runOnMainSync { opacity = 1f }
            instrumentation.waitForIdleSync()
            withTimeout(5_000) { while (seesWallpaper()) delay(100) }
        } finally {
            bitmap.recycle()
            file.delete()
        }
    }

    private suspend fun awaitNode(instrumentation: Instrumentation, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo =
        withTimeout(5_000) {
            fun find(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
                if (node.isVisibleToUser && predicate(node)) return node
                for (i in 0 until node.childCount) node.getChild(i)?.let { find(it)?.let { hit -> return hit } }
                return null
            }
            var match = instrumentation.uiAutomation.rootInActiveWindow?.let(::find)
            while (match == null) {
                delay(50)
                match = instrumentation.uiAutomation.rootInActiveWindow?.let(::find)
            }
            match
        }
}
