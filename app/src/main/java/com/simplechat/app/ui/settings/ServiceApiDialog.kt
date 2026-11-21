package com.simplechat.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.simplechat.app.R
import com.simplechat.app.data.AppTextException
import com.simplechat.app.data.ServiceApiConfig
import com.simplechat.app.data.GEMINI_NANO_BANANA
import com.simplechat.app.data.OPENAI_DEFAULT_IMAGE_MODEL
import com.simplechat.app.data.ImageGenConfig
import com.simplechat.app.data.SettingsStore
import com.simplechat.app.data.StoredSettings
import com.simplechat.app.data.nativeImageAdapter
import com.simplechat.app.data.userMessage
import com.simplechat.app.ui.common.SoftShape
import com.simplechat.app.ui.common.Space
import com.simplechat.app.ui.common.AppSwitch
import com.simplechat.app.ui.common.CapsuleButton
import com.simplechat.app.ui.theme.LocalChatColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun ToolApisPage(stored: StoredSettings, onOpenSearch: () -> Unit, onOpenFiles: () -> Unit, onOpenImage: () -> Unit) {
    val profileName = stored.apiProfiles.firstOrNull { it.id == stored.activeApiProfileId }?.name
    SettingsSectionLabel(stringResource(R.string.service_group))
    SettingsCard {
        SettingsNavRow(null, stringResource(R.string.service_search_title),
            stringResource(if (stored.searchApi.enabled) R.string.service_enabled else R.string.service_disabled),
            onOpenSearch)
        RowDivider(inset = Space.Screen)
        SettingsNavRow(null, stringResource(R.string.service_files_title),
            stringResource(if (stored.filesApi.enabled) R.string.service_enabled else R.string.service_disabled),
            onOpenFiles)
        RowDivider(inset = Space.Screen)
        SettingsNavRow(null, stringResource(R.string.image_api_title),
            when {
                stored.imageGenApi.enabled -> stringResource(
                    when (stored.imageGenApi.adapter) {
                        "GEMINI" -> R.string.image_adapter_gemini
                        "DASHSCOPE" -> R.string.image_adapter_qwen
                        else -> R.string.image_adapter_openai
                    },
                ) + " · " + stored.imageGenApi.modelId.ifBlank { stringResource(R.string.api_model_unset) }
                stored.nativeImageAdapter() != null -> stringResource(R.string.image_api_native_enabled)
                else -> stringResource(R.string.service_disabled)
            }, onOpenImage)
    }
    if (profileName != null) ServiceNote(stringResource(R.string.service_active_profile, profileName))
}

@Composable
internal fun ImageGenApiPage(stored: StoredSettings, store: SettingsStore, onSaved: () -> Unit) {
    val profileId = stored.activeApiProfileId
    key(profileId) {
        val original = remember { stored.imageGenApi }
        val scope = rememberCoroutineScope()
        var enabled by remember { mutableStateOf(original.enabled) }
        var native by remember { mutableStateOf(original.nativeResponsesEnabled) }
        var adapter by remember { mutableStateOf(original.adapter) }
        var reuseKey by remember { mutableStateOf(original.reuseChatKey) }
        var base by remember { mutableStateOf(original.baseUrl) }
        var model by remember { mutableStateOf(original.modelId) }
        var secret by remember { mutableStateOf("") }
        var size by remember { mutableStateOf(original.size) }
        var ratio by remember { mutableStateOf(original.aspectRatio) }
        var geminiSize by remember { mutableStateOf(original.geminiImageSize) }
        var error by remember { mutableStateOf<String?>(null) }
        var saving by remember { mutableStateOf(false) }

        SettingsSectionLabel(stringResource(R.string.image_api_route))
        SettingsCard {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.image_api_independent), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                AppSwitch(enabled, { enabled = it })
            }
            if (enabled) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CapsuleButton("OpenAI Images", { adapter = "OPENAI_IMAGES" }, selected = adapter == "OPENAI_IMAGES")
                    CapsuleButton("Gemini", {
                        if (adapter != "GEMINI") {
                            adapter = "GEMINI"
                            base = "https://generativelanguage.googleapis.com"
                            reuseKey = false
                        }
                    }, selected = adapter == "GEMINI")
                }
                CapsuleButton("Qwen / DashScope", {
                    if (adapter != "DASHSCOPE") {
                        adapter = "DASHSCOPE"
                        base = "https://dashscope.aliyuncs.com"
                        reuseKey = false
                    }
                }, selected = adapter == "DASHSCOPE", modifier = Modifier.padding(horizontal = 12.dp))
                TextField(value = model, onValueChange = { model = it }, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp), label = { Text(stringResource(R.string.image_api_model)) }, placeholder = {
                    when (adapter) {
                        "GEMINI" -> Text(GEMINI_NANO_BANANA)
                        "OPENAI_IMAGES" -> Text(OPENAI_DEFAULT_IMAGE_MODEL)
                        else -> Text("qwen-image-3.0-pro")
                    }
                }, singleLine = true)
                TextField(value = base, onValueChange = { base = it }, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp), label = { Text(stringResource(R.string.image_api_base_url)) }, placeholder = { Text(stringResource(R.string.image_api_chat_base_hint)) }, singleLine = true)
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.image_api_reuse_key), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    AppSwitch(reuseKey, { reuseKey = it })
                }
                if (!reuseKey) TextField(value = secret, onValueChange = { secret = it }, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp), label = { Text(stringResource(R.string.image_api_key)) }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
                if (adapter != "GEMINI") {
                    TextField(value = size, onValueChange = { size = it }, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp), label = { Text(stringResource(R.string.image_api_size)) }, singleLine = true)
                } else {
                    TextField(value = ratio, onValueChange = { ratio = it }, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp), label = { Text(stringResource(R.string.image_api_aspect_ratio)) }, singleLine = true)
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("1K", "2K", "4K").forEach { option -> CapsuleButton(option, { geminiSize = option }, selected = geminiSize == option) }
                    }
                }
            }
        }
        SettingsSectionLabel(stringResource(R.string.image_api_fallback))
        SettingsCard {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.image_api_native_title), style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource(R.string.image_api_native_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                AppSwitch(native, { native = it })
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp)) }
        CapsuleButton(
            text = stringResource(R.string.action_save), onClick = {
                if (saving) return@CapsuleButton
                saving = true
                scope.launch {
                    try {
                        val encrypted = if (secret.isBlank()) stored.imageGenApi.apiKeyEncrypted else store.encryptServiceKey(secret)
                        val next = original.copy(
                            enabled = enabled, adapter = adapter, reuseChatKey = reuseKey,
                            baseUrl = base.trim(), modelId = model.trim(), apiKeyEncrypted = encrypted,
                            nativeResponsesEnabled = native, size = size.trim(), aspectRatio = ratio.trim(),
                            geminiImageSize = geminiSize,
                        )
                        if (next.enabled) {
                            next.connectionBase(stored.baseUrl)
                            next.key(stored.apiKey)
                        }
                        store.setImageGenApi(next, profileId)
                        secret = ""
                        error = null
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        error = failure.userMessage()
                    }
                    finally { saving = false }
                }
            }, enabled = !saving,
        )
    }
}

