package com.simplechat.app.ui.settings

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import com.simplechat.app.BuildConfig
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.simplechat.app.ui.common.MenuShape
import com.simplechat.app.ui.common.ContainerShape
import com.simplechat.app.ui.common.BubbleShapeOut
import com.simplechat.app.App
import com.simplechat.app.R
import com.simplechat.app.data.AppLanguage
import com.simplechat.app.data.ImportMode
import com.simplechat.app.data.Res
import com.simplechat.app.data.StoredSettings
import com.simplechat.app.net.ModelCatalog
import com.simplechat.app.net.ProviderKind
import com.simplechat.app.ui.common.AppIcons
import com.simplechat.app.ui.common.AppConfirmDialog
import com.simplechat.app.ui.common.AppMenu
import com.simplechat.app.ui.common.AppMenuCheck
import com.simplechat.app.ui.common.AppMenuItem
import com.simplechat.app.ui.chat.Preset
import com.simplechat.app.ui.common.AppSlider
import com.simplechat.app.ui.common.ContainerShape
import com.simplechat.app.ui.common.IconCircleButton
import com.simplechat.app.ui.common.PillShape
import com.simplechat.app.ui.common.screenTransform
import com.simplechat.app.ui.theme.AccentColor
import com.simplechat.app.ui.theme.LocalChatColors
import com.simplechat.app.ui.theme.LocalIsDarkTheme
import com.simplechat.app.ui.theme.ThemeMode
import com.simplechat.app.ui.theme.primary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置页内的一级 / 二级页面。
 *
 * [depth] 只用来判断转场方向：二级页从右侧进来，返回时原路退回。
 */
private enum class SettingsPage(val depth: Int) {
    ROOT(0),
    DATA(1),
    API(1),
    PRESETS(1),
    FONT(1),
    ABOUT(1),
}

private sealed interface SettingsDialog {
    data object Provider : SettingsDialog
    data object BaseUrl : SettingsDialog
    data object Model : SettingsDialog
    data object ApiKey : SettingsDialog
    data object DeleteAll : SettingsDialog
    data object ImportMode : SettingsDialog
    /** 编辑预设；[preset] 为 null 表示新建。 */
    data class PresetEditor(val preset: Preset?) : SettingsDialog
    data class DeletePreset(val preset: Preset) : SettingsDialog
}

/** 字号档位。与官方一致：中间一档为「标准」。 */
private val fontSizeSteps = listOf(
    0.85f to R.string.font_step_small,
    0.925f to R.string.font_step_smaller,
    1.0f to R.string.font_step_standard,
    1.10f to R.string.font_step_larger,
    1.20f to R.string.font_step_large,
    1.30f to R.string.font_step_largest,
)

