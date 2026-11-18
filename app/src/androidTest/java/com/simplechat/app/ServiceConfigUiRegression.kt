package com.simplechat.app

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Rect
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.ui.Modifier
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.simplechat.app.data.SettingsStore
import com.simplechat.app.ui.settings.SettingsScreen
import com.simplechat.app.ui.theme.SimpleChatTheme
import com.simplechat.app.ui.theme.ThemeMode
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

internal object ServiceConfigUiRegression {
    suspend fun run(instrumentation: Instrumentation, status: (String) -> Unit) {
        val root = File(instrumentation.targetContext.cacheDir, "service-ui-${UUID.randomUUID()}").apply { mkdirs() }
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
            return output.filter { it.isVisibleToUser }
        }

        suspend fun awaitNode(label: String, matches: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
            return withTimeout(5_000) {
                var found = nodes().firstOrNull(matches)
                while (found == null) {
                    delay(50)
                    found = nodes().firstOrNull(matches)
                }
                checkNotNull(found) { "Missing UI node: $label" }
            }
        }

        fun matchesText(node: AccessibilityNodeInfo, label: String): Boolean =
            node.text?.toString()?.let { it == label || it.startsWith("$label, ") } == true

        fun click(node: AccessibilityNodeInfo) {
            var target: AccessibilityNodeInfo? = node
            while (target != null && !target.isClickable) target = target.parent
            check(target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) { "Not clickable: ${node.text}" }
        }

        suspend fun waitForTransition() {
            instrumentation.waitForIdleSync()
            delay(350)
        }

        fun scroll(current: List<AccessibilityNodeInfo>, action: Int): Boolean =
            current.any { it.isScrollable && it.performAction(action) }

