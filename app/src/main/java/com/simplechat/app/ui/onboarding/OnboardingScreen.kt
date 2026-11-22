package com.simplechat.app.ui.onboarding

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.simplechat.app.R
import com.simplechat.app.data.ChatRepository
import com.simplechat.app.data.ImportMode
import com.simplechat.app.data.SettingsStore
import com.simplechat.app.data.userMessage
import com.simplechat.app.net.ChatApi
import com.simplechat.app.net.ProviderKind
import com.simplechat.app.ui.common.AppIcons
import com.simplechat.app.ui.common.ContainerShape
import com.simplechat.app.ui.common.FieldThickness
import com.simplechat.app.ui.common.IconCircleButton
import com.simplechat.app.ui.common.LoadingIndicator
import com.simplechat.app.ui.common.MenuShape
import com.simplechat.app.ui.common.PillShape
import com.simplechat.app.ui.common.SoftShape
import com.simplechat.app.ui.common.Space
import com.simplechat.app.ui.common.dismissKeyboardOnTouch
import com.simplechat.app.ui.theme.AccentColor
import com.simplechat.app.ui.theme.LocalChatColors
import com.simplechat.app.ui.theme.LocalIsDarkTheme
import com.simplechat.app.ui.theme.primary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private enum class OnboardingStep { WELCOME, KEY, MODEL, BACKUP, THEME }

/** 指示器里的步数（欢迎页不计）。 */
private const val StepCount = 4

/** 底部主按钮与次级操作的最小触控高度。两者都预留，主按钮在每一页的位置都不变。 */
private val ActionHeight = 48.dp

/** 状态行（错误 / 验证中 / 导入进度）预留高度，出现或消失时上方控件不跳动。 */
private val StatusMinHeight = 48.dp