/**
 * 设置页。
 *
 * 一级列表只放**入口**，具体编辑都进二级页面 —— 一级列表保持一屏内可扫完。
 * 外观例外：只有三个互斥选项，弹个菜单比切页面快。
 *
 * 每个一级入口配**各自不同**的图标（数据管理 / API 配置 / 外观 / 字体大小 /
 * 自动折叠思考 / 关于）；二级页面里的行不再配图标，避免图标语义打架。
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalChatColors.current
    val context = LocalContext.current
    val container = (context.applicationContext as App).container
    val scope = rememberCoroutineScope()
    val store = container.settingsStore
    val repository = container.chatRepository

    var stored by remember { mutableStateOf(StoredSettings()) }
    // rememberSaveable：换语言会 recreate()（见 MainActivity），二级页要停在原地
    var page by rememberSaveable { mutableStateOf(SettingsPage.ROOT) }
    var dialog by remember { mutableStateOf<SettingsDialog?>(null) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var dataMessage by remember { mutableStateOf<String?>(null) }
    var pendingImport by remember { mutableStateOf<ByteArray?>(null) }
    var presets by remember { mutableStateOf<List<Preset>>(emptyList()) }

    LaunchedEffect(Unit) { store.flow.collect { stored = it } }
    LaunchedEffect(Unit) { repository.observePresets().collect { presets = it } }

    // 二级页面按返回键先回一级，而不是直接退出设置
    BackHandler(enabled = page != SettingsPage.ROOT) { page = SettingsPage.ROOT }

    val provider = runCatching { ProviderKind.valueOf(stored.providerId) }
        .getOrDefault(ProviderKind.DEEPSEEK)

    val themeMode = runCatching { ThemeMode.valueOf(stored.themeModeId) }
        .getOrDefault(ThemeMode.SYSTEM)

    val accentColor = runCatching { AccentColor.valueOf(stored.accentColorId) }
        .getOrDefault(AccentColor.BLUE)

    // ── 文件读写：导出 / 导入 ─────────────────────────────

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    val text = repository.exportBackup()
                    context.contentResolver.openOutputStream(uri)?.use {
                        it.write(text.toByteArray())
                    } ?: error(Res.get(R.string.error_write_failed))
                }
            }
            dataMessage = outcome.fold(
                onSuccess = { Res.get(R.string.export_done) },
                onFailure = { Res.get(R.string.export_failed, it.message.toString()) },
            )
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val bytes = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                }.getOrNull()
            }
            if (bytes == null || bytes.isEmpty()) {
                dataMessage = Res.get(R.string.import_read_failed)
            } else {
                // 存**字节**而不是文本：Chatbox 的完整备份是 ZIP，按字符串读会坏掉
                pendingImport = bytes
                dialog = SettingsDialog.ImportMode
            }
        }
    }

    fun startExport() {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        dataMessage = null
        exportLauncher.launch("simplechat-$stamp.json")
    }

    // ── 页面 ──────────────────────────────────────────────

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.settingsBackground),
    ) {
        SettingsTopBar(
            title = when (page) {
                SettingsPage.ROOT -> stringResource(R.string.settings_title)
                SettingsPage.DATA -> stringResource(R.string.settings_data)
                SettingsPage.API -> stringResource(R.string.settings_api)
                SettingsPage.PRESETS -> stringResource(R.string.settings_presets)
                SettingsPage.FONT -> stringResource(R.string.settings_font_size)
                SettingsPage.ABOUT -> stringResource(R.string.settings_about)
            },
            onBack = { if (page == SettingsPage.ROOT) onBack() else page = SettingsPage.ROOT },
        )

        /*
         * 二级页面从右侧滑入、返回原路退回（见 ui/common/Motion.kt）。
         *
         * 滚动容器放在 AnimatedContent **里面**，有两个原因：
         *   1. 转场要在**整屏宽**上滑，放在外层 16dp 内边距里会从内容边缘"挤"出来；
         *   2. 顺带修掉一个真问题 —— 六个页面原先共用同一个 scroll state，
         *      从「设置」滚到底再进「数据管理」，进去就是滚到底的。
         *      现在每页各有一份，且用 SaveableStateHolder 存档，来回切不丢位置。
         */
        val pageStates = rememberSaveableStateHolder()

        AnimatedContent(
            targetState = page,
            transitionSpec = {
                screenTransform(forward = targetState.depth > initialState.depth)
            },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            label = "settingsPage",
        ) { target ->
            pageStates.SaveableStateProvider(target) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp),
                ) {
                    when (target) {
                        SettingsPage.ROOT -> RootPage(
                            stored = stored,
                            presetCount = presets.size,
                            themeMode = themeMode,
                            accentColor = accentColor,
                            language = AppLanguage.fromId(stored.languageId),
                            onOpen = { page = it },
                            onThemeChange = { scope.launch { store.setThemeMode(it.name) } },
                            onAccentChange = { scope.launch { store.setAccentColor(it.name) } },
                            onLanguageChange = { scope.launch { store.setLanguage(it.name) } },
                            onToggleAutoCollapse = {
                                scope.launch { store.setAutoCollapseThinking(it) }
                            },
                        )

                        SettingsPage.DATA -> DataPage(
                            message = dataMessage,
                            onExport = { startExport() },
                            onImport = {
                                // 全放行：Chatbox 的备份是 .zip，有的设备报的 MIME 还很不标准。
                                // 真正的判定放在解析那一步，认不出来会给出明确原因。
                                importLauncher.launch(arrayOf("*/*"))
                            },
                            onDeleteAll = { dialog = SettingsDialog.DeleteAll },
                        )

                        SettingsPage.API -> ApiPage(
                            stored = stored,
                            provider = provider,
                            testing = testing,
                            testResult = testResult,
                            onPick = { dialog = it },
                            onTest = {
                                if (testing) return@ApiPage
                                testing = true
                                testResult = null
                                scope.launch {
                                    val result = container.chatApi.fetchModels(
                                        baseUrl = stored.baseUrl.ifBlank { provider.defaultBaseUrl },
                                        apiKey = stored.apiKey,
                                    )
                                    testResult = result.fold(
                                        onSuccess = { Res.get(R.string.api_test_ok, it.size) },
                                        onFailure = { Res.get(R.string.api_test_failed, it.message.toString()) },
                                    )
                                    testing = false
                                }
                            },
                        )

                        SettingsPage.PRESETS -> PresetsPage(
                            presets = presets,
                            onEdit = { dialog = SettingsDialog.PresetEditor(it) },
                            onDelete = { dialog = SettingsDialog.DeletePreset(it) },
                        )

                        SettingsPage.FONT -> FontPage(
                            stored = stored,
                            onFollowSystem = { scope.launch { store.setFollowSystemFont(it) } },
                            onScaleCommit = { scope.launch { store.setFontScale(it) } },
                        )

                        SettingsPage.ABOUT -> AboutPage()
                    }

                    Spacer(Modifier.size(32.dp))
                    Spacer(Modifier.navigationBarsPadding())
                }
            }
        }
    }

    // ── 弹窗 ──────────────────────────────────────────────

    // 先收进局部变量：dialog 是委托属性，直接 when(dialog) 无法智能转换
    val activeDialog = dialog
    when (activeDialog) {
        SettingsDialog.Provider -> ChoiceDialog(
            title = stringResource(R.string.label_provider),
            options = ProviderKind.entries.map { it to it.displayName },
            selected = provider,
            onDismiss = { dialog = null },
            onSelect = { picked ->
                dialog = null
                scope.launch {
                    store.setProvider(
                        providerId = picked.name,
                        baseUrl = picked.defaultBaseUrl,
                        modelId = picked.defaultModel.ifBlank { stored.modelId },
                    )
                }
            },
        )

        SettingsDialog.BaseUrl -> TextInputDialog(
            title = "Base URL",
            initial = stored.baseUrl.ifBlank { provider.defaultBaseUrl },
            placeholder = "https://api.deepseek.com",
            onDismiss = { dialog = null },
            onConfirm = { value ->
                dialog = null
                scope.launch { store.setBaseUrl(value) }
            },
        )

        SettingsDialog.Model -> ChoiceDialog(
            title = stringResource(R.string.label_model),
            options = ModelCatalog.knownModels(provider).map { it to it.displayName },
            selected = ModelCatalog.knownModels(provider).firstOrNull { it.id == stored.modelId },
            onDismiss = { dialog = null },
            onSelect = { picked ->
                dialog = null
                scope.launch { store.setModel(picked.id) }
            },
        )

        SettingsDialog.ApiKey -> TextInputDialog(
            title = "API Key",
            initial = stored.apiKey,
            placeholder = "sk-…",
            secret = true,
            onDismiss = { dialog = null },
            onConfirm = { value ->
                dialog = null
                scope.launch { store.setApiKey(value) }
            },
        )

        SettingsDialog.DeleteAll -> AppConfirmDialog(
            title = stringResource(R.string.settings_delete_all_title),
            message = stringResource(R.string.settings_delete_all_body),
            confirmText = stringResource(R.string.settings_delete_all_confirm),
            destructive = true,
            onDismiss = { dialog = null },
            onConfirm = {
                dialog = null
                scope.launch {
                    val outcome = runCatching { repository.deleteAllConversations() }
                    dataMessage = outcome.fold(
                        onSuccess = { Res.get(R.string.settings_delete_all_done) },
                        onFailure = { Res.get(R.string.error_delete_failed, it.message.toString()) },
                    )
                }
            },
        )

        SettingsDialog.ImportMode -> ChoiceDialog(
            title = stringResource(R.string.import_mode_title),
            options = listOf(
                ImportMode.MERGE to stringResource(R.string.import_mode_merge),
                ImportMode.OVERWRITE to stringResource(R.string.import_mode_overwrite),
            ),
            selected = null,
            onDismiss = {
                dialog = null
                pendingImport = null
            },
            onSelect = { mode ->
                dialog = null
                val bytes = pendingImport
                pendingImport = null
                if (bytes == null) return@ChoiceDialog
                scope.launch {
                    // 认得两种备份：本应用的 JSON，和 Chatbox 的 ZIP / JSON
                    val outcome = runCatching {
                        repository.importExternal(bytes, context.cacheDir, mode)
                    }
                    dataMessage = outcome.fold(
                        onSuccess = { (result, source) ->
                            Res.get(R.string.import_done, source, result.conversations, result.messages) +
                                if (result.skipped > 0) Res.get(R.string.import_done_skipped, result.skipped) else ""
                        },
                        onFailure = { Res.get(R.string.error_import_failed, it.message.toString()) },
                    )
                }
            },
        )

        is SettingsDialog.PresetEditor -> PresetEditorDialog(
            initial = activeDialog.preset,
            onDismiss = { dialog = null },
            onConfirm = { name, content ->
                val target = activeDialog.preset
                dialog = null
                scope.launch {
                    repository.upsertPreset(
                        preset = Preset(
                            id = target?.id ?: UUID.randomUUID().toString(),
                            name = name,
                            content = content,
                        ),
                        // 新建排在末尾；编辑保持原有位置
                        order = target?.let { presets.indexOfFirst { p -> p.id == it.id } }
                            ?.takeIf { it >= 0 }
                            ?: presets.size,
                    )
                }
            },
        )

        is SettingsDialog.DeletePreset -> AppConfirmDialog(
            title = stringResource(R.string.preset_delete_title, activeDialog.preset.name),
            message = stringResource(R.string.preset_delete_body),
            confirmText = stringResource(R.string.action_delete),
            destructive = true,
            onDismiss = { dialog = null },
            onConfirm = {
                val id = activeDialog.preset.id
                dialog = null
                scope.launch { repository.deletePreset(id) }
            },
        )

        null -> Unit
    }
}

