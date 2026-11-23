package com.simplechat.app

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
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
import kotlinx.coroutines.withTimeoutOrNull

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
            return withTimeoutOrNull(5_000) {
                var found = nodes().firstOrNull(matches)
                while (found == null) {
                    delay(50)
                    found = nodes().firstOrNull(matches)
                }
                found
            } ?: error("Missing UI node: $label. Visible: ${nodes().mapNotNull { it.text }.take(12)}")
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

        suspend fun tapText(label: String) = click(revealNode(label) { node ->
            matchesText(node, label) && generateSequence(node) { it.parent }.any { it.isClickable }
        })
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
            val title = revealNode(profileName) { matchesText(it, profileName) }
            val row = generateSequence(title) { it.parent }.firstOrNull { it.isClickable } ?: title
            val rowBounds = Rect().also(row::getBoundsInScreen)
            val label = activity.getString(R.string.api_profile_edit)
            // Every profile has the same pencil description: use the button beside this row.
            click(awaitNode("$label ($profileName)") {
                val bounds = Rect().also(it::getBoundsInScreen)
                it.contentDescription?.toString() == label && bounds.centerY() in rowBounds.top until rowBounds.bottom
            })
        }

        suspend fun toolSwitch(label: String, checked: Boolean) = revealNode(label) {
            (it.isCheckable && it.isChecked == checked && it.contentDescription?.toString() == label) ||
                (matchesText(it, label) && generateSequence(it) { node -> node.parent }.any { node -> node.isClickable })
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

        suspend fun verifyVoiceAndIcon() {
            val apiTitle = activity.getString(R.string.settings_api)
            val voiceApiTitle = activity.getString(R.string.tts_third_party_api)
            val voiceSection = activity.getString(R.string.tts_third_party_section)
            check(nodes().any { matchesText(it, voiceApiTitle) })
            check(nodes().none { matchesText(it, voiceSection) })
            tapText(voiceApiTitle)
            waitForTransition()
            val voiceSwitch = activity.getString(R.string.tts_third_party_enable)
            val presetLabel = activity.getString(R.string.tts_third_party_preset_qwen)
            toolSwitch(voiceSwitch, checked = false)
            check(nodes().none { matchesText(it, presetLabel) })
            click(toolSwitch(voiceSwitch, checked = false))
            revealText(presetLabel)
            tapText(presetLabel)
            withTimeout(5_000) { store.flow.first { it.voiceApi.adapter == "DASHSCOPE_TTS" } }
            waitForTransition()
            if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
            val builtInLabel = activity.getString(R.string.tts_third_party_built_in, 5)
            check(nodes().none { matchesText(it, presetLabel) || matchesText(it, builtInLabel) })
            click(toolSwitch(voiceSwitch, checked = true))
            withTimeout(5_000) { store.flow.first { !it.voiceApi.enabled } }
            waitForTransition()
            withTimeout(5_000) {
                while (nodes().any { matchesText(it, presetLabel) }) {
                    if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
                    delay(100)
                }
            }
            tapBack(toolbar = true)
            waitForTransition()
            awaitText(apiTitle)
            store.setApiKey("fixture-key")
            store.setVoiceApi(com.simplechat.app.data.VoiceApiConfig(
                enabled = true, baseUrl = "https://speech-fixture.invalid", customVoices = listOf("Fixture voice"),
            ))
            tapBack(toolbar = true)
            waitForTransition()
            tapText(activity.getString(R.string.settings_read_aloud))
            waitForTransition()
            revealText("Fixture voice")
            check(nodes().any { matchesText(it, voiceApiTitle) })
            status("Voice configuration is available from Read Aloud; disabled fields are hidden and configured voices appear")
            tapBack(toolbar = true)
            waitForTransition()
            tapText(activity.getString(R.string.settings_appearance))
            waitForTransition()
            val iconStyle = activity.getString(R.string.settings_icon_style)
            val classic = activity.getString(R.string.settings_icon_classic)
            revealText(iconStyle)
            tapText(iconStyle)
            tapText(classic)
            withTimeout(5_000) { store.flow.first { !it.useSimpleCatIcon } }
            awaitText(activity.getString(R.string.settings_icon_restart_title))
            tapText(activity.getString(R.string.action_close))
            waitForTransition()
            revealText(iconStyle)
            revealText(classic)
            status("Icon style menu changes the selection without leaving Appearance")
        }

        try {
            instrumentation.waitForIdleSync(); delay(1200)
            store.migrateApiProfiles()
            val originalSettings = store.flow.first()
            val legacyId = originalSettings.activeApiProfileId
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
            revealText(activity.getString(R.string.service_shared_settings))

            val imageTitle = activity.getString(R.string.image_api_title)
            tapText(imageTitle)
            waitForTransition()
            click(toolSwitch(activity.getString(R.string.image_api_independent), checked = false))
            click(toolSwitch(activity.getString(R.string.image_api_reuse_key), checked = true))
            suspend fun setField(resource: Int, input: String) {
                val label = activity.getString(resource)
                suspend fun fieldBounds(): Rect = Rect().also(revealText(label)::getBoundsInScreen)
                val labelBounds = fieldBounds()
                val editable = nodes().filter { node ->
                    node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }
                }
                val field = editable.firstOrNull { it.contentDescription?.toString() == label }
                    ?: editable.filter { Rect().also(it::getBoundsInScreen).centerY() >= labelBounds.centerY() }
                        .minByOrNull { Rect().also(it::getBoundsInScreen).centerY() }
                    ?: error("Missing editable field: $label")
                val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, input) }
                check(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args))
            }
            setField(R.string.image_api_base_url, "https://image-fixture.invalid/v1")
            suspend fun fillDialog(input: String) {
                val field = revealNode("dialog input") { node ->
                    node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }
                }
                val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, input) }
                if (!field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                    field.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    instrumentation.sendStringSync(input)
                }
                tapText(activity.getString(R.string.action_save))
                waitForTransition()
            }
            tapText(activity.getString(R.string.image_api_key))
            fillDialog("fixture-image-key")
            tapText(activity.getString(R.string.image_api_pick_model))
            tapText(activity.getString(R.string.api_model_manual))
            fillDialog("fixture-image-model")
            revealText(activity.getString(R.string.api_test))
            checkNoSaveButton()
            tapBack(toolbar = true)
            withTimeout(5_000) { store.flow.first { it.imageGenApi.enabled } }
            check(store.flow.first().imageGenApi.key("unused") == "fixture-image-key")
            waitForTransition()
            tapText(imageTitle)
            waitForTransition()
            revealText(activity.getString(R.string.api_profile_key_saved))
            tapBack(toolbar = false)
            waitForTransition()
            awaitText(apiTitle)
            status("Image API: separate key auto-saves on Back and shows the saved status when reopened")

            for (toolbar in listOf(true, false)) {
                tapProfilePencil(profileName)
                waitForTransition()
                awaitText(profileName)
                revealText(renameLabel)
                revealText(providerLabel)
                check(store.flow.first().activeApiProfileId == legacyId) { "Pencil activated the edited profile" }
                tapBack(toolbar)
                waitForTransition()
                awaitText(apiTitle)
                revealText(profileTitle)
                revealText(activity.getString(R.string.service_shared_settings))
            }

            tapText(profileName)
            withTimeout(5_000) { store.flow.first { it.activeApiProfileId == favoriteId } }
            waitForTransition()
            awaitText(apiTitle)
            revealText(activity.getString(R.string.service_shared_settings))
            status("API profiles: pencil opens the inactive profile, both Back controls return to API, and tapping a row switches in place")

            for ((files, mode) in listOf(false to ThemeMode.LIGHT, true to ThemeMode.DARK)) {
                instrumentation.runOnMainSync { themeState.value = mode }
                val title = if (files) filesTitle else searchTitle
                val otherToolTitle = if (files) searchTitle else filesTitle
                val toolbar = !files
                val backName = if (toolbar) "toolbar Back" else "system Back"
                val endpoint = activity.getString(R.string.service_path)
                val enableLabel = activity.getString(if (files) R.string.service_enable_files else R.string.service_enable_search)
                val reuseLabel = activity.getString(R.string.service_reuse_key)

                tapText(title)
                waitForTransition()
                check(nodes().none { matchesText(it, endpoint) }) { "Disabled tool exposed configuration" }
                click(toolSwitch(enableLabel, checked = false))
                toolSwitch(enableLabel, checked = true)
                revealText(endpoint)
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

            tapText(activity.getString(R.string.settings_read_aloud))
            waitForTransition()
            val readAloudLabel = activity.getString(R.string.tts_enabled)
            val voiceDefault = activity.getString(R.string.tts_voice_default)
            revealText(voiceDefault)
            click(toolSwitch(readAloudLabel, checked = true))
            withTimeout(5_000) { store.flow.first { !it.readAloudEnabled } }
            waitForTransition()
            withTimeout(5_000) {
                while (nodes().any { matchesText(it, voiceDefault) }) {
                    if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
                    delay(100)
                }
            }
            tapBack(toolbar = true)
            revealText(activity.getString(R.string.settings_read_aloud_off))
            tapText(activity.getString(R.string.settings_read_aloud))
            waitForTransition()
            click(toolSwitch(readAloudLabel, checked = false))
            withTimeout(5_000) { store.flow.first { it.readAloudEnabled } }
            revealText(voiceDefault)
            status("Read aloud switch hides and restores the voice list")
            verifyVoiceAndIcon()
            store.selectApiProfile(legacyId)
            val legacy = store.flow.first()
            check(legacy.searchApi.enabled && legacy.filesApi.enabled)
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            scope.coroutineContext[Job]!!.cancelAndJoin()
            root.deleteRecursively()
        }
    }
}
