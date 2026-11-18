package com.simplechat.app

import android.app.Instrumentation
import android.content.Intent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.simplechat.app.data.*
import com.simplechat.app.ui.settings.ServiceApiDialog
import com.simplechat.app.ui.theme.SimpleChatTheme
import com.simplechat.app.ui.theme.ThemeMode
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

internal object ServiceConfigUiRegression {
    suspend fun run(instrumentation: Instrumentation, status: (String) -> Unit) {
        val root = File(instrumentation.targetContext.cacheDir, "service-ui-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = SettingsStore(instrumentation.targetContext,
            PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(root, "prefs.preferences_pb") }))
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        fun nodes(): List<AccessibilityNodeInfo> {
            val output = mutableListOf<AccessibilityNodeInfo>()
            fun scan(node: AccessibilityNodeInfo?) {
                if (node == null) return
                output += node
                for (index in 0 until node.childCount) scan(node.getChild(index))
            }
            scan(instrumentation.uiAutomation.rootInActiveWindow)
            return output
        }
        try {
            instrumentation.waitForIdleSync(); delay(1200)
            store.migrateApiProfiles(); store.setApiKey("fixture-key")
            for ((files, theme) in listOf(false to ThemeMode.LIGHT, true to ThemeMode.DARK)) {
                val stored = store.flow.first()
                val visible = mutableStateOf(true)
                instrumentation.runOnMainSync { activity.setContent { SimpleChatTheme(themeMode = theme) {
                    if (visible.value) ServiceApiDialog(files, stored, store) { visible.value = false }
                } } }
                instrumentation.waitForIdleSync(); delay(400)
                val title = Res.get(if (files) R.string.service_files_title else R.string.service_search_title)
                withTimeout(5000) { while (nodes().none { it.text?.toString() == title ||
                    it.text?.toString() == activity.getString(if (files) R.string.service_files_title else R.string.service_search_title) }) delay(100) }
                val toggle = nodes().firstOrNull { it.isCheckable && !it.isChecked }
                    ?: error("Missing enable switch")
                check(toggle.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                instrumentation.waitForIdleSync(); delay(200)
                instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                    File(instrumentation.targetContext.cacheDir, if (files) "service-files-dark.png" else "service-search-light.png")
                        .outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                }
                val ok = nodes().first { it.text?.toString() == instrumentation.targetContext.getString(android.R.string.ok) }
                var button: AccessibilityNodeInfo? = ok
                while (button != null && !button.isClickable) button = button.parent
                check(button?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
                withTimeout(5000) { while (withContext(Dispatchers.Main) { visible.value }) delay(50) }
                val result = store.flow.first()
                check(if (files) result.filesApi.enabled else result.searchApi.enabled)
                status("tool API dialog: $title rendered in $theme, switch/save persisted successfully")
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            scope.coroutineContext[Job]!!.cancelAndJoin(); root.deleteRecursively()
        }
    }
}