// ══════════════════════════════════════════════════════════
//  一级页面
// ══════════════════════════════════════════════════════════

@Composable
private fun RootPage(
    stored: StoredSettings,
    presetCount: Int,
    themeMode: ThemeMode,
    accentColor: AccentColor,
    language: AppLanguage,
    onOpen: (SettingsPage) -> Unit,
    onThemeChange: (ThemeMode) -> Unit,
    onAccentChange: (AccentColor) -> Unit,
    onLanguageChange: (AppLanguage) -> Unit,
    onToggleAutoCollapse: (Boolean) -> Unit,
) {
    var themeMenuOpen by remember { mutableStateOf(false) }
    var accentMenuOpen by remember { mutableStateOf(false) }
    var languageMenuOpen by remember { mutableStateOf(false) }

    SettingsSectionLabel(stringResource(R.string.settings_group_account))

    SettingsCard {
        SettingsNavRow(
            icon = AppIcons.Database,
            label = stringResource(R.string.settings_data),
            value = stringResource(R.string.settings_data_summary),
            onClick = { onOpen(SettingsPage.DATA) },
        )
        RowDivider()
        SettingsNavRow(
            icon = AppIcons.Server,
            label = stringResource(R.string.settings_api),
            value = null,
            onClick = { onOpen(SettingsPage.API) },
        )
    }

    SettingsSectionLabel(stringResource(R.string.settings_group_personal))

    SettingsCard {
        // 外观：三个互斥选项，直接弹菜单
        Box {
            SettingsNavRow(
                icon = AppIcons.Contrast,
                label = stringResource(R.string.settings_appearance),
                value = when (themeMode) {
                    ThemeMode.SYSTEM -> stringResource(R.string.value_follow_system)
                    ThemeMode.LIGHT -> stringResource(R.string.value_light)
                    ThemeMode.DARK -> stringResource(R.string.value_dark)
                },
                onClick = { themeMenuOpen = true },
            )
            AppMenu(
                expanded = themeMenuOpen,
                onDismissRequest = { themeMenuOpen = false },
            ) {
                ThemeMode.entries.forEach { mode ->
                    AppMenuItem(
                        label = when (mode) {
                            ThemeMode.SYSTEM -> stringResource(R.string.value_follow_system)
                            ThemeMode.LIGHT -> stringResource(R.string.value_light)
                            ThemeMode.DARK -> stringResource(R.string.value_dark)
                        },
                        selected = mode == themeMode,
                        onClick = {
                            themeMenuOpen = false
                            onThemeChange(mode)
                        },
                    )
                }
            }
        }

        RowDivider()

        // 语言：三个互斥选项，弹菜单。换语言 = 改缓存 + 整页重建（见 MainActivity）
        Box {
            SettingsNavRow(
                icon = AppIcons.Globe,
                label = stringResource(R.string.settings_language),
                value = when (language) {
                    AppLanguage.SYSTEM -> stringResource(R.string.value_follow_system)
                    AppLanguage.ZH -> stringResource(R.string.lang_chinese)
                    AppLanguage.EN -> stringResource(R.string.lang_english)
                },
                onClick = { languageMenuOpen = true },
            )
            AppMenu(
                expanded = languageMenuOpen,
                onDismissRequest = { languageMenuOpen = false },
            ) {
                listOf(
                    AppLanguage.SYSTEM to stringResource(R.string.value_follow_system),
                    AppLanguage.ZH to stringResource(R.string.lang_chinese),
                    AppLanguage.EN to stringResource(R.string.lang_english),
                ).forEach { (option, label) ->
                    AppMenuItem(
                        label = label,
                        selected = option == language,
                        onClick = {
                            languageMenuOpen = false
                            onLanguageChange(option)
                        },
                    )
                }
            }
        }

        RowDivider()

        // 主题色：点开弹一排色块
        Box {
            SettingsNavRow(
                icon = AppIcons.Palette,
                label = stringResource(R.string.settings_accent),
                value = accentColor.label,
                onClick = { accentMenuOpen = true },
            )
            AppMenu(
                expanded = accentMenuOpen,
                onDismissRequest = { accentMenuOpen = false },
                // 七个色块横排，比默认菜单宽
                width = AccentMenuWidth,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    AccentColor.entries.forEach { color ->
                        AccentSwatch(
                            color = color,
                            selected = color == accentColor,
                            onClick = {
                                accentMenuOpen = false
                                onAccentChange(color)
                            },
                        )
                    }
                }
            }
        }

        RowDivider()

        SettingsNavRow(
            icon = AppIcons.Prompt,
            label = stringResource(R.string.settings_presets),
            value = if (presetCount == 0) stringResource(R.string.preset_count_empty) else stringResource(R.string.preset_count, presetCount),
            onClick = { onOpen(SettingsPage.PRESETS) },
        )

        RowDivider()

        SettingsNavRow(
            icon = AppIcons.FontSize,
            label = stringResource(R.string.settings_font_size),
            value = stringResource(
                fontSizeSteps
                    .minByOrNull { kotlin.math.abs(it.first - stored.fontScale) }
                    ?.second ?: R.string.font_step_standard,
            ),
            onClick = { onOpen(SettingsPage.FONT) },
        )

        RowDivider()

        SettingsSwitchRow(
            icon = AppIcons.Collapse,
            label = stringResource(R.string.settings_auto_collapse),
            checked = stored.autoCollapseThinking,
            onCheckedChange = onToggleAutoCollapse,
        )
    }

    SettingsSectionLabel(stringResource(R.string.settings_about))

    SettingsCard {
        SettingsNavRow(
            icon = AppIcons.Info,
            label = stringResource(R.string.settings_about_title),
            value = null,
            onClick = { onOpen(SettingsPage.ABOUT) },
        )
    }
}

