package com.simplechat.app.ui.settings

import android.content.Intent
import java.io.File
import android.net.Uri
import android.provider.DocumentsContract
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.compose.runtime.collectAsState
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
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
import com.simplechat.app.data.ApiKey
import com.simplechat.app.net.UpdateChecker
import com.simplechat.app.data.AppLanguage
import com.simplechat.app.data.ImportMode
import com.simplechat.app.data.Res
import com.simplechat.app.data.StoredSettings
import com.simplechat.app.data.ApiProfile
import com.simplechat.app.data.userMessage
import com.simplechat.app.data.copyExportFile
import com.simplechat.app.data.stageArchive
import com.simplechat.app.net.ModelCatalog
import com.simplechat.app.net.DeepSeekTts
import com.simplechat.app.net.ProviderKind
import com.simplechat.app.ui.common.AppIcons
import com.simplechat.app.ui.common.AppConfirmDialog
import com.simplechat.app.ui.common.AppMenu
import com.simplechat.app.ui.common.AppMenuCheck
import com.simplechat.app.ui.common.AppMenuItem
import com.simplechat.app.ui.chat.Preset
import com.simplechat.app.ui.chat.SpeechVoice
import com.simplechat.app.ui.chat.loadSpeechVoices
import com.simplechat.app.ui.chat.rememberSpeechOutput
import com.simplechat.app.ui.common.AppSlider
import com.simplechat.app.ui.common.AppSwitch
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 设置页内的一级 / 二级页面。
 *
 * [depth] 只用来判断转场方向：二级页从右侧进来，返回时原路退回。
 */
private enum class SettingsPage(val depth: Int) {
    ROOT(0),
    DATA(1),
    API(1),
    API_PROFILES(2),
    PRESETS(1),
    FONT(1),
    /** 朗读音色（系统 TTS）。 */
    VOICE(1),
    ABOUT(1),
    /** 开源许可与版权清单（关于 → 二级）。 */
    LICENSES(2),
}

private sealed interface SettingsDialog {
    data object Provider : SettingsDialog
    data object BaseUrl : SettingsDialog
    data object Model : SettingsDialog
    /** 手动填写模型 id：端点没实现 `/models`、或列表里没有目标模型时的出口。 */
    data object ModelManual : SettingsDialog
    data object ApiKey : SettingsDialog
    data class ApiProfileName(val profile: ApiProfile? = null, val copyCurrent: Boolean = false) : SettingsDialog
    data class DeleteApiProfile(val profile: ApiProfile) : SettingsDialog
    data object DeleteAll : SettingsDialog
    data object ImportMode : SettingsDialog
    /** 协议通道（自动 / OpenAI 兼容 / Anthropic 兼容）。 */
    data object ApiStyle : SettingsDialog
    /** 地区（时间地点提醒里的地点那一项）。 */
    data object Location : SettingsDialog
    /** 编辑预设；[preset] 为 null 表示新建。 */
    data class PresetEditor(val preset: Preset?) : SettingsDialog
    data class DeletePreset(val preset: Preset) : SettingsDialog
}

/**
 * 协议通道的**可选档位**（持久化的是枚举名）。
 *
 * 与 `net.ApiStyle` 刻意分开：那个是"实际走哪条"，只有两档；
 * 这里的 `AUTO` 是用户意图（"你看着办"），落库后由 `ApiStyle.resolve` 按端点解析。
 * 把 `AUTO` 塞进 `ApiStyle` 会让"实际通道"变成可选值，到处都是 null 判断。
 */
private enum class ApiStylePreference(val labelRes: Int) {
    AUTO(R.string.api_style_auto),
    OPENAI(R.string.api_style_openai),
    ANTHROPIC(R.string.api_style_anthropic),
    ;

    companion object {
        fun fromId(id: String?): ApiStylePreference =
            entries.firstOrNull { it.name == id } ?: AUTO
    }
}