@Composable
internal fun ServiceApiPage(
    files: Boolean,
    visit: Int,
    stored: StoredSettings,
    store: SettingsStore,
    saving: Boolean,
    onSavingChange: (Boolean) -> Unit,
    onSaved: () -> Unit,
    onBackAction: ((() -> Unit)?) -> Unit,
) {
    // Drafts, especially typed credentials, are never persisted in saved instance state.
    val profileId = stored.activeApiProfileId
    key(profileId, files, visit) {
        val original = remember { if (files) stored.filesApi else stored.searchApi }
        ServiceApiForm(files, original, store, profileId, saving, onSavingChange, onSaved, onBackAction)
    }
}

@Composable
private fun ServiceApiForm(
    files: Boolean,
    original: ServiceApiConfig,
    store: SettingsStore,
    profileId: String,
    saving: Boolean,
    onSavingChange: (Boolean) -> Unit,
    onSaved: () -> Unit,
    onBackAction: ((() -> Unit)?) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(original.enabled) }
    var base by remember { mutableStateOf(original.baseUrl) }
    var reuse by remember { mutableStateOf(original.reuseChatKey) }
    var secret by remember { mutableStateOf("") }
    var path by remember { mutableStateOf(if (files) original.filesPath else original.searchPath) }
    var fetch by remember { mutableStateOf(original.fetchPath) }
    var count by remember { mutableStateOf(original.count.toString()) }
    var purpose by remember { mutableStateOf(original.purpose) }
    var threshold by remember { mutableStateOf(original.longTextChars.toString()) }
    var error by remember { mutableStateOf<String?>(null) }
    var errorAttempt by remember { mutableStateOf(0) }
    var backInProgress by remember { mutableStateOf(false) }
    val errorRequester = remember { BringIntoViewRequester() }
    val registerBack by rememberUpdatedState(onBackAction)
    val notifySaving by rememberUpdatedState(onSavingChange)
    val finish by rememberUpdatedState(onSaved)
    val backAction by rememberUpdatedState<() -> Unit>(back@{
        if (backInProgress || saving) return@back
        backInProgress = true
        val originalPath = if (files) original.filesPath else original.searchPath
        val unchanged = enabled == original.enabled && base == original.baseUrl &&
            reuse == original.reuseChatKey && secret.isBlank() && path == originalPath &&
            fetch == original.fetchPath && count == original.count.toString() &&
            purpose == original.purpose && threshold == original.longTextChars.toString()
        if (unchanged) {
            finish()
            return@back
        }

        notifySaving(true)
        error = null
        scope.launch {
            var saved = false
            try {
                val n = if (files) original.count else count.toIntOrNull()
                    ?.takeIf { it in 1..50 } ?: throw AppTextException(R.string.service_count_invalid)
                val chars = if (!files) original.longTextChars else threshold.toIntOrNull()
                    ?.takeIf { it in 4_000..1_000_000 } ?: throw AppTextException(R.string.service_threshold_invalid)
                val encrypted = if (secret.isBlank()) original.apiKeyEncrypted else store.encryptServiceKey(secret)
                val next = original.copy(
                    enabled = enabled,
                    baseUrl = base.trim(),
                    reuseChatKey = reuse,
                    apiKeyEncrypted = encrypted,
                    searchPath = if (files) original.searchPath else path.trim(),
                    filesPath = if (files) path.trim() else original.filesPath,
                    fetchPath = if (files) original.fetchPath else fetch.trim(),
                    count = n,
                    purpose = purpose,
                    longTextChars = chars,
                )
                if (next != original) {
                    if (enabled && !reuse && encrypted.isBlank()) throw AppTextException(R.string.service_key_missing)
                    store.setServiceApi(files, next, profileId)
                }
                saved = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.userMessage()
                errorAttempt++
            } finally {
                if (!saved) backInProgress = false
                notifySaving(false)
            }
            if (saved) finish()
        }
    })
    DisposableEffect(Unit) {
        registerBack { backAction() }
        onDispose { registerBack(null) }
    }

    error?.let {
        Box(Modifier.bringIntoViewRequester(errorRequester)) { ServiceNote(it, isError = true) }
        LaunchedEffect(errorAttempt) {
            withFrameNanos { }
            errorRequester.bringIntoView()
        }
    }

    SettingsSectionLabel(stringResource(R.string.service_connection))
    SettingsCard {
        SettingsSwitchRow(stringResource(R.string.service_enabled), enabled, { enabled = it })
        RowDivider(inset = Space.Screen)
        ServiceField("Base URL", base, { base = it }, KeyboardType.Uri,
            hint = stringResource(R.string.service_base_hint))
        RowDivider(inset = Space.Screen)
        ServiceField(stringResource(R.string.service_path), path, { path = it }, KeyboardType.Uri)
        if (!files) {
            RowDivider(inset = Space.Screen)
            ServiceField(stringResource(R.string.service_fetch_path), fetch, { fetch = it }, KeyboardType.Uri)
        }
    }
    ServiceNote(stringResource(if (files) R.string.service_files_hint else R.string.service_search_hint))

    SettingsSectionLabel(stringResource(R.string.settings_group_auth))
    SettingsCard {
        SettingsSwitchRow(stringResource(R.string.service_reuse_key), reuse, { reuse = it })
        if (!reuse) {
            RowDivider(inset = Space.Screen)
            ServiceField(stringResource(R.string.service_custom_key), secret, { secret = it }, KeyboardType.Password,
                hint = stringResource(R.string.service_key_hint))
        }
    }

    if (files) {
        SettingsSectionLabel(stringResource(R.string.service_purpose))
        SettingsCard {
            Column(Modifier.selectableGroup()) {
                SettingsChoiceRow(null, stringResource(R.string.service_text_only), purpose == "text_recognition") {
                    purpose = "text_recognition"
                }
                RowDivider(inset = Space.Screen)
                SettingsChoiceRow(null, stringResource(R.string.service_rich_layout), purpose == "rich_layout_recognition") {
                    purpose = "rich_layout_recognition"
                }
            }
            RowDivider(inset = Space.Screen)
            ServiceField(stringResource(R.string.service_text_threshold), threshold, { threshold = it }, KeyboardType.Number)
        }
    } else {
        SettingsSectionLabel(stringResource(R.string.settings_group_search))
        SettingsCard {
            ServiceField(stringResource(R.string.service_count), count, { count = it }, KeyboardType.Number)
        }
    }
}