// ══════════════════════════════════════════════════════════
//  二级：数据管理
// ══════════════════════════════════════════════════════════

@Composable
private fun DataPage(
    message: String?,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onDeleteAll: () -> Unit,
) {
    SettingsSectionLabel(stringResource(R.string.settings_group_backup))

    SettingsCard {
        SettingsActionRow(
            label = stringResource(R.string.settings_export_all),
            hint = stringResource(R.string.settings_export_all_summary),
            onClick = onExport,
        )
        RowDivider()
        SettingsActionRow(
            label = stringResource(R.string.settings_import_all),
            hint = stringResource(R.string.settings_import_all_summary),
            onClick = onImport,
        )
    }

    SettingsSectionLabel(stringResource(R.string.settings_group_reset))

    SettingsCard {
        SettingsActionRow(
            label = stringResource(R.string.settings_delete_all),
            hint = stringResource(R.string.settings_destructive_hint),
            destructive = true,
            onClick = onDeleteAll,
        )
    }

    message?.let {
        Spacer(Modifier.size(12.dp))
        Text(
            text = it,
            style = MaterialTheme.typography.labelMedium,
            color = LocalChatColors.current.placeholder,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

// ══════════════════════════════════════════════════════════
//  二级：API 配置
// ══════════════════════════════════════════════════════════

@Composable
private fun ApiPage(
    stored: StoredSettings,
    provider: ProviderKind,
    testing: Boolean,
    testResult: String?,
    onPick: (SettingsDialog) -> Unit,
    onTest: () -> Unit,
) {
    val colors = LocalChatColors.current

    SettingsSectionLabel(stringResource(R.string.settings_group_service))

    SettingsCard {
        SettingsNavRow(
            icon = null,
            label = stringResource(R.string.label_provider),
            value = provider.displayName,
            onClick = { onPick(SettingsDialog.Provider) },
        )
        RowDivider(inset = 16.dp)
        SettingsNavRow(
            icon = null,
            label = "Base URL",
            value = stored.baseUrl.ifBlank { provider.defaultBaseUrl },
            onClick = { onPick(SettingsDialog.BaseUrl) },
        )
        RowDivider(inset = 16.dp)
        SettingsNavRow(
            icon = null,
            label = stringResource(R.string.label_model),
            value = stored.modelId,
            onClick = { onPick(SettingsDialog.Model) },
        )
    }

    SettingsSectionLabel(stringResource(R.string.settings_group_auth))

    SettingsCard {
        SettingsNavRow(
            icon = null,
            label = "API Key",
            value = if (stored.hasKey) maskKey(stored.apiKey) else stringResource(R.string.api_key_unset),
            onClick = { onPick(SettingsDialog.ApiKey) },
        )
        RowDivider(inset = 16.dp)
        SettingsActionRow(
            label = if (testing) stringResource(R.string.api_testing) else stringResource(R.string.api_test),
            hint = null,
            onClick = onTest,
        )
    }

    testResult?.let {
        Spacer(Modifier.size(12.dp))
        Text(
            text = it,
            style = MaterialTheme.typography.labelMedium,
            color = colors.placeholder,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

// ══════════════════════════════════════════════════════════
//  二级：预设（系统提示词库）
// ══════════════════════════════════════════════════════════

/**
 * 预设 = **一段可命名的系统提示词**（设计文档 §9.1.1①）。
 *
 * 刻意不做结构化字段、不兼容外部角色卡格式 —— 那些约束最终都会限制表达。
 * 一个会话可挂多条，按顺序拼接。
 */
@Composable
private fun PresetsPage(
    presets: List<Preset>,
    onEdit: (Preset?) -> Unit,
    onDelete: (Preset) -> Unit,
) {
    val colors = LocalChatColors.current

    SettingsSectionLabel(stringResource(R.string.settings_group_presets))

    if (presets.isEmpty()) {
        SettingsCard {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 28.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.preset_empty),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.placeholder,
                )
            }
        }
    } else {
        SettingsCard {
            presets.forEachIndexed { index, preset ->
                if (index > 0) RowDivider(inset = 16.dp)
                PresetRow(
                    preset = preset,
                    onEdit = { onEdit(preset) },
                    onDelete = { onDelete(preset) },
                )
            }
        }
    }

    Spacer(Modifier.size(12.dp))

    SettingsCard {
        SettingsActionRow(
            label = stringResource(R.string.preset_new),
            hint = stringResource(R.string.preset_new_hint),
            onClick = { onEdit(null) },
        )
    }

    Spacer(Modifier.size(16.dp))

    Text(
        text = stringResource(R.string.preset_order_hint),
        style = MaterialTheme.typography.labelSmall,
        color = colors.placeholder,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}

@Composable
private fun PresetRow(
    preset: Preset,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = LocalChatColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onEdit)
            .padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = preset.name,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = preset.content,
                style = MaterialTheme.typography.labelSmall,
                color = colors.placeholder,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconCircleButton(
            icon = AppIcons.Trash,
            contentDescription = stringResource(R.string.preset_delete_action),
            onClick = onDelete,
            size = 34.dp,
            iconSize = 16.dp,
            tint = colors.processText,
        )
    }
}

/** 预设编辑器：名称 + 提示词正文。 */
@Composable
private fun PresetEditorDialog(
    initial: Preset?,
    onDismiss: () -> Unit,
    onConfirm: (name: String, content: String) -> Unit,
) {
    val colors = LocalChatColors.current
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var content by remember { mutableStateOf(initial?.content.orEmpty()) }
    val nameFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) { runCatching { nameFocus.requestFocus() } }

    val canSave = name.isNotBlank() && content.isNotBlank()

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(ContainerShape)
                .background(colors.card)
                .padding(20.dp),
        ) {
            Text(
                text = if (initial == null) stringResource(R.string.preset_new) else stringResource(R.string.preset_edit),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.size(14.dp))

            FieldBox {
                BasicTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(nameFocus),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    singleLine = true,
                    decorationBox = { inner ->
                        Box {
                            if (name.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.preset_name_hint),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = colors.placeholder,
                                )
                            }
                            inner()
                        }
                    },
                )
            }

            Spacer(Modifier.size(10.dp))

            FieldBox {
                BasicTextField(
                    value = content,
                    onValueChange = { content = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp, max = 300.dp),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    decorationBox = { inner ->
                        Box {
                            if (content.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.preset_body_hint),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = colors.placeholder,
                                )
                            }
                            inner()
                        }
                    },
                )
            }

            Spacer(Modifier.size(18.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextAction(stringResource(R.string.action_cancel), onClick = onDismiss)
                Spacer(Modifier.size(8.dp))
                TextAction(
                    text = stringResource(R.string.action_save),
                    primary = true,
                    enabled = canSave,
                    onClick = { onConfirm(name.trim(), content.trim()) },
                )
            }
        }
    }
}