@OptIn(ExperimentalAnimationApi::class)
@Composable
fun OnboardingScreen(settingsStore: SettingsStore, chatApi: ChatApi, repository: ChatRepository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf(OnboardingStep.WELCOME) }
    var apiKey by remember { mutableStateOf("") }
    var selectedModel by remember { mutableStateOf(ProviderKind.DEEPSEEK.defaultModel) }
    var selectedTheme by remember { mutableStateOf(AccentColor.BLUE) }
    var busy by remember { mutableStateOf(false) }
    val progressFlow = remember { MutableStateFlow<Float?>(null) }
    val progress by progressFlow.collectAsState()
    var error by remember { mutableStateOf<String?>(null) }
    var imported by remember { mutableStateOf(false) }
    var pendingFile by remember { mutableStateOf<File?>(null) }
    var showImportModes by remember { mutableStateOf(false) }
    var selectedImportMode by remember { mutableStateOf(ImportMode.MERGE) }
    val dark = LocalIsDarkTheme.current

    suspend fun finish() {
        if (apiKey.isNotBlank()) {
            settingsStore.setProvider(ProviderKind.DEEPSEEK.name, ProviderKind.DEEPSEEK.defaultBaseUrl, selectedModel)
            settingsStore.setApiKey(apiKey)
        }
        settingsStore.setAccentColor(selectedTheme.name)
        settingsStore.setOnboardingComplete()
    }

    fun next() {
        step = when (step) {
            OnboardingStep.WELCOME -> OnboardingStep.KEY
            OnboardingStep.KEY -> if (apiKey.isNotBlank()) OnboardingStep.MODEL else OnboardingStep.BACKUP
            OnboardingStep.MODEL -> OnboardingStep.BACKUP
            OnboardingStep.BACKUP -> OnboardingStep.THEME
            OnboardingStep.THEME -> OnboardingStep.THEME
        }
    }

    /** next() 的逆过程：跳过 Key 时没有经过模型页，返回也不经过。 */
    fun previous() {
        error = null
        step = when (step) {
            OnboardingStep.WELCOME, OnboardingStep.KEY -> OnboardingStep.WELCOME
            OnboardingStep.MODEL -> OnboardingStep.KEY
            OnboardingStep.BACKUP -> if (apiKey.isNotBlank()) OnboardingStep.MODEL else OnboardingStep.KEY
            OnboardingStep.THEME -> OnboardingStep.BACKUP
        }
    }

    fun verifyKey() {
        if (busy) return
        if (!apiKey.startsWith("sk-") || apiKey.length < 12 || apiKey.any { it.code !in 0x21..0x7e }) {
            error = context.getString(R.string.onboarding_key_invalid)
        } else scope.launch {
            busy = true
            error = null
            val result = chatApi.fetchModels(ProviderKind.DEEPSEEK.defaultBaseUrl, apiKey.trim())
            busy = false
            if (result.isSuccess) next() else error = context.getString(R.string.onboarding_key_rejected)
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            error = null
            var staged: File? = null
            try {
                val file = withContext(Dispatchers.IO) {
                    File.createTempFile("onboarding-import-", ".zip", context.cacheDir).also { target ->
                        staged = target
                        context.contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use { output -> input.copyTo(output) } }
                            ?: error(context.getString(R.string.import_read_failed))
                    }
                }
                pendingFile = file
                showImportModes = true
            } catch (e: Exception) {
                error = context.getString(R.string.error_import_failed, e.userMessage())
                staged?.delete()
                busy = false
            }
        }
    }

    fun startImport(mode: ImportMode) {
        showImportModes = false
        scope.launch {
            val file = pendingFile
            pendingFile = null
            if (file == null) { busy = false; return@launch }
            progressFlow.value = 0f
            try {
                withContext(Dispatchers.IO) {
                    repository.importExternal(file, context.cacheDir, mode) { progressFlow.value = it }
                }
                imported = true
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = context.getString(R.string.error_import_failed, e.userMessage()) }
            finally {
                file.delete()
                busy = false
                // 仅界面状态：清掉进度，下一次选文件时先显示「读取中」而不是上次的百分比
                progressFlow.value = null
            }
        }
    }

    fun cancelImportMode() {
        showImportModes = false
        pendingFile?.delete()
        pendingFile = null
        busy = false
    }

    // 返回键必须有人接（§15.7），否则直接落到 Activity.finish()
    BackHandler(enabled = step != OnboardingStep.WELCOME && !busy) { previous() }

    if (showImportModes) {
        ImportModeDialog(
            selected = selectedImportMode,
            onSelect = { selectedImportMode = it },
            onConfirm = { startImport(selectedImportMode) },
            onDismiss = { cancelImportMode() },
        )
    }

    // Surface 提供 contentColor：没有它时 Text 默认是黑字，深色模式下看不见
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .imePadding()
                .dismissKeyboardOnTouch(),
        ) {
            OnboardingTopBar(
                step = step,
                backEnabled = !busy,
                onBack = { previous() },
            )

            AnimatedContent(
                targetState = step,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.TopStart,
                transitionSpec = {
                    val direction = if (targetState.ordinal >= initialState.ordinal) 1 else -1
                    slideInHorizontally(tween(260)) { it * direction } togetherWith
                        slideOutHorizontally(tween(260)) { -it * direction }
                },
                label = "onboarding-slide",
            ) { page ->
                when (page) {
                    OnboardingStep.WELCOME -> WelcomePage()
                    OnboardingStep.KEY -> KeyPage(
                        apiKey = apiKey,
                        onKeyChange = { apiKey = it; error = null },
                        errorText = error,
                        verifying = busy,
                        onDone = { verifyKey() },
                        onGetKey = {
                            runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse("https://platform.deepseek.com"))) }
                        },
                    )
                    OnboardingStep.MODEL -> ModelPage(selected = selectedModel, onSelect = { selectedModel = it })
                    OnboardingStep.BACKUP -> BackupPage(
                        busy = busy,
                        reading = busy && progress == null && pendingFile == null,
                        progress = if (busy) progress else null,
                        imported = imported,
                        error = error,
                        onPick = { importLauncher.launch(arrayOf("*/*")) },
                    )
                    OnboardingStep.THEME -> ThemePage(selected = selectedTheme, onSelect = { selectedTheme = it })
                }
            }

            // ── 底部操作区：每一页同一个位置、同一套尺寸 ──
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(start = Space.Screen, end = Space.Screen, top = Space.Gap, bottom = Space.Gap),
            ) {
                when (step) {
                    OnboardingStep.WELCOME -> PrimaryAction(
                        text = stringResource(R.string.onboarding_begin),
                        trailingIcon = AppIcons.ChevronRight,
                        onClick = { next() },
                    )
                    OnboardingStep.KEY -> PrimaryAction(
                        text = stringResource(if (busy) R.string.onboarding_verifying else R.string.onboarding_verify),
                        loading = busy,
                        enabled = !busy,
                        onClick = { verifyKey() },
                    )
                    OnboardingStep.MODEL -> PrimaryAction(
                        text = stringResource(R.string.onboarding_next),
                        onClick = { next() },
                    )
                    OnboardingStep.BACKUP -> PrimaryAction(
                        text = stringResource(if (imported) R.string.onboarding_next else R.string.onboarding_skip),
                        tonal = !imported,
                        enabled = !busy,
                        onClick = { error = null; next() },
                    )
                    OnboardingStep.THEME -> PrimaryAction(
                        text = stringResource(R.string.onboarding_start),
                        container = selectedTheme.primary(dark),
                        onClick = { scope.launch { finish() } },
                    )
                }
                Box(Modifier.fillMaxWidth().heightIn(min = ActionHeight), contentAlignment = Alignment.Center) {
                    if (step == OnboardingStep.KEY) {
                        SecondaryAction(
                            text = stringResource(R.string.onboarding_later),
                            enabled = !busy,
                            onClick = { apiKey = ""; error = null; step = OnboardingStep.BACKUP },
                        )
                    }
                }
            }
        }
    }
}