@Composable
private fun ServiceField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    keyboard: KeyboardType,
    hint: String? = null,
) {
    val colors = LocalChatColors.current
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().padding(horizontal = Space.Screen, vertical = Space.RowPadY),
        verticalArrangement = Arrangement.spacedBy(Space.Gap),
    ) {
        Text(label, style = MaterialTheme.typography.titleSmall, color = scheme.onSurface)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = label },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = keyboard),
            visualTransformation = if (keyboard == KeyboardType.Password) PasswordVisualTransformation() else VisualTransformation.None,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = scheme.onSurface),
            cursorBrush = SolidColor(scheme.primary),
            decorationBox = { field ->
                Box(Modifier.fillMaxWidth().heightIn(min = Space.Row)
                    .clip(SoftShape).background(colors.fieldBackground).padding(horizontal = Space.Screen),
                    contentAlignment = Alignment.CenterStart) { field() }
            },
        )
        if (hint != null) Text(hint, style = MaterialTheme.typography.labelSmall, color = colors.placeholder)
    }
}

@Composable
private fun ServiceNote(text: String, isError: Boolean = false) {
    val colors = LocalChatColors.current
    Text(text, style = MaterialTheme.typography.bodySmall,
        color = if (isError) colors.danger else colors.placeholder,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = Space.RowPadY))
}
