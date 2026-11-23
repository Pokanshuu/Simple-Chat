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
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.platform.LocalContext
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
import com.simplechat.app.data.ImageGenConfig
import com.simplechat.app.data.SettingsStore
import com.simplechat.app.data.StoredSettings
import com.simplechat.app.data.nativeImageAdapter
import com.simplechat.app.data.userMessage
import com.simplechat.app.net.ImageGenerationApi
import com.simplechat.app.ui.common.SoftShape
import com.simplechat.app.ui.common.Space
import com.simplechat.app.ui.theme.LocalChatColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun ToolApisPage(stored: StoredSettings, onOpenSearch: () -> Unit, onOpenFiles: () -> Unit, onOpenImage: () -> Unit) {
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
    ServiceNote(stringResource(R.string.service_shared_settings))
}

@Composable
internal fun ImageGenApiPage(
    visit: Int,
    stored: StoredSettings,
    store: SettingsStore,
    saving: Boolean,
    onSavingChange: (Boolean) -> Unit,
    onBackAction: ((() -> Unit)?) -> Unit,
    onSaved: () -> Unit,
) = key(visit) {
    val original = remember { stored.imageGenApi }
    val initial = if (original.enabled) original.copy(nativeResponsesEnabled = false) else original
    val scope = rememberCoroutineScope()
    val imageApi = remember { ImageGenerationApi() }
    var draft by remember { mutableStateOf(initial) }
    var secret by remember { mutableStateOf("") }
    val reusedBase = stored.baseUrl.takeIf { draft.baseUrl.isBlank() || draft.reuseChatKey }
    val reusedKey = stored.apiKey.takeIf { draft.reuseChatKey }
    var models by remember(draft.adapter, draft.baseUrl, draft.reuseChatKey, secret, reusedBase, reusedKey) {
        val catalogBase = runCatching { draft.copy(baseUrl = draft.baseUrl.trim()).connectionBase(stored.baseUrl) }.getOrNull()
        mutableStateOf(original.modelIds.takeIf { draft.adapter == original.adapter &&
            draft.reuseChatKey == original.reuseChatKey && secret.isBlank() && catalogBase == original.modelCatalogBase }.orEmpty())
    }
    var testStatus by remember(draft.adapter, draft.baseUrl, draft.reuseChatKey, secret, reusedBase, reusedKey) {
        mutableStateOf<Pair<String, Boolean>?>(null)
    }
    var testing by remember { mutableStateOf(false) }
    var pickingModel by remember { mutableStateOf(false) }
    var editingModel by remember { mutableStateOf(false) }
    var editingKey by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var errorAttempt by remember { mutableStateOf(0) }
    var backInProgress by remember { mutableStateOf(false) }
    val registerBack by rememberUpdatedState(onBackAction)
    val notifySaving by rememberUpdatedState(onSavingChange)
    val finish by rememberUpdatedState(onSaved)

    fun configuredDraft(): ImageGenConfig = draft.copy(
        baseUrl = draft.baseUrl.trim(), modelId = draft.modelId.trim(),
        size = draft.size.trim(), aspectRatio = draft.aspectRatio.trim(),
        apiKeyEncrypted = if (secret.isBlank() || draft.reuseChatKey) draft.apiKeyEncrypted else store.encryptServiceKey(secret),
    )

    val backAction by rememberUpdatedState<() -> Unit>(back@{
        if (saving || backInProgress) return@back
        backInProgress = true
        if (draft == original && secret.isBlank() && models == original.modelIds) {
            finish()
            return@back
        }
        notifySaving(true)
        error = null
        scope.launch {
            var saved = false
            try {
                val next = configuredDraft()
                if (next.enabled) {
                    next.connectionBase(stored.baseUrl)
                    if (!next.reuseChatKey) next.key(stored.apiKey)
                }
                store.setImageGenApi(next.copy(
                    modelIds = models,
                    modelCatalogBase = if (models.isEmpty()) "" else next.connectionBase(stored.baseUrl),
                ))
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

    val adapters = listOf(
        "OPENAI_IMAGES" to stringResource(R.string.image_adapter_openai),
        "GEMINI" to stringResource(R.string.image_adapter_gemini),
        "DASHSCOPE" to stringResource(R.string.image_adapter_qwen),
    )
    SettingsSectionLabel(stringResource(R.string.image_api_route))
    SettingsCard {
        SettingsSwitchRow(stringResource(R.string.image_api_independent), draft.enabled,
            { draft = draft.copy(enabled = it, nativeResponsesEnabled = false) },
            enabled = !draft.nativeResponsesEnabled)
        RowDivider(inset = Space.Screen)
        SettingsSwitchRow(stringResource(R.string.image_api_native_title), draft.nativeResponsesEnabled,
            { draft = draft.copy(enabled = false, nativeResponsesEnabled = it) },
            enabled = !draft.enabled)
    }
    if (draft.nativeResponsesEnabled) ServiceNote(stringResource(R.string.image_api_native_hint))
    if (draft.enabled) {
        SettingsSectionLabel(stringResource(R.string.image_api_protocol))
        SettingsCard {
            Column(Modifier.selectableGroup()) {
                adapters.forEachIndexed { index, (adapter, label) ->
                    if (index > 0) RowDivider(inset = Space.Screen)
                    SettingsChoiceRow(null, label, draft.adapter == adapter) {
                        if (draft.adapter != adapter) {
                            val defaultBase = when (adapter) {
                                "GEMINI" -> "https://generativelanguage.googleapis.com"
                                "DASHSCOPE" -> "https://dashscope.aliyuncs.com"
                                else -> ""
                            }
                            draft = draft.copy(adapter = adapter, baseUrl = defaultBase, modelId = "",
                                reuseChatKey = defaultBase.isBlank(), apiKeyEncrypted = "")
                            secret = ""
                        }
                    }
                }
            }
        }
        SettingsSectionLabel(stringResource(R.string.image_api_connection))
        SettingsCard {
            ServiceField(stringResource(R.string.image_api_base_url), draft.baseUrl, { draft = draft.copy(baseUrl = it) },
                KeyboardType.Uri, hint = stringResource(R.string.image_api_chat_base_hint))
            RowDivider(inset = Space.Screen)
            SettingsSwitchRow(stringResource(R.string.image_api_reuse_key), draft.reuseChatKey, { draft = draft.copy(reuseChatKey = it) })
            if (!draft.reuseChatKey) {
                RowDivider(inset = Space.Screen)
                SettingsNavRow(null, stringResource(R.string.image_api_key),
                    stringResource(if (secret.isBlank() && draft.apiKeyEncrypted.isBlank()) R.string.api_key_unset else R.string.api_profile_key_saved),
                    { editingKey = true })
            }
            RowDivider(inset = Space.Screen)
            val testLabel = stringResource(if (testing) R.string.api_testing else R.string.api_test)
            val appContext = LocalContext.current
            SettingsActionRow(label = testLabel, hint = null, enabled = !testing && !saving, onClick = {
                testing = true
                testStatus = null
                scope.launch {
                    try {
                        val found = imageApi.fetchModels(configuredDraft(), stored.baseUrl, stored.apiKey)
                        models = found
                        testStatus = appContext.getString(R.string.api_test_ok, found.size) to false
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        testStatus = appContext.getString(R.string.api_test_failed, failure.userMessage()) to true
                    } finally {
                        testing = false
                    }
                }
            })
        }
        testStatus?.let { (message, failed) -> ServiceNote(message, failed) }
        SettingsSectionLabel(stringResource(R.string.image_api_options))
        SettingsCard {
            SettingsNavRow(null, stringResource(R.string.image_api_pick_model),
                draft.modelId.ifBlank { stringResource(R.string.api_model_unset) }, { pickingModel = true })
            RowDivider(inset = Space.Screen)
            val sizeLabel = stringResource(R.string.image_api_default_size)
            if (draft.adapter == "GEMINI") {
                ServiceField(stringResource(R.string.image_api_aspect_ratio), draft.aspectRatio,
                    { draft = draft.copy(aspectRatio = it) }, KeyboardType.Text)
                RowDivider(inset = Space.Screen)
                Column(Modifier.selectableGroup()) {
                    Text(sizeLabel, style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = Space.Screen, vertical = Space.RowPadY))
                    listOf("1K", "2K", "4K").forEachIndexed { index, size ->
                        if (index > 0) RowDivider(inset = Space.Screen)
                        SettingsChoiceRow(null, size, draft.geminiImageSize == size) { draft = draft.copy(geminiImageSize = size) }
                    }
                }
            } else {
                ServiceField(sizeLabel, draft.size, { draft = draft.copy(size = it) }, KeyboardType.Text)
            }
        }
    }
    if (pickingModel) {
        ChoiceDialog(
            title = stringResource(R.string.image_api_pick_model),
            options = models.map { it to it },
            emptyHint = stringResource(R.string.api_model_empty_hint),
            extraAction = stringResource(R.string.api_model_manual) to { pickingModel = false; editingModel = true },
            selected = draft.modelId,
            onDismiss = { pickingModel = false },
            onSelect = { draft = draft.copy(modelId = it); pickingModel = false },
        )
    }
    if (editingModel) TextInputDialog(
        title = stringResource(R.string.api_model_manual), initial = draft.modelId,
        placeholder = stringResource(R.string.image_api_model),
        onDismiss = { editingModel = false; pickingModel = true },
        onConfirm = { draft = draft.copy(modelId = it.trim()); editingModel = false },
    )
    if (editingKey) TextInputDialog(
        title = stringResource(R.string.image_api_key), initial = secret,
        placeholder = stringResource(R.string.service_key_hint), secret = true,
        onDismiss = { editingKey = false }, onConfirm = { secret = it; editingKey = false },
    )
    error?.let { message ->
        val requester = remember { BringIntoViewRequester() }
        Box(Modifier.bringIntoViewRequester(requester)) { ServiceNote(message, isError = true) }
        LaunchedEffect(errorAttempt) {
            withFrameNanos { }
            requester.bringIntoView()
        }
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
    key(files, visit) {
        val original = remember { if (files) stored.filesApi else stored.searchApi }
        ServiceApiForm(files, original, store, saving, onSavingChange, onSaved, onBackAction)
    }
}

@Composable
private fun ServiceApiForm(
    files: Boolean,
    original: ServiceApiConfig,
    store: SettingsStore,
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
                if (!enabled) {
                    if (original.enabled) store.setServiceApi(files, original.copy(enabled = false))
                } else {
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
                        store.setServiceApi(files, next)
                    }
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

    SettingsCard {
        SettingsSwitchRow(
            stringResource(if (files) R.string.service_enable_files else R.string.service_enable_search),
            enabled,
            { enabled = it; error = null },
        )
    }
    if (!enabled) return

    SettingsSectionLabel(stringResource(R.string.service_connection))
    SettingsCard {
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