@Composable
private fun FieldBox(content: @Composable () -> Unit) {
    val colors = LocalChatColors.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MenuShape)
            .background(colors.fieldBackground)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        content()
    }
}

// ══════════════════════════════════════════════════════════
//  二级：字体大小
// ══════════════════════════════════════════════════════════

@Composable
private fun FontPage(
    stored: StoredSettings,
    onFollowSystem: (Boolean) -> Unit,
    onScaleCommit: (Float) -> Unit,
) {
    val colors = LocalChatColors.current
    val follow = stored.followSystemFont

    // 拖动时只改本地状态做即时预览，松手才落盘 —— 否则一次拖动几十次写 DataStore
    var index by remember(stored.fontScale) {
        mutableStateOf(
            fontSizeSteps.indexOfFirst { it.first == stored.fontScale }.takeIf { it >= 0 } ?: 2,
        )
    }
    val previewScale = fontSizeSteps[index].first
    val baseDensity = LocalDensity.current

    Column(modifier = Modifier.fillMaxWidth()) {
        Spacer(Modifier.size(24.dp))

        // ── 预览：真实气泡，用当前档位的字号渲染 ──────────
        CompositionLocalProvider(
            LocalDensity provides Density(
                density = baseDensity.density,
                fontScale = baseDensity.fontScale * previewScale,
            ),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                Text(
                    text = stringResource(R.string.font_preview_label),
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.onUserBubble,
                    modifier = Modifier
                        .clip(BubbleShapeOut)
                        .background(colors.userBubble)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }

        Spacer(Modifier.size(20.dp))

        Text(
            text = stringResource(R.string.font_scale_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )

        Spacer(Modifier.size(24.dp))

        // ── 底部控制区 ────────────────────────────────────
        SettingsCard {
            SettingsSwitchRow(
                label = stringResource(R.string.value_follow_system),
                icon = null,
                checked = follow,
                onCheckedChange = onFollowSystem,
            )
        }

        Spacer(Modifier.size(16.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "A",
                style = MaterialTheme.typography.labelMedium,
                color = colors.placeholder,
            )
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Text(
                    text = if (follow) stringResource(R.string.value_follow_system) else stringResource(fontSizeSteps[index].second),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (follow) colors.placeholder else MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                )
            }
            Text(
                text = "A",
                style = MaterialTheme.typography.headlineSmall,
                color = colors.placeholder,
            )
        }

        AppSlider(
            value = index.toFloat(),
            onValueChange = { index = it.toInt().coerceIn(0, fontSizeSteps.lastIndex) },
            intervals = fontSizeSteps.lastIndex,
            enabled = !follow,
            valueRange = 0f..fontSizeSteps.lastIndex.toFloat(),
            onValueChangeFinished = { onScaleCommit(fontSizeSteps[index].first) },
        )
    }
}

// ══════════════════════════════════════════════════════════
//  二级：关于
// ══════════════════════════════════════════════════════════

@Composable
private fun AboutPage() {
    SettingsSectionLabel(stringResource(R.string.about_version))

    SettingsCard {
        InfoRow(label = stringResource(R.string.about_version), value = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        RowDivider(inset = 16.dp)
        InfoRow(label = stringResource(R.string.about_package), value = BuildConfig.APPLICATION_ID)
    }

    SettingsSectionLabel(stringResource(R.string.about_licenses))

    SettingsCard {
        InfoRow(label = stringResource(R.string.about_icon), value = "Lucide (ISC)")
        RowDivider(inset = 16.dp)
        InfoRow(label = stringResource(R.string.about_libs_net), value = "OkHttp · kotlinx.serialization")
        RowDivider(inset = 16.dp)
        InfoRow(label = stringResource(R.string.about_libs_storage), value = "Room · DataStore · Jetpack Compose")
        RowDivider(inset = 16.dp)
        InfoRow(label = "Markdown", value = "multiplatform-markdown-renderer (Apache-2.0)")
        RowDivider(inset = 16.dp)
        InfoRow(label = stringResource(R.string.about_libs_parsing), value = "JetBrains markdown · Highlights")
        RowDivider(inset = 16.dp)
        InfoRow(label = stringResource(R.string.about_libs_crypto), value = "Android Keystore (AES-256-GCM)")
    }

    Spacer(Modifier.size(16.dp))

    Text(
        text = stringResource(R.string.about_ai_disclaimer),
        style = MaterialTheme.typography.labelSmall,
        color = LocalChatColors.current.placeholder,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}

// ══════════════════════════════════════════════════════════
//  基础组件
// ══════════════════════════════════════════════════════════

@Composable
fun SettingsTopBar(title: String, onBack: () -> Unit) {
    val colors = LocalChatColors.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .height(56.dp),
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 16.dp)
                .size(36.dp)
                .clip(CircleShape)
                .background(colors.fieldBackground)
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = AppIcons.ChevronLeft,
                contentDescription = stringResource(R.string.action_back),
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(18.dp),
            )
        }

        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.align(Alignment.Center),
        )
    }
}