// ══════════════════════════════════════════════════════════
//  框架：顶栏、页头、底部按钮
// ══════════════════════════════════════════════════════════

@Composable
private fun OnboardingTopBar(step: OnboardingStep, backEnabled: Boolean, onBack: () -> Unit) {
    val colors = LocalChatColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = Space.Gap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            if (step != OnboardingStep.WELCOME) {
                IconCircleButton(
                    icon = AppIcons.ChevronLeft,
                    contentDescription = stringResource(R.string.action_back),
                    onClick = onBack,
                    enabled = backEnabled,
                    size = 48.dp,
                    iconSize = 22.dp,
                )
            }
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            if (step != OnboardingStep.WELCOME) {
                val current = step.ordinal // KEY = 1 … THEME = 4
                val description = stringResource(R.string.onboarding_step, current, StepCount)
                Row(
                    Modifier.semantics(mergeDescendants = true) { contentDescription = description },
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    for (i in 1..StepCount) {
                        val active = i == current
                        val width by animateDpAsState(if (active) 24.dp else 8.dp, tween(260), label = "step-width")
                        val color by animateColorAsState(
                            if (i <= current) MaterialTheme.colorScheme.primary else colors.outline,
                            tween(260),
                            label = "step-color",
                        )
                        Box(Modifier.width(width).height(6.dp).clip(PillShape).background(color))
                    }
                }
            }
        }
        Spacer(Modifier.size(48.dp))
    }
}

/** 页面主体：可滚动，横向页边距与标题区统一。 */
@Composable
private fun PageColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Space.Screen)
            .padding(top = Space.Gap, bottom = Space.Section),
        content = content,
    )
}

@Composable
private fun PageHeader(title: String, description: String? = null) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.semantics { heading() },
    )
    if (description != null) {
        Spacer(Modifier.height(Space.Gap))
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Spacer(Modifier.height(Space.Section))
}

@Composable
private fun PrimaryAction(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    loading: Boolean = false,
    /** 「跳过」这类非推荐操作：同位置同尺寸，只换成浅色底。 */
    tonal: Boolean = false,
    container: Color = MaterialTheme.colorScheme.primary,
    trailingIcon: ImageVector? = null,
) {
    val colors = LocalChatColors.current
    val background = when {
        tonal -> colors.capsuleSelected
        !enabled && !loading -> colors.fieldBackground
        else -> container
    }
    val content = when {
        tonal -> if (enabled) MaterialTheme.colorScheme.primary else colors.placeholder
        !enabled && !loading -> colors.placeholder
        else -> MaterialTheme.colorScheme.onPrimary
    }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = ActionHeight)
            .clip(PillShape)
            .background(background)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = Space.Section, vertical = Space.Inline),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (loading) {
            LoadingIndicator(Modifier.size(18.dp), color = content, strokeWidth = 2.dp)
            Spacer(Modifier.width(Space.Gap))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            color = content,
            textAlign = TextAlign.Center,
        )
        if (trailingIcon != null) {
            Spacer(Modifier.width(Space.Gap))
            Icon(trailingIcon, null, Modifier.size(18.dp), tint = content)
        }
    }
}

@Composable
private fun SecondaryAction(text: String, enabled: Boolean, onClick: () -> Unit) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (enabled) MaterialTheme.colorScheme.primary else LocalChatColors.current.placeholder,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .clip(PillShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .heightIn(min = ActionHeight)
            .padding(horizontal = Space.Card, vertical = Space.Inline),
    )
}

/** 状态行：预留高度，内容变化时朗读。 */
@Composable
private fun StatusArea(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = StatusMinHeight)
            .padding(top = Space.Gap)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(Space.Gap),
        content = content,
    )
}

// ══════════════════════════════════════════════════════════
//  各页
// ══════════════════════════════════════════════════════════