        suspend fun revealNode(label: String, matches: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
            for (direction in listOf(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) {
                for (attempt in 0 until 10) {
                    val current = nodes()
                    current.firstOrNull(matches)?.let { return it }
                    if (!scroll(current, direction)) break
                    waitForTransition()
                }
            }
            return awaitNode(label, matches)
        }

        suspend fun tapText(label: String) = click(revealNode(label) { it.isClickable && matchesText(it, label) })
        suspend fun awaitText(label: String) = awaitNode(label) { matchesText(it, label) }
        suspend fun revealText(label: String) = revealNode(label) { matchesText(it, label) }
        suspend fun tapBack(toolbar: Boolean) {
            if (toolbar) {
                val label = activity.getString(R.string.action_back)
                click(awaitNode(label) { it.contentDescription?.toString() == label })
            } else {
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            }
        }

        suspend fun tapProfilePencil(profileName: String) {
            val row = revealNode(profileName) { it.isClickable && matchesText(it, profileName) }
            val rowBounds = Rect().also(row::getBoundsInScreen)
            val label = activity.getString(R.string.api_profile_edit)
            // Every profile has the same pencil description: use the button beside this row.
            click(awaitNode("$label ($profileName)") {
                val bounds = Rect().also(it::getBoundsInScreen)
                it.contentDescription?.toString() == label && bounds.centerY() in rowBounds.top until rowBounds.bottom
            })
        }

        suspend fun toolSwitch(label: String, checked: Boolean) = revealNode(label) {
            it.isCheckable && it.isChecked == checked && it.contentDescription?.toString() == label
        }

        suspend fun checkNoSaveButton() {
            val label = activity.getString(R.string.action_save)
            repeat(10) {
                val current = nodes()
                check(current.none { it.isClickable && matchesText(it, label) }) { "Unexpected tool form Save button" }
                if (!scroll(current, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return
                waitForTransition()
            }
            error("Tool form did not scroll to the bottom")
        }

        try {
            instrumentation.waitForIdleSync(); delay(1200)
            store.migrateApiProfiles()
            val originalSettings = store.flow.first()
            val legacyId = originalSettings.activeApiProfileId
            val legacyName = originalSettings.apiProfiles.single { it.id == legacyId }.name
            val profileName = "Fixture tool profile"
            store.createApiProfile(profileName)
            store.setApiKey("fixture-key")
            val favoriteId = store.flow.first().activeApiProfileId
            store.selectApiProfile(legacyId)
            val themeState = androidx.compose.runtime.mutableStateOf(ThemeMode.LIGHT)
            instrumentation.runOnMainSync {
                activity.setContent {
                    SimpleChatTheme(themeMode = themeState.value) {
                        SettingsScreen(onBack = {}, modifier = Modifier, store = store)
                    }
                }
            }
            val apiTitle = activity.getString(R.string.settings_api)
            val profileTitle = activity.getString(R.string.api_profiles_title)
            val toolTitle = activity.getString(R.string.service_group)
            val searchTitle = activity.getString(R.string.service_search_title)
            val filesTitle = activity.getString(R.string.service_files_title)
            val renameLabel = activity.getString(R.string.api_profile_rename)
            val providerLabel = activity.getString(R.string.label_provider)
            val rootTitle = activity.getString(R.string.settings_title)

            tapText(apiTitle)
            waitForTransition()
            revealText(profileTitle)
            revealText(toolTitle)
            revealText(activity.getString(R.string.service_active_profile, legacyName))

            for (toolbar in listOf(true, false)) {
                tapProfilePencil(profileName)
                waitForTransition()
                awaitText(profileName)
                revealNode(renameLabel) { it.isClickable && matchesText(it, renameLabel) }
                revealText(providerLabel)
                check(store.flow.first().activeApiProfileId == legacyId) { "Pencil activated the edited profile" }
                tapBack(toolbar)
                waitForTransition()
                awaitText(apiTitle)
                revealText(profileTitle)
                revealText(activity.getString(R.string.service_active_profile, legacyName))
            }

            tapText(profileName)
            withTimeout(5_000) { store.flow.first { it.activeApiProfileId == favoriteId } }
            waitForTransition()
            awaitText(apiTitle)
            revealText(activity.getString(R.string.service_active_profile, profileName))
            status("API profiles: pencil opens the inactive profile, both Back controls return to API, and tapping a row switches in place")

            for ((files, mode) in listOf(false to ThemeMode.LIGHT, true to ThemeMode.DARK)) {
                instrumentation.runOnMainSync { themeState.value = mode }
                val title = if (files) filesTitle else searchTitle
                val otherToolTitle = if (files) searchTitle else filesTitle
                val toolbar = !files
                val backName = if (toolbar) "toolbar Back" else "system Back"
                val endpoint = activity.getString(R.string.service_path)
                val enableLabel = activity.getString(R.string.service_enabled)
                val reuseLabel = activity.getString(R.string.service_reuse_key)

                tapText(title)
                waitForTransition()
                revealNode(endpoint) { it.isEditable && it.contentDescription?.toString() == endpoint }
                click(toolSwitch(enableLabel, checked = false))
                toolSwitch(enableLabel, checked = true)
                waitForTransition()
                instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                    File(instrumentation.targetContext.cacheDir, if (files) "service-files-dark.png" else "service-search-light.png")
                        .outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                }
                checkNoSaveButton()

                // Turn off key reuse without ever opening the keyboard. Both Back controls must reject it.
                click(toolSwitch(reuseLabel, checked = true))
                toolSwitch(reuseLabel, checked = false)
                tapBack(toolbar)
                revealText(activity.getString(R.string.service_key_missing))
                awaitText(title)
                val unchanged = store.flow.first().let { if (files) it.filesApi else it.searchApi }
                check(!unchanged.enabled && unchanged.reuseChatKey) { "$backName persisted an invalid draft" }
                toolSwitch(enableLabel, checked = true)
                click(toolSwitch(reuseLabel, checked = false))
                toolSwitch(reuseLabel, checked = true)

                tapBack(toolbar)
                withTimeout(5_000) { store.flow.first { if (files) it.filesApi.enabled else it.searchApi.enabled } }
                waitForTransition()
                awaitText(apiTitle)
                revealText(otherToolTitle)
                tapText(title)
                waitForTransition()
                toolSwitch(enableLabel, checked = true)
                tapBack(toolbar = !toolbar)
                waitForTransition()
                awaitText(apiTitle)
                revealText(profileTitle)
                status("Tool API: $title rendered in $mode; $backName blocked invalid changes, then saved and returned to API")
            }

            tapBack(toolbar = false)
            waitForTransition()
            awaitText(rootTitle)
            tapText(apiTitle)
            waitForTransition()
            revealText(profileTitle)
            tapBack(toolbar = true)
            waitForTransition()
            awaitText(rootTitle)
            status("API page: both Back controls return to Settings")
            store.selectApiProfile(legacyId)
            val legacy = store.flow.first()
            check(!legacy.searchApi.enabled && !legacy.filesApi.enabled)
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            scope.coroutineContext[Job]!!.cancelAndJoin()
            root.deleteRecursively()
        }
    }
}