@Composable
fun SettingsSectionLabel(text: String) {
    val colors = LocalChatColors.current
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = colors.placeholder,
        modifier = Modifier.padding(start = 4.dp, top = 24.dp, bottom = 10.dp),
    )
}

@Composable
fun SettingsCard(content: @Composable () -> Unit) {
    val colors = LocalChatColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MenuShape)
            .background(colors.card),
    ) {
        content()
    }
}

@Composable
private fun RowDivider(inset: androidx.compose.ui.unit.Dp = 52.dp) {
    val colors = LocalChatColors.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = inset)
            .height(1.dp)
            .background(colors.divider),
    )
}

/** 主题色菜单的宽度：七个 30dp 色块 + 间距，比默认菜单宽。 */
private val AccentMenuWidth = 266.dp

/**
 * 主题色菜单里的一个色块。
 *
 * 选中的那个在**外面**套一圈描边，而不是压着色块边缘画 ——
 * 色块本身不因为"选中"而变形，只是外圈标出"当前是这个"。
 *
 * 描边色用 `onSurface`（浅色下近黑、深色下近白）：不写死黑色，
 * 否则深色主题下选中态就看不见了。
 */
@Composable
private fun AccentSwatch(
    color: AccentColor,
    selected: Boolean,
    onClick: () -> Unit,
) {
    // 色块展示的是**当前主题模式下**的那个档位（深色下用提亮过的那一档）
    val dark = LocalIsDarkTheme.current
    val swatchDesc = stringResource(R.string.a11y_accent_color, color.label)
    Box(
        modifier = Modifier.size(30.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(color.primary(dark)),
        )
        if (selected) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape),
            )
        }
        /*
         * 点击层单独一个**圆形**节点，而且放在最上面。
         *
         * ⚠️ 不能把 `clickable` 挂到外层 Box 上：水波纹的形状就是**节点的裁剪形状**，
         * 而外层恰恰**不能裁剪**（选中那圈描边是外溢到 30dp 之外的，裁了就会缺一圈）——
         * 于是波纹就成了一个方块。这里让一层 30dp 的圆来吃点击，波纹自然是圆的。
         */
        Box(
            modifier = Modifier
                .matchParentSize()
                .clip(CircleShape)
                .semantics { contentDescription = swatchDesc }
                .clickable(onClick = onClick),
        )
    }
}