/** 字号档位。与官方一致：中间一档为「标准」。 */private val fontSizeSteps = listOf(
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
    var profileError by remember { mutableStateOf<String?>(null) }
    fun updateProfile(action: suspend () -> Unit) {
        scope.launch {
            try { action(); profileError = null }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { profileError = error.userMessage() }
        }
    }
    var dataMessage by remember { mutableStateOf<String?>(null) }
    var pendingImport by remember { mutableStateOf<File?>(null) }
    // 导入进度：null = 没在导入；0..1 = 解析进度（拷贝阶段是 indeterminate）
    val importProgress = remember { MutableStateFlow<Float?>(null) }
    val importPercent by importProgress.collectAsState()
    // 读文件 / 开包阶段：没有可用进度，界面给循环进度条 + 「读取中」
    var importReading by remember { mutableStateOf(false) }
    val archiveProgress = remember { MutableStateFlow<Float?>(null) }
    val archivePercent by archiveProgress.collectAsState()
    var archiveSaving by remember { mutableStateOf(false) }
    var pendingArchive by remember { mutableStateOf<File?>(null) }
    DisposableEffect(Unit) {
        onDispose { pendingArchive?.delete(); pendingImport?.delete() }
    }
    var presets by remember { mutableStateOf<List<Preset>>(emptyList()) }

    /*
     * 朗读音色。
     *
     * - 清单要**临时起一个 TTS 引擎**才拿得到（见 loadSpeechVoices），所以只在
     *   进二级页时读一次，读到就缓存到页面关闭；
     * - [speaker] 只用于「试听」与选完顺手念一句，不参与对话页的朗读。
     */
    var speechVoices by remember { mutableStateOf<List<SpeechVoice>?>(null) }

    /*
     * DeepSeek 官方 TTS（/v1/tools/tts，音色 stella / tide）。
     *
     * 只在「服务商 = DeepSeek 官方 + Base URL 指向官方域 + Key 已填」时可用 ——
     * 那个端点是官方域专有的。可用时列在音色页最前面；不可用时整块不出现，
     * 只留一句说明（不支持的 API 不做入口，也不在点了之后才报错）。
     */
    val officialTtsConfig = remember(stored.providerId, stored.apiKey) {
        if (DeepSeekTts.isOfficialTtsVoiceAvailable(stored.providerId, stored.apiKey)) {
            DeepSeekTts.Config(stored.apiKey)
        } else {
            null
        }
    }
    val officialVoices = remember(officialTtsConfig) {
        if (officialTtsConfig == null) {
            emptyList()
        } else {
            DeepSeekTts.Voice.entries.map { voice ->
                SpeechVoice(
                    name = DeepSeekTts.storageNameOf(voice.id),
                    label = Res.get(voice.labelRes),
                    // 官方合成走网络（不是设备引擎），标"需联网"符合用户预期
                    needsNetwork = true,
                )
            }
        }
    }

    val speaker = rememberSpeechOutput(stored.ttsVoiceName, officialTtsConfig)
    /** 朗读相关的报错（引擎缺失 / 合成失败），显示在音色页的说明位置。 */
    var speechError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(speaker.error) {
        speaker.error?.let {
            speechError = it
            speaker.consumeError()
        }
    }
    LaunchedEffect(page) {
        if (page == SettingsPage.VOICE && speechVoices == null) {
            speechError = null
            /*
             * 加超时兜底：个别设备的 TTS 引擎初始化回调可能**永远不来**
             * （引擎被禁用 / 服务被冻结）。不设限的话这一页会永远停在「正在读取…」，
             * 而用户完全不知道该怎么办。超时按"没有可用引擎"处理，至少给一句提示。
             */
            speechVoices = withTimeoutOrNull(TtsVoiceLoadTimeoutMs) {
                loadSpeechVoices(context)
            } ?: emptyList()
        }
    }

    LaunchedEffect(Unit) { store.flow.collect { stored = it } }
    LaunchedEffect(Unit) { repository.observePresets().collect { presets = it } }

    // 二级页面按返回键先回一级，而不是直接退出设置
    fun previousPage() { page = if (page == SettingsPage.API_PROFILES) SettingsPage.API else SettingsPage.ROOT }
    BackHandler(enabled = page != SettingsPage.ROOT) { previousPage() }
    LaunchedEffect(stored.activeApiProfileId, stored.baseUrl) { testResult = null }

    val provider = runCatching { ProviderKind.valueOf(stored.providerId) }
        .getOrDefault(ProviderKind.DEEPSEEK)

    val themeMode = runCatching { ThemeMode.valueOf(stored.themeModeId) }
        .getOrDefault(ThemeMode.SYSTEM)

    val accentColor = runCatching { AccentColor.valueOf(stored.accentColorId) }
        .getOrDefault(AccentColor.BLUE)

    // ── 文件读写：导出 / 导入 ─────────────────────────────

    fun savePreparedExport(uri: Uri?) {
        val staged = pendingArchive
        pendingArchive = null
        if (uri == null) {
            staged?.delete()
            archiveProgress.value = null
            dataMessage = null
            return
        }
        scope.launch {
            archiveSaving = true
            archiveProgress.value = 0f
            var saved = false
            try {
                checkNotNull(staged) { Res.get(R.string.export_failed_short) }
                withContext(Dispatchers.IO) {
                    copyExportFile(staged, openDestination = {
                        context.contentResolver.openOutputStream(uri, "wt")
                            ?: error(Res.get(R.string.error_write_failed))
                    }, onProgress = { archiveProgress.value = it })
                }
                saved = true
                dataMessage = Res.get(R.string.export_done)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                dataMessage = Res.get(R.string.export_failed, error.userMessage())
            } finally {
                withContext(NonCancellable + Dispatchers.IO) {
                    staged?.delete()
                    if (!saved) runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
                }
                archiveSaving = false
                archiveProgress.value = null
            }
        }
    }

    fun prepareExport(extension: String, launch: (String) -> Unit, generate: suspend (File) -> Unit) {
        if (archiveProgress.value != null || importProgress.value != null || importReading) return
        archiveSaving = false
        archiveProgress.value = 0f
        dataMessage = null
        scope.launch {
            var staged: File? = null
            try {
                val target = File.createTempFile("prepared-export-", ".$extension", context.cacheDir)
                staged = target
                withContext(Dispatchers.IO) {
                    generate(target)
                    check(target.length() > 0) { Res.get(R.string.export_failed_short) }
                }
                pendingArchive = target
                val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
                launch("simplechat-$stamp.$extension")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                pendingArchive = null
                archiveProgress.value = null
                dataMessage = Res.get(R.string.export_failed, error.userMessage())
            } finally {
                if (pendingArchive !== staged) withContext(NonCancellable + Dispatchers.IO) { staged?.delete() }
            }
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri: Uri? -> savePreparedExport(uri) }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            /*
             * **流式**落到缓存文件，不再整包读进内存。
             *
             * 实测一份 228MB 的 DeepSeek 导出包：`readBytes()` 会直接 OOM，
             * 或者卡到用户以为"点了没反应"。这里改成按块拷贝 —— 内存占用是常量，
             * 之后 `ZipFile` 直接按中央目录读这个文件。
             */
            importReading = true
            dataMessage = null
            var staged: File? = null
            try {
                val file = withContext(Dispatchers.IO) {
                    val target = File.createTempFile("import-", ".bak", context.cacheDir)
                    staged = target
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        target.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                output.write(buffer, 0, count)
                            }
                        }
                    } ?: error(Res.get(R.string.import_read_failed))
                    check(target.length() > 0L) { Res.get(R.string.import_read_failed) }
                    target
                }
                pendingImport = file
                dialog = SettingsDialog.ImportMode
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                dataMessage = Res.get(R.string.error_import_failed, error.userMessage())
            } finally {
                if (pendingImport !== staged) withContext(NonCancellable + Dispatchers.IO) { staged?.delete() }
                importReading = false
            }
        }
    }

    fun startExport() = prepareExport("json", { exportLauncher.launch(it) }) { file ->
        repository.exportBackup(file, onProgress = { archiveProgress.value = it })
    }

    fun startReportExport() = prepareExport("json", { exportLauncher.launch(it) }) { file ->
        val report = repository.lastImportReportFile()
            ?: error(Res.get(R.string.import_report_missing))
        report.inputStream().use { input -> file.outputStream().use { input.copyTo(it) } }
        archiveProgress.value = 1f
    }

    val crashLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) {
        uri: Uri? -> savePreparedExport(uri)
    }
    fun startCrashExport() = prepareExport("txt", { crashLauncher.launch(it) }) { file ->
        val report = com.simplechat.app.data.CrashDiagnostics.file(context)
            ?: error(Res.get(R.string.crash_report_missing))
        report.inputStream().use { input -> file.outputStream().use { input.copyTo(it) } }
        archiveProgress.value = 1f
    }

    val archiveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri: Uri? -> savePreparedExport(uri) }

    fun startArchiveExport() = prepareExport("zip", { archiveLauncher.launch(it) }) { file ->
        stageArchive(file) { repository.exportArchive(it) { p -> archiveProgress.value = p } }
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
                SettingsPage.API_PROFILES -> stringResource(R.string.api_profiles_title)
                SettingsPage.PRESETS -> stringResource(R.string.settings_presets)
                SettingsPage.FONT -> stringResource(R.string.settings_font_size)
                SettingsPage.VOICE -> stringResource(R.string.tts_voice)
                SettingsPage.ABOUT -> stringResource(R.string.settings_about)
                SettingsPage.LICENSES -> stringResource(R.string.about_licenses)
            },
            onBack = {
                if (archiveProgress.value == null && importProgress.value == null && !importReading) {
                    if (page == SettingsPage.ROOT) onBack() else previousPage()
                }
            },
        )
        BackHandler(enabled = archivePercent != null || importPercent != null || importReading) { }

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
                            progress = importPercent,
                            reading = importReading,
                            exportProgress = archivePercent,
                            exportSaving = archiveSaving,
                            onExport = { startExport() },
                            onExportArchive = { startArchiveExport() },
                            onExportReport = { startReportExport() },
                            onExportCrash = { startCrashExport() },
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
                            onProfiles = { page = SettingsPage.API_PROFILES },
                            profileError = profileError,
                            onWebSearch = { enabled ->
                                scope.launch { store.setWebSearch(enabled) }
                            },
                            onTest = {
                                if (testing) return@ApiPage
                                testing = true
                                testResult = null
                                val source = stored
                                scope.launch {
                                    try {
                                    val result = container.chatApi.fetchModels(
                                        baseUrl = source.baseUrl.ifBlank { provider.defaultBaseUrl },
                                        apiKey = source.apiKey,
                                    )
                                    // 拉到的列表**必须落库**：它就是「模型」选择器的数据源，
                                    // 早先只报了个条数、列表丢掉，于是自定义端点明明"连接成功"，
                                    // 点开「模型」却是一个只有标题的白框。
                                    result.onSuccess { ids -> store.setModelIds(ids, source.activeApiProfileId, source.baseUrl) }
                                    if (stored.activeApiProfileId == source.activeApiProfileId && stored.baseUrl == source.baseUrl) testResult = result.fold(
                                        onSuccess = { Res.get(R.string.api_test_ok, it.size) },
                                        onFailure = { Res.get(R.string.api_test_failed, it.userMessage()) },
                                    )
                                    } catch (error: CancellationException) { throw error }
                                    catch (error: Exception) { profileError = error.userMessage() }
                                    finally { testing = false }
                                }
                            },
                        )

                        SettingsPage.API_PROFILES -> ApiProfilesPage(
                            stored = stored, error = profileError,
                            onSelect = { profile -> updateProfile { store.selectApiProfile(profile.id); page = SettingsPage.API } },
                            onNew = { dialog = SettingsDialog.ApiProfileName() },
                            onCopy = { dialog = SettingsDialog.ApiProfileName(copyCurrent = true) },
                            onRename = { dialog = SettingsDialog.ApiProfileName(profile = it) },
                            onDelete = { dialog = SettingsDialog.DeleteApiProfile(it) },
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

                        SettingsPage.ABOUT -> AboutPage(
                            onOpenLicenses = { page = SettingsPage.LICENSES },
                        )

                        SettingsPage.LICENSES -> LicensesPage()

                        SettingsPage.VOICE -> VoicePage(
                            selectedName = stored.ttsVoiceName,
                            voices = speechVoices,
                            officialVoices = officialVoices,
                            officialAvailable = officialTtsConfig != null,
                            previewing = speaker.previewing,
                            error = speechError,
                            onSelect = { voice ->
                                scope.launch { store.setTtsVoice(voice?.name.orEmpty(), voice?.label.orEmpty()) }
                                // 选完顺手念一句：省得用户"选了但不知道变了没有"
                                speaker.preview(Res.get(R.string.tts_voice_preview_text), voice?.name.orEmpty())
                            },
                            onPreview = { voice ->
                                speaker.preview(Res.get(R.string.tts_voice_preview_text), voice?.name.orEmpty())
                            },
                        )
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
                updateProfile { store.selectProvider(picked) }
            },
        )

        is SettingsDialog.ApiProfileName -> TextInputDialog(
            title = stringResource(if (activeDialog.profile != null) R.string.api_profile_rename
                else if (activeDialog.copyCurrent) R.string.api_profile_copy else R.string.api_profile_new),
            initial = activeDialog.profile?.name ?: if (activeDialog.copyCurrent)
                stored.apiProfiles.firstOrNull { it.id == stored.activeApiProfileId }?.name.orEmpty() +
                    stringResource(R.string.conversation_fork_suffix) else "",
            placeholder = stringResource(R.string.api_profile_name_hint),
            onDismiss = { dialog = null },
            onConfirm = { value ->
                if (value.isNotBlank()) {
                    dialog = null
                    updateProfile {
                        if (activeDialog.profile != null) store.renameApiProfile(activeDialog.profile.id, value)
                        else { store.createApiProfile(value, activeDialog.copyCurrent); page = SettingsPage.API }
                    }
                }
            },
        )

        is SettingsDialog.DeleteApiProfile -> AppConfirmDialog(
            title = stringResource(R.string.api_profile_delete),
            message = stringResource(R.string.api_profile_delete_body, activeDialog.profile.name),
            confirmText = stringResource(R.string.action_delete),
            destructive = true,
            onDismiss = { dialog = null },
            onConfirm = { dialog = null; updateProfile { store.deleteApiProfile(activeDialog.profile.id) } },
        )

        SettingsDialog.BaseUrl -> TextInputDialog(
            title = "Base URL",
            initial = stored.baseUrl.ifBlank { provider.defaultBaseUrl },
            placeholder = "https://api.deepseek.com",
            onDismiss = { dialog = null },
            onConfirm = { value ->
                dialog = null
                updateProfile { store.setBaseUrl(value) }
            },
        )

        SettingsDialog.Model -> {
            /*
             * 选项 = **这个端点拉到的模型**（落库的那份）在前，内置能力表补在后。
             *
             * 早先只读 `knownModels(provider)`：自定义服务商的内置目录是空的，
             * 于是「测试连接」显示"可用模型 45 个"、点开「模型」却什么都没有 ——
             * 拉取结果根本没进界面。
             */
            val models = ModelCatalog.pickerModels(provider, stored.modelIds)
            ChoiceDialog(
                title = stringResource(R.string.label_model),
                options = models.map { it to it.displayName },
                selected = models.firstOrNull { it.id == stored.modelId },
                emptyHint = stringResource(R.string.api_model_empty_hint),
                extraAction = stringResource(R.string.api_model_manual) to {
                    dialog = SettingsDialog.ModelManual
                },
                onDismiss = { dialog = null },
                onSelect = { picked ->
                    dialog = null
                    scope.launch { store.setModel(picked.id) }
                },
            )
        }

        SettingsDialog.ModelManual -> TextInputDialog(
            title = stringResource(R.string.api_model_manual),
            initial = stored.modelId,
            // 例子用**当前服务商的默认模型**：用户照抄一个多半就是能用的
            placeholder = provider.defaultModel.ifBlank { ProviderKind.DEEPSEEK.defaultModel },
            onDismiss = { dialog = null },
            onConfirm = { value ->
                dialog = null
                val id = value.trim()
                // 空值等于"没选"，不要拿它覆盖已选模型
                if (id.isNotEmpty()) scope.launch { store.setModel(id) }
            },
        )

        SettingsDialog.ApiKey -> TextInputDialog(
            title = "API Key",
            initial = stored.apiKey,
            placeholder = "sk-…",
            secret = true,
            /*
             * 粘进来的东西经常带换行、空格，甚至整段中文占位符。HTTP 头只接受可见 ASCII，
             * 不拦的话错误会以 `Unexpected char 0x4f55 at 8 in Authorization value`
             * 的形式出现在对话里 —— 用户完全看不懂（见 `ApiKey`）。
             */
            validate = { raw ->
                val clean = ApiKey.normalize(raw)
                val illegal = ApiKey.firstIllegalChar(clean)
                if (illegal == null) {
                    null
                } else {
                    Res.get(
                        R.string.api_key_illegal_chars,
                        "0x%04x".format(illegal.code),
                    )
                }
            },
            onDismiss = { dialog = null },
            onConfirm = { value ->
                dialog = null
                updateProfile { store.setApiKey(value) }
            },
        )

        SettingsDialog.ApiStyle -> ChoiceDialog(
            title = stringResource(R.string.settings_api_style),
            options = ApiStylePreference.entries.map { it to stringResource(it.labelRes) },
            selected = ApiStylePreference.fromId(stored.apiStyleId),
            onDismiss = { dialog = null },
            onSelect = { picked ->
                dialog = null
                scope.launch { store.setApiStyle(picked.name) }
            },
        )

        SettingsDialog.Location -> TextInputDialog(
            title = stringResource(R.string.settings_location),
            initial = stored.locationRegion,
            // 占位符要给出**可照抄的例子**，而不是复述"这是干什么用的"
            placeholder = stringResource(R.string.settings_location_placeholder),
            onDismiss = { dialog = null },
            onConfirm = { value ->
                dialog = null
                scope.launch { store.setLocationRegion(value) }
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
                        onFailure = { Res.get(R.string.error_delete_failed, it.userMessage()) },
                    )
                }
            },
        )

        SettingsDialog.ImportMode -> ChoiceDialog(
            title = stringResource(R.string.import_mode_title),
            options = listOf(
                ImportMode.MERGE to stringResource(R.string.import_mode_merge),
                ImportMode.REPLACE_SAME to stringResource(R.string.import_mode_replace_same),
                ImportMode.RENAME to stringResource(R.string.import_mode_rename),
            ),
            selected = null,
            onDismiss = {
                dialog = null
                pendingImport?.delete()
                pendingImport = null
            },
            onSelect = { mode ->
                dialog = null
                val file = pendingImport
                pendingImport = null
                if (file == null) return@ChoiceDialog
                scope.launch {
                    dataMessage = Res.get(R.string.import_working)
                    importProgress.value = 0f
                    // 认得三种备份：本应用的 JSON / ZIP、Chatbox 的 ZIP / JSON、DeepSeek 导出
                    try {
                        withContext(Dispatchers.IO) {
                            val (_, summary) = repository.importExternal(file, context.cacheDir, mode) { p ->
                                // 回调来自 IO 线程：StateFlow 是线程安全的，界面侧 collectAsState 收。
                                // 0 就先不给 —— 停在 0% 看着像卡死，等真有进展再显示百分比
                                importProgress.value = p.coerceIn(0f, 1f)
                            }
                            dataMessage = summary
                        }
                    } catch (error: CancellationException) { throw error }
                    catch (error: Exception) { dataMessage = Res.get(R.string.error_import_failed, error.userMessage()) }
                    finally {
                        importProgress.value = null
                        withContext(NonCancellable + Dispatchers.IO) { file.delete() }
                    }
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

        RowDivider()

        // 朗读音色：系统 TTS 引擎提供，进二级页挑选（见 VoicePage）
        SettingsNavRow(
            icon = AppIcons.Volume,
            label = stringResource(R.string.tts_voice),
            value = stored.ttsVoiceLabel.ifBlank { stringResource(R.string.tts_voice_default) },
            onClick = { onOpen(SettingsPage.VOICE) },
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
//  二级：朗读音色
// ══════════════════════════════════════════════════════════

/**
 * 朗读音色选择。
 *
 * 为什么是"列表 + 试听"而不是下拉框：音色名是 `zh-cn-x-ccc-local` 这种机器标识，
 * 光看名字根本分不出区别 —— 唯一的判断方式就是**听一句**。所以每一行都带试听，
 * 选中后也顺手念一遍（见调用处）。
 */
@Composable
private fun VoicePage(
    selectedName: String,
    voices: List<SpeechVoice>?,
    /** DeepSeek 官方音色；空 = 当前服务商不支持官方朗读（整块不显示）。 */
    officialVoices: List<SpeechVoice>,
    officialAvailable: Boolean,
    previewing: Boolean,
    error: String?,
    onSelect: (SpeechVoice?) -> Unit,
    onPreview: (SpeechVoice?) -> Unit,
) {
    SettingsSectionLabel(stringResource(R.string.tts_voice))

    SettingsCard {
        VoiceRow(
            label = stringResource(R.string.tts_voice_default),
            selected = selectedName.isBlank(),
            onClick = { onSelect(null) },
        )
        voices?.forEach { voice ->
            RowDivider()
            VoiceRow(
                label = voice.label,
                selected = voice.name == selectedName,
                onClick = { onSelect(voice) },
                onPreview = { onPreview(voice) },
            )
        }
    }

    /*
     * 官方音色单独一组、排在系统音色**后面**：系统音色是"一定可用"的兜底，
     * 官方的音质更好但依赖服务商与网络。不支持时不显示这一组 —— 连入口都没有，
     * 用户就不会点了才发现不行。
     */
    if (officialVoices.isNotEmpty()) {
        Spacer(Modifier.size(24.dp))
        SettingsSectionLabel(stringResource(R.string.tts_voice_official_section))
        SettingsCard {
            officialVoices.forEachIndexed { index, voice ->
                if (index > 0) RowDivider()
                VoiceRow(
                    label = voice.label,
                    selected = voice.name == selectedName,
                    onClick = { onSelect(voice) },
                    onPreview = { onPreview(voice) },
                )
            }
        }
    }

    val colors = LocalChatColors.current
    val hint = when {
        // 报错优先：引擎缺失 / 合成失败时，"正在试听"之类的说明就是误导
        error != null -> error
        voices == null -> stringResource(R.string.tts_voice_loading)
        voices.isEmpty() && officialVoices.isEmpty() -> stringResource(R.string.tts_voice_empty)
        previewing -> stringResource(R.string.tts_voice_preview_running)
        else -> stringResource(R.string.tts_voice_hint)
    }
    Text(
        text = hint,
        style = MaterialTheme.typography.bodySmall,
        color = colors.placeholder,
        modifier = Modifier.padding(start = 4.dp, top = 12.dp),
    )
    // 官方音色没出现时说明原因与条件，而不是让用户以为"这个 App 只有系统音色"
    if (!officialAvailable) {
        Text(
            text = stringResource(R.string.tts_voice_official_hint),
            style = MaterialTheme.typography.bodySmall,
            color = colors.placeholder,
            modifier = Modifier.padding(start = 4.dp, top = 8.dp),
        )
    }
}

/** 一行音色：名字 +（可选）试听 + 选中标记。 */
@Composable
private fun VoiceRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    onPreview: (() -> Unit)? = null,
) {
    val colors = LocalChatColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (onPreview != null) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onPreview),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = AppIcons.Play,
                    contentDescription = stringResource(R.string.tts_voice_preview),
                    tint = colors.placeholder,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        if (selected) {
            Icon(
                imageVector = AppIcons.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

// ══════════════════════════════════════════════════════════
//  二级：数据管理
// ══════════════════════════════════════════════════════════

@Composable
private fun DataPage(
    message: String?,
    /** 确定进度：null = 还没开始或已经结束；0..1 = 已解析的会话占比。 */
    progress: Float? = null,
    /** 无进度阶段（读文件、开包）—— 给循环进度条，别让界面看着像卡死。 */
    reading: Boolean = false,
    exportProgress: Float? = null,
    exportSaving: Boolean = false,
    onExport: () -> Unit,
    onExportArchive: () -> Unit,
    onExportReport: () -> Unit,
    onExportCrash: () -> Unit,
    onImport: () -> Unit,
    onDeleteAll: () -> Unit,
) {
    SettingsSectionLabel(stringResource(R.string.settings_group_backup))

    SettingsCard {
        SettingsActionRow(
            label = stringResource(R.string.settings_export_all),
            hint = stringResource(R.string.settings_export_all_summary),
            onClick = onExport,
            enabled = !reading && progress == null && exportProgress == null,
        )
        RowDivider()
        SettingsActionRow(
            label = stringResource(R.string.settings_export_archive),
            hint = stringResource(R.string.settings_export_archive_summary),
            onClick = onExportArchive,
            enabled = !reading && progress == null && exportProgress == null,
        )
        RowDivider()
        SettingsActionRow(
            label = stringResource(R.string.settings_import_all),
            hint = stringResource(R.string.settings_import_all_summary),
            onClick = onImport,
            // 导入期间不能重复点：同一份包并发解析会把内存和数据库都顶爆
            enabled = !reading && progress == null && exportProgress == null,
        )
    }

    /*
     * 进度条。大包（几百 MB）要走上分钟，没有进度的话界面上就是"点了没反应"——
     * 这正是先前那份 228MB 导出包给人的印象。
     *
     * 解析阶段按"已处理多少条会话"给确定进度；拷贝与收尾阶段只有 indeterminate。
     */
    if (exportProgress != null) {
        Spacer(Modifier.size(12.dp))
        Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), progress = { exportProgress })
            Spacer(Modifier.size(6.dp))
            Text(
                text = stringResource(
                    if (exportSaving) R.string.export_saving_progress else R.string.export_preparing_progress,
                    (exportProgress * 100).toInt(),
                ),
                style = MaterialTheme.typography.labelMedium,
                color = LocalChatColors.current.placeholder,
            )
        }
    }
    if (reading || progress != null) {
        Spacer(Modifier.size(12.dp))
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
            /*
             * 两个阶段用两种进度条：
             * - **读取中**（落盘、开 ZIP、读索引）：无从得知比例 → 循环进度条；
             * - **导入中**（逐条会话）：按已处理会话数给百分比。
             *
             * 先前读文件阶段也塞了个 0% 的确定进度条，几十秒不动 —— 看着就是"卡死"。
             */
            if (reading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.size(6.dp))
                Text(
                    text = stringResource(R.string.import_reading),
                    style = MaterialTheme.typography.labelMedium,
                    color = LocalChatColors.current.placeholder,
                )
            } else if (progress != null) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    progress = { progress },
                )
                Spacer(Modifier.size(6.dp))
                Text(
                    text = stringResource(R.string.import_progress, (progress * 100).toInt()),
                    style = MaterialTheme.typography.labelMedium,
                    color = LocalChatColors.current.placeholder,
                )
            }
        }
    }

    /*
     * 诊断：导入对账报告与崩溃诊断都是"出了问题才用"的东西，和日常的备份 / 导入
     * 不是同一类操作。混在备份卡片里，看起来会像"又一个常规导出"。
     */
    SettingsSectionLabel(stringResource(R.string.settings_group_diagnostics))

    SettingsCard {
        SettingsActionRow(
            label = stringResource(R.string.import_report_export),
            hint = stringResource(R.string.import_report_hint),
            onClick = onExportReport,
            enabled = !reading && progress == null && exportProgress == null,
        )
        RowDivider()
        SettingsActionRow(
            label = stringResource(R.string.crash_report_export),
            hint = stringResource(R.string.crash_report_hint),
            onClick = onExportCrash,
            enabled = !reading && progress == null && exportProgress == null,
        )
    }

    SettingsSectionLabel(stringResource(R.string.settings_group_reset))

    SettingsCard {
        SettingsActionRow(
            label = stringResource(R.string.settings_delete_all),
            hint = stringResource(R.string.settings_destructive_hint),
            destructive = true,
            onClick = onDeleteAll,
            enabled = !reading && progress == null && exportProgress == null,
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
    onProfiles: () -> Unit,
    profileError: String?,
    onTest: () -> Unit,
    onWebSearch: (Boolean) -> Unit,
) {
    val colors = LocalChatColors.current

    SettingsSectionLabel(stringResource(R.string.api_profiles_title))
    SettingsCard {
        SettingsNavRow(null, stringResource(R.string.api_profile_current),
            stored.apiProfiles.firstOrNull { it.id == stored.activeApiProfileId }?.name, onProfiles)
    }
    Text(stringResource(R.string.api_profiles_hint), style = MaterialTheme.typography.labelSmall,
        color = colors.placeholder, modifier = Modifier.padding(horizontal = 4.dp, vertical = 10.dp))
    profileError?.let { Text(it, color = colors.danger, style = MaterialTheme.typography.labelMedium) }

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
            // 自定义服务商初始没有模型，空串不该显示成一行空白
            value = stored.modelId.ifBlank { stringResource(R.string.api_model_unset) },
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

    /*
     * 通道与联网。
     *
     * 这一组是「扩展能力」的开关：协议通道决定走哪条端到端路径，
     * 智能搜索决定本轮要不要向服务端声明搜索工具，地区只进时间地点提醒。
     * 三者都不是必填项 —— 不碰它们，原来的行为一点不变。
     */
    SettingsSectionLabel(stringResource(R.string.settings_api_style))

    SettingsCard {
        SettingsNavRow(
            icon = null,
            label = stringResource(R.string.settings_api_style),
            value = stringResource(ApiStylePreference.fromId(stored.apiStyleId).labelRes),
            onClick = { onPick(SettingsDialog.ApiStyle) },
        )
        /*
         * 联网搜索只在扩展通道上有意义（`deepseek_web_search` 是那套协议里的工具），
         * OpenAI 兼容通道没有对应入口 —— 那就**不显示**这一行，而不是显示一个
         * 打开了也没用的开关。
         */
        if (stored.supportsWebSearch) {
            RowDivider(inset = 16.dp)
            SettingsSwitchRow(
                icon = null,
                label = stringResource(R.string.settings_web_search),
                checked = stored.webSearch,
                onCheckedChange = onWebSearch,
            )
        }
        RowDivider(inset = 16.dp)
        SettingsNavRow(
            icon = null,
            label = stringResource(R.string.settings_location),
            value = stored.locationRegion.ifBlank {
                stringResource(R.string.settings_location_hint_short)
            },
            onClick = { onPick(SettingsDialog.Location) },
        )
    }
}

@Composable
internal fun ApiProfilesPage(
    stored: StoredSettings, error: String?, onSelect: (ApiProfile) -> Unit,
    onNew: () -> Unit, onCopy: () -> Unit, onRename: (ApiProfile) -> Unit, onDelete: (ApiProfile) -> Unit,
) {
    val colors = LocalChatColors.current
    SettingsSectionLabel(stringResource(R.string.api_profiles_title))
    Text(stringResource(R.string.api_profiles_hint), style = MaterialTheme.typography.bodySmall,
        color = colors.placeholder, modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp))
    error?.let { Text(it, color = colors.danger) }
    SettingsCard {
        stored.apiProfiles.forEachIndexed { index, profile ->
            if (index > 0) RowDivider(inset = 16.dp)
            Row(Modifier.fillMaxWidth().padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).clickable { onSelect(profile) }.padding(16.dp)) {
                    Text(profile.name, style = MaterialTheme.typography.titleSmall,
                        color = if (profile.id == stored.activeApiProfileId) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                    val provider = ProviderKind.entries.firstOrNull { it.name == profile.providerId }
                    Text(listOfNotNull(provider?.displayName, profile.modelId.takeIf { it.isNotBlank() }).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall, color = colors.placeholder,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (profile.baseUrl.isNotBlank()) Text(profile.baseUrl,
                        style = MaterialTheme.typography.labelSmall, color = colors.placeholder,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(stringResource(if (profile.id == stored.activeApiProfileId) R.string.api_profile_selected
                        else if (profile.apiKeyEncrypted.isNotBlank()) R.string.api_profile_key_saved else R.string.api_key_unset),
                        style = MaterialTheme.typography.labelSmall, color = colors.placeholder)
                }
                IconCircleButton(AppIcons.Edit, stringResource(R.string.api_profile_rename), { onRename(profile) }, size = 36.dp, iconSize = 18.dp)
                if (stored.apiProfiles.size > 1) IconCircleButton(AppIcons.Trash,
                    stringResource(R.string.api_profile_delete), { onDelete(profile) }, size = 36.dp, iconSize = 18.dp)
            }
        }
    }
    Spacer(Modifier.size(16.dp))
    SettingsCard {
        SettingsActionRow(stringResource(R.string.api_profile_new), stringResource(R.string.api_profile_new_hint), onNew)
        RowDivider(inset = 16.dp)
        SettingsActionRow(stringResource(R.string.api_profile_copy), stringResource(R.string.api_profile_copy_hint), onCopy)
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
private fun AboutPage(onOpenLicenses: () -> Unit) {
    val colors = LocalChatColors.current
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var checkResult by remember { mutableStateOf<UpdateChecker.Result?>(null) }

    SettingsSectionLabel(stringResource(R.string.about_version))

    SettingsCard {
        InfoRow(label = stringResource(R.string.about_version), value = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        RowDivider(inset = 16.dp)
        InfoRow(label = stringResource(R.string.about_package), value = BuildConfig.APPLICATION_ID)
        RowDivider(inset = 16.dp)
        InfoRow(label = stringResource(R.string.license_type), value = APP_LICENSE)
        RowDivider(inset = 16.dp)
        InfoRow(label = stringResource(R.string.license_copyright), value = APP_COPYRIGHT)
    }

    /*
     * 「检查更新」单独一张卡：上面几行是只读信息，这一行是**可点**的 ——
     * 混排会出现"有的能点有的不能点"。
     */
    SettingsSectionLabel(stringResource(R.string.about_update))

    SettingsCard {
        SettingsActionRow(
            label = stringResource(R.string.about_check_update),
            hint = stringResource(R.string.about_current_version, BuildConfig.VERSION_NAME),
            onClick = {
                if (checking) return@SettingsActionRow
                checking = true
                checkResult = null
                scope.launch {
                    checkResult = UpdateChecker.check(BuildConfig.VERSION_NAME)
                    checking = false
                }
            },
            enabled = !checking,
        )
    }

    /*
     * 项目地址单独一组，不与上面的版本信息混排：
     * 这一行是**可点**的（点开跳到仓库），而上面几行是只读信息 ——
     * 混在一张卡里会出现「有的能点有的不能点」。
     */
    SettingsSectionLabel(stringResource(R.string.about_project))

    SettingsCard {
        LinkRow(
            label = stringResource(R.string.about_project_url),
            value = ProjectUrl,
            url = ProjectUrl,
        )
    }

    SettingsSectionLabel(stringResource(R.string.about_licenses))

    /*
     * 只放一行入口，不把十几个组件铺在「关于」页里。
     *
     * 合规要看的是**每个组件的五项信息**（名称与版本 / 许可证 / 版权 / 官方链接 /
     * 许可证全文），那必须是一页能逐条读的清单，而不是几张 `名字 (MIT)` 的便签。
     */
    SettingsCard {
        SettingsNavRow(
            icon = AppIcons.FileText,
            label = stringResource(R.string.about_licenses),
            value = stringResource(R.string.licenses_count, thirdPartyLicenses.size),
            onClick = onOpenLicenses,
        )
    }

    Spacer(Modifier.size(16.dp))

    Text(
        text = stringResource(R.string.about_ai_disclaimer),
        style = MaterialTheme.typography.labelSmall,
        color = LocalChatColors.current.placeholder,
        modifier = Modifier.padding(horizontal = 4.dp),
    )

    checkResult?.let { result ->
        val context = LocalContext.current
        UpdateResultDialog(
            result = result,
            onDismiss = { checkResult = null },
            onOpen = { url ->
                checkResult = null
                openInBrowser(context, url)
            },
        )
    }
}

/**
 * 检查更新的结果。
 *
 * 三种结果一句话说清：有新版本 → 给一个「前往下载」；没有 → 「已是最新版」；
 * 查不出来 → 「检查失败」（不猜原因，也不催用户重试 —— 网络的事他比我们清楚）。
 */
@Composable
private fun UpdateResultDialog(
    result: UpdateChecker.Result,
    onDismiss: () -> Unit,
    onOpen: (String) -> Unit,
) {
    val colors = LocalChatColors.current
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(ContainerShape)
                .background(colors.card)
                .padding(20.dp),
        ) {
            Text(
                text = when (result) {
                    is UpdateChecker.Result.Newer -> stringResource(R.string.update_new_title, result.release.tag)
                    UpdateChecker.Result.UpToDate -> stringResource(R.string.update_up_to_date)
                    is UpdateChecker.Result.Failed -> stringResource(R.string.update_failed)
                },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (result is UpdateChecker.Result.Newer) {
                Spacer(Modifier.size(8.dp))
                Text(
                    text = stringResource(R.string.about_current_version, BuildConfig.VERSION_NAME),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.placeholder,
                )
            }
            Spacer(Modifier.size(18.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextAction(stringResource(R.string.action_close), onClick = onDismiss)
                if (result is UpdateChecker.Result.Newer) {
                    Spacer(Modifier.size(8.dp))
                    TextAction(
                        stringResource(R.string.update_open),
                        primary = true,
                        onClick = { onOpen(result.release.url) },
                    )
                }
            }
        }
    }
}

/** 用浏览器打开链接；没有浏览器时静默失败（不值得为一步跳转崩掉整个设置页）。 */
private fun openInBrowser(context: android.content.Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}

// ══════════════════════════════════════════════════════════
//  三级：开源许可与版权
// ══════════════════════════════════════════════════════════

/**
 * 第三方组件清单。
 *
 * 合规要的是**每个组件的五项**：名称与版本、许可证、版权所有、官方链接、许可证全文。
 * 这必须是一页能逐条读的清单 —— 挤在「关于」页里的 `名字 (MIT)` 便签既查不到版本，
 * 也拿不到版权方。
 */
@Composable
private fun LicensesPage() {
    val colors = LocalChatColors.current
    var selected by remember { mutableStateOf<LicenseEntry?>(null) }

    SettingsSectionLabel(stringResource(R.string.licenses_third_party))

    SettingsCard {
        thirdPartyLicenses.forEachIndexed { index, entry ->
            if (index > 0) RowDivider(inset = 16.dp)
            LicenseRow(entry = entry, onClick = { selected = entry })
        }
    }

    SettingsSectionLabel(stringResource(R.string.licenses_reference_title))

    SettingsCard {
        referenceCredits.forEachIndexed { index, textRes ->
            if (index > 0) RowDivider(inset = 16.dp)
            Text(
                text = stringResource(textRes),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.processText,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            )
        }
    }

    selected?.let { entry ->
        LicenseDetailDialog(entry = entry, onDismiss = { selected = null })
    }
}

/** 清单里的一行：名称 + 版本 · 许可证 + 版权方。 */
@Composable
private fun LicenseRow(entry: LicenseEntry, onClick: () -> Unit) {
    val colors = LocalChatColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.name,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.size(3.dp))
            Text(
                text = "${entry.version} · ${entry.license}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.size(2.dp))
            Text(
                text = entry.copyright,
                style = MaterialTheme.typography.labelSmall,
                color = colors.placeholder,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.size(8.dp))
        Icon(
            imageVector = AppIcons.ChevronRight,
            contentDescription = null,
            tint = colors.placeholder,
            modifier = Modifier.size(16.dp),
        )
    }
}

/** 单个组件的详情：五项信息齐全，链接可直接打开。 */
@Composable
private fun LicenseDetailDialog(entry: LicenseEntry, onDismiss: () -> Unit) {
    val colors = LocalChatColors.current
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(ContainerShape)
                .background(colors.card)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 16.dp),
        ) {
            Text(
                text = entry.name,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Spacer(Modifier.size(12.dp))
            InfoRow(label = stringResource(R.string.license_version), value = entry.version)
            RowDivider(inset = 16.dp)
            InfoRow(label = stringResource(R.string.license_type), value = entry.license)
            RowDivider(inset = 16.dp)
            InfoRow(label = stringResource(R.string.license_copyright), value = entry.copyright)
            RowDivider(inset = 16.dp)
            LinkRow(
                label = stringResource(R.string.license_project),
                value = entry.projectUrl,
                url = entry.projectUrl,
            )
            RowDivider(inset = 16.dp)
            LinkRow(
                label = stringResource(R.string.license_full_text),
                value = entry.licenseUrl,
                url = entry.licenseUrl,
            )
            Spacer(Modifier.size(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextAction(stringResource(R.string.action_close), onClick = onDismiss)
            }
        }
    }
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
 * 读系统音色列表的超时。
 *
 * `TextToSpeech` 的初始化回调在个别设备上可能永远不来（引擎被禁用 / 服务被冻结）。
 * 不设上限的话，音色页会永远停在「正在读取…」—— 用户既看不到音色、也不知道该怎么办。
 */
private const val TtsVoiceLoadTimeoutMs = 4_000L

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
    /** 导入进行中之类的"暂时不能点"。 */
    enabled: Boolean = true,
) {
    val colors = LocalChatColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
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
        AppSwitch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * 仓库地址。写成常量而不是资源：它是**代码里的一个事实**，不随界面语言变，
 * 也不该被翻译（翻成别的语言反而点不开）。改仓库时只改这一处。
 */
private const val ProjectUrl = "https://github.com/Pokanshuu/Simple-Chat"

/**
 * 可点击的信息行 —— 样式与 [InfoRow] 一致，只是值用主色、整行可点。
 *
 * 用系统的 `ACTION_VIEW` 交给浏览器：应用内 WebView 打开 GitHub 只会带来
 * 一个返回键更别扭的界面，而"在浏览器里打开"也符合用户对这个动作的预期。
 */
@Composable
private fun LinkRow(label: String, value: String, url: String) {
    val colors = LocalChatColors.current
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                // 没有浏览器时不要崩：提示一句就够了
                runCatching { context.startActivity(intent) }
            }
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.End,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
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
    /** 一个选项都没有时显示的话。给 null 就是只留标题（其余调用方没有空列表）。 */
    emptyHint: String? = null,
    /** 列表下面多出来的那一条动作（例如「手动填写模型」），与选中状态无关。 */
    extraAction: Pair<String, () -> Unit>? = null,
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

            if (options.isEmpty()) {
                emptyHint?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.placeholder,
                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp),
                    )
                }
            } else {
                /*
                 * 用 LazyColumn 而不是 Column：自定义端点的 `/models` 动辄四五十个
                 * （用户那台中转站是 45 个），一次性铺开会把弹窗顶穿屏幕、底部
                 * 超出窗口的部分**点不到也看不见** —— 看起来又像"列表没显示"。
                 */
                LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                    items(options.size) { index ->
                        val (item, label) = options[index]
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

            extraAction?.let { (label, onClick) ->
                if (options.isNotEmpty()) RowDivider(inset = 16.dp)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onClick)
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
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
    /** 返回非空字符串 = 校验失败的原因；此时不关窗、不提交。 */
    validate: ((String) -> String?)? = null,
) {
    val colors = LocalChatColors.current
    var value by remember { mutableStateOf(initial) }
    var error by remember { mutableStateOf<String?>(null) }

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
                    onValueChange = {
                        value = it
                        // 用户开始改了就把旧的红字收掉，别让它跟着新内容一起错着
                        error = null
                    },
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

            /*
             * 校验失败时**不关窗**：把原因就地写在按钮上方，用户改一个字就能再点。
             * 关掉窗再弹一句提示，等于让他重新打开一遍再粘一次。
             */
            error?.let { text ->
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelMedium,
                    color = LocalChatColors.current.danger,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextAction(stringResource(R.string.action_cancel), onClick = onDismiss)
                Spacer(Modifier.size(8.dp))
                TextAction(
                    stringResource(R.string.action_save),
                    primary = true,
                    onClick = {
                        val problem = validate?.invoke(value)
                        if (problem != null) error = problem else onConfirm(value)
                    },
                )
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