@Composable
private fun WelcomePage() {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Space.Screen, vertical = Space.Section),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(AppIcons.MessageSquare, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(Space.Section))
        Text(
            text = stringResource(R.string.onboarding_welcome),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
    }
}

@Composable
private fun KeyPage(
    apiKey: String,
    onKeyChange: (String) -> Unit,
    errorText: String?,
    verifying: Boolean,
    onDone: () -> Unit,
    onGetKey: () -> Unit,
) {
    val colors = LocalChatColors.current
    val label = stringResource(R.string.onboarding_key_label)
    PageColumn {
        PageHeader(
            title = stringResource(R.string.onboarding_key_title),
            description = stringResource(R.string.onboarding_key_privacy),
        )
        val borderColor = if (errorText != null) MaterialTheme.colorScheme.error else colors.outline
        BasicTextField(
            value = apiKey,
            onValueChange = onKeyChange,
            enabled = !verifying,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onDone() }),
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = ActionHeight)
                .clip(PillShape)
                .background(colors.fieldBackground)
                .border(1.dp, borderColor, PillShape)
                .semantics {
                    contentDescription = label
                    if (errorText != null) error(errorText)
                },
            decorationBox = { inner ->
                Box(
                    Modifier.padding(horizontal = Space.Card, vertical = FieldThickness),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (apiKey.isEmpty()) {
                        Text("sk-…", style = MaterialTheme.typography.bodyMedium, color = colors.placeholder)
                    }
                    inner()
                }
            },
        )
        StatusArea {
            if (errorText != null) {
                Text(
                    text = errorText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = Space.Card),
                )
            }
        }
        Row(
            Modifier
                .clip(PillShape)
                .clickable(role = Role.Button, onClick = onGetKey)
                .heightIn(min = ActionHeight)
                .padding(horizontal = Space.Card),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.onboarding_get_key),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(4.dp))
            Icon(AppIcons.ChevronRight, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun ModelPage(selected: String, onSelect: (String) -> Unit) {
    PageColumn {
        PageHeader(title = stringResource(R.string.onboarding_model_title))
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(Space.Inline)) {
            listOf(
                Triple("deepseek-v4-pro", R.string.onboarding_model_pro_name, R.string.onboarding_model_pro),
                Triple(ProviderKind.DEEPSEEK.defaultModel, R.string.onboarding_model_flash_name, R.string.onboarding_model_flash),
            ).forEach { (id, name, description) ->
                ModelOption(
                    id = id,
                    name = stringResource(name),
                    description = stringResource(description),
                    selected = selected == id,
                    onClick = { onSelect(id) },
                )
            }
        }
        Spacer(Modifier.height(Space.Section))
        Text(
            text = stringResource(R.string.onboarding_model_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ModelOption(id: String, name: String, description: String, selected: Boolean, onClick: () -> Unit) {
    val colors = LocalChatColors.current
    val border by animateColorAsState(if (selected) MaterialTheme.colorScheme.primary else colors.outline, label = "model-border")
    val fill by animateColorAsState(if (selected) colors.capsuleSelected else Color.Transparent, label = "model-fill")
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MenuShape)
            .background(fill)
            .border(1.dp, border, MenuShape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(Space.Card),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = if (selected) AppIcons.CircleCheck else AppIcons.Circle,
            contentDescription = null,
            tint = if (selected) MaterialTheme.colorScheme.primary else colors.placeholder,
            modifier = Modifier.padding(top = 2.dp).size(20.dp),
        )
        Spacer(Modifier.width(Space.Inline))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(id, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(Space.Gap))
            Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun BackupPage(
    busy: Boolean,
    reading: Boolean,
    progress: Float?,
    imported: Boolean,
    error: String?,
    onPick: () -> Unit,
) {
    val colors = LocalChatColors.current
    PageColumn {
        PageHeader(
            title = stringResource(R.string.onboarding_backup_title),
            description = stringResource(R.string.onboarding_backup_hint),
        )
        val tint = if (busy) colors.placeholder else MaterialTheme.colorScheme.primary
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .clip(MenuShape)
                .border(1.dp, colors.outline, MenuShape)
                .clickable(enabled = !busy, role = Role.Button, onClick = onPick)
                .padding(horizontal = Space.Card, vertical = Space.Inline),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(AppIcons.Upload, null, Modifier.size(20.dp), tint = tint)
            Spacer(Modifier.width(Space.Inline))
            Text(
                text = stringResource(if (imported) R.string.onboarding_import_again else R.string.onboarding_import),
                style = MaterialTheme.typography.titleSmall,
                color = if (busy) colors.placeholder else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Icon(AppIcons.ChevronRight, null, Modifier.size(18.dp), tint = colors.placeholder)
        }
        StatusArea {
            when {
                // 0 先不给百分比：停在 0% 看着像卡死（与设置页同一处理），等真有进展再显示
                progress != null && progress > 0f -> {
                    val value = progress.coerceIn(0f, 1f)
                    LinearProgressIndicator(
                        progress = { value },
                        modifier = Modifier.fillMaxWidth().clip(PillShape),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = colors.outline,
                        drawStopIndicator = {},
                    )
                    Text(
                        text = stringResource(R.string.onboarding_import_progress, (value * 100).toInt()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                reading || progress != null -> {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().clip(PillShape),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = colors.outline,
                    )
                    Text(
                        text = stringResource(if (reading) R.string.onboarding_import_reading else R.string.import_working),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                error != null -> Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                imported -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(AppIcons.CircleCheck, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(Space.Gap))
                    Text(
                        text = stringResource(R.string.onboarding_imported),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ThemePage(selected: AccentColor, onSelect: (AccentColor) -> Unit) {
    val dark = LocalIsDarkTheme.current
    PageColumn {
        PageHeader(title = stringResource(R.string.onboarding_theme_title))
        FlowRow(
            Modifier.fillMaxWidth().selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(Space.Gap),
            verticalArrangement = Arrangement.spacedBy(Space.Gap),
        ) {
            AccentColor.entries.forEach { accent ->
                val isSelected = accent == selected
                val description = stringResource(R.string.a11y_accent_color, accent.label)
                Column(
                    Modifier
                        .width(64.dp)
                        .clip(SoftShape)
                        .selectable(selected = isSelected, role = Role.RadioButton, onClick = { onSelect(accent) })
                        .semantics(mergeDescendants = true) { contentDescription = description }
                        .padding(vertical = Space.Gap),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                        if (isSelected) {
                            Box(Modifier.size(48.dp).border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape))
                        }
                        Box(
                            Modifier.size(38.dp).clip(CircleShape).background(accent.primary(dark)),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (isSelected) Icon(AppIcons.Check, null, Modifier.size(18.dp), tint = Color.White)
                        }
                    }
                    Spacer(Modifier.height(Space.Gap))
                    Text(
                        text = accent.label,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                    )
                }
            }
        }
    }
}

// ══════════════════════════════════════════════════════════
//  导入方式（沿用设置页 ChoiceDialog 的外观）
// ══════════════════════════════════════════════════════════

@Composable
private fun ImportModeDialog(
    selected: ImportMode,
    onSelect: (ImportMode) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalChatColors.current
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(ContainerShape)
                .background(colors.card)
                .verticalScroll(rememberScrollState())
                .padding(vertical = Space.Gap),
        ) {
            Text(
                text = stringResource(R.string.import_mode_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .padding(horizontal = 20.dp, vertical = Space.RowPadY)
                    .semantics { heading() },
            )
            Column(Modifier.selectableGroup()) {
                listOf(
                    ImportMode.MERGE to R.string.import_mode_merge,
                    ImportMode.REPLACE_SAME to R.string.import_mode_replace_same,
                    ImportMode.RENAME to R.string.import_mode_rename,
                ).forEach { (mode, label) ->
                    val isSelected = selected == mode
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(selected = isSelected, role = Role.RadioButton, onClick = { onSelect(mode) })
                            .heightIn(min = ActionHeight)
                            .padding(horizontal = 20.dp, vertical = Space.RowPadY),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = if (isSelected) AppIcons.CircleCheck else AppIcons.Circle,
                            contentDescription = null,
                            tint = if (isSelected) MaterialTheme.colorScheme.primary else colors.placeholder,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(Space.Inline))
                        Text(
                            text = stringResource(label),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Space.Inline, vertical = Space.Gap),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DialogAction(stringResource(R.string.onboarding_cancel), MaterialTheme.colorScheme.onSurfaceVariant, onDismiss)
                Spacer(Modifier.width(Space.Gap))
                DialogAction(stringResource(R.string.onboarding_import_confirm), MaterialTheme.colorScheme.primary, onConfirm)
            }
        }
    }
}

@Composable
private fun DialogAction(text: String, color: Color, onClick: () -> Unit) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = color,
        modifier = Modifier
            .clip(PillShape)
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = ActionHeight)
            .padding(horizontal = Space.Card, vertical = Space.Inline),
    )
}