/**
 * 一级入口行：图标 + 标题 +（可选）当前值 + 箭头。
 * 图标传 `null` 即为二级页面里的行 —— 二级不配图标。
 */
@Composable
fun SettingsNavRow(
    icon: ImageVector?,
    label: String,
    value: String?,
    onClick: () -> Unit,
) {
    val colors = LocalChatColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(21.dp),
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            // 标题吃掉全部剩余空间 → 值 + 箭头被顶到最右，形成一条整齐的右边线
            modifier = Modifier.weight(1f),
        )
        if (value != null) {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.placeholder,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End,
                modifier = Modifier.widthIn(max = 190.dp),
            )
        }
        Icon(
            imageVector = AppIcons.ChevronRight,
            contentDescription = null,
            tint = colors.placeholder,
            modifier = Modifier.size(16.dp),
        )
    }
}

/** 二级页面的动作行：标题 +（可选）说明，无图标。 */
@Composable
private fun SettingsActionRow(
    label: String,
    hint: String?,
    onClick: () -> Unit,
    destructive: Boolean = false,
) {
    val colors = LocalChatColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            color = if (destructive) colors.danger else MaterialTheme.colorScheme.onSurface,
        )
        if (hint != null) {
            Text(
                text = hint,
                style = MaterialTheme.typography.labelSmall,
                color = colors.placeholder,
            )
        }
    }
}

/** 开关行。 */
@Composable
private fun SettingsSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    icon: ImageVector? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(21.dp),
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = androidx.compose.ui.graphics.Color.White,
                checkedTrackColor = MaterialTheme.colorScheme.primary,
                checkedBorderColor = MaterialTheme.colorScheme.primary,
            ),
        )
    }
}

/** 只读信息行。 */
@Composable
private fun InfoRow(label: String, value: String) {
    val colors = LocalChatColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.width(16.dp))
        /*
         * 值这一列**必须撑满剩余宽度**（`weight(1f)` 默认 `fill = true`），
         * 否则它只按自然宽度排版，右边缘随文字长短飘 ——
         * 短值停在半路，看起来就像"有的左对齐有的右对齐"。
         *
         * 撑满之后 `TextAlign.End` 才真的把每个值的右边缘对到卡片同一条线上。
         */
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.placeholder,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
    }
}

// ══════════════════════════════════════════════════════════
//  弹窗
// ══════════════════════════════════════════════════════════

@Composable
private fun <T> ChoiceDialog(
    title: String,
    options: List<Pair<T, String>>,
    selected: T?,
    onDismiss: () -> Unit,
    onSelect: (T) -> Unit,
) {
    val colors = LocalChatColors.current
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(ContainerShape)
                .background(colors.card)
                .padding(vertical = 8.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
            )
            options.forEach { (item, label) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(item) }
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (item == selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        modifier = Modifier.weight(1f),
                    )
                    AppMenuCheck(selected = item == selected)
                }
            }
        }
    }
}

@Composable
private fun TextInputDialog(
    title: String,
    initial: String,
    placeholder: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    secret: Boolean = false,
) {
    val colors = LocalChatColors.current
    var value by remember { mutableStateOf(initial) }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(ContainerShape)
                .background(colors.card)
                .padding(20.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.size(14.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(PillShape)
                    .background(colors.fieldBackground)
                    .padding(horizontal = 16.dp, vertical = 13.dp),
            ) {
                BasicTextField(
                    value = value,
                    onValueChange = { value = it },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    singleLine = !secret,
                    visualTransformation = if (secret) {
                        PasswordVisualTransformation()
                    } else {
                        VisualTransformation.None
                    },
                    decorationBox = { inner ->
                        Box {
                            if (value.isEmpty()) {
                                Text(
                                    text = placeholder,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = colors.placeholder,
                                )
                            }
                            inner()
                        }
                    },
                )
            }

            Spacer(Modifier.size(18.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextAction(stringResource(R.string.action_cancel), onClick = onDismiss)
                Spacer(Modifier.size(8.dp))
                TextAction(stringResource(R.string.action_save), primary = true, onClick = { onConfirm(value) })
            }
        }
    }
}

@Composable
private fun TextAction(
    text: String,
    onClick: () -> Unit,
    primary: Boolean = false,
    destructive: Boolean = false,
    enabled: Boolean = true,
) {
    val colors = LocalChatColors.current
    val tint = when {
        !enabled -> colors.placeholder
        destructive -> colors.danger
        primary -> MaterialTheme.colorScheme.primary
        else -> colors.processText
    }
    Box(
        modifier = Modifier
            .clip(PillShape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            color = tint,
        )
    }
}

private fun maskKey(key: String): String {
    if (key.length <= 8) return "••••"
    return key.take(4) + "••••" + key.takeLast(4)
}
