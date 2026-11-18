package com.simplechat.app.ui.onboarding

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.simplechat.app.R
import com.simplechat.app.data.ImportMode
import com.simplechat.app.data.SettingsStore
import com.simplechat.app.data.ChatRepository
import com.simplechat.app.net.ChatApi
import com.simplechat.app.net.ProviderKind
import com.simplechat.app.ui.common.AppIcons
import com.simplechat.app.ui.common.CapsuleButton
import com.simplechat.app.ui.common.Space
import com.simplechat.app.ui.theme.AccentColor
import com.simplechat.app.ui.theme.LocalChatColors
import com.simplechat.app.ui.theme.primary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private enum class OnboardingStep { WELCOME, KEY, MODEL, BACKUP, THEME }

@OptIn(ExperimentalAnimationApi::class)
@Composable
fun OnboardingScreen(settingsStore: SettingsStore, chatApi: ChatApi, repository: ChatRepository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf(OnboardingStep.WELCOME) }
    var apiKey by remember { mutableStateOf("") }
    var selectedModel by remember { mutableStateOf("deepseek-v4-flash") }
    var selectedTheme by remember { mutableStateOf(AccentColor.BLUE) }
    var busy by remember { mutableStateOf(false) }
    val progressFlow = remember { MutableStateFlow<Float?>(null) }
    val progress by progressFlow.collectAsState()
    var error by remember { mutableStateOf<String?>(null) }
    var imported by remember { mutableStateOf(false) }
    var pendingFile by remember { mutableStateOf<File?>(null) }
    var showImportModes by remember { mutableStateOf(false) }
    var selectedImportMode by remember { mutableStateOf(ImportMode.MERGE) }
    val colors = LocalChatColors.current

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
                error = e.message ?: context.getString(R.string.error_import_failed, "")
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
            catch (e: Exception) { error = e.message ?: context.getString(R.string.error_import_failed, "") }
            finally { file.delete(); busy = false }
        }
    }

    if (showImportModes) {
        AlertDialog(
            onDismissRequest = { showImportModes = false; pendingFile?.delete(); pendingFile = null; busy = false },
            title = { Text(context.getString(R.string.import_mode_title)) },
            text = {
                Column {
                    listOf(
                        ImportMode.MERGE to R.string.import_mode_merge,
                        ImportMode.REPLACE_SAME to R.string.import_mode_replace_same,
                        ImportMode.RENAME to R.string.import_mode_rename,
                    ).forEach { (mode, label) ->
                        Row(
                            Modifier.fillMaxWidth().clickable { selectedImportMode = mode },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = selectedImportMode == mode, onClick = { selectedImportMode = mode })
                            Text(context.getString(label))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { startImport(selectedImportMode) }) { Text(context.getString(R.string.onboarding_import_confirm)) } },
            dismissButton = {
                TextButton(onClick = { showImportModes = false; pendingFile?.delete(); pendingFile = null; busy = false }) { Text(context.getString(R.string.onboarding_cancel)) }
            },
        )
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(Space.Screen)) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            AnimatedContent(
                targetState = step,
                transitionSpec = {
                    val direction = if (targetState.ordinal >= initialState.ordinal) 1 else -1
                    slideInHorizontally(tween(260)) { it * direction } togetherWith
                        slideOutHorizontally(tween(260)) { -it * direction }
                },
                label = "onboarding-slide",
            ) { page ->
                when (page) {
                    OnboardingStep.WELCOME -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        Icon(AppIcons.Bubble, null, Modifier.size(76.dp), tint = MaterialTheme.colorScheme.primary)
                        Text(context.getString(R.string.onboarding_welcome), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                        Box(Modifier.size(56.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary).clickable { next() }, Alignment.Center) {
                            Icon(AppIcons.ChevronRight, null, tint = MaterialTheme.colorScheme.onPrimary)
                        }
                    }
                    OnboardingStep.KEY -> Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(context.getString(R.string.onboarding_key_title), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                        Text(context.getString(R.string.onboarding_key_privacy), style = MaterialTheme.typography.bodySmall, color = colors.placeholder, textAlign = TextAlign.Center)
                        BasicTextField(
                            value = apiKey,
                            onValueChange = { apiKey = it; error = null },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(colors.card).border(1.dp, colors.outline, RoundedCornerShape(20.dp)).padding(18.dp),
                            decorationBox = { inner -> Box { if (apiKey.isEmpty()) Text("sk-…", color = colors.placeholder); inner() } },
                        )
                        Text(context.getString(R.string.onboarding_get_key), color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable {
                            runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse("https://platform.deepseek.com"))) }
                        })
                        if (error != null) Text(error.orEmpty(), color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                        if (busy) CircularProgressIndicator()
                    }
                    OnboardingStep.MODEL -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text(context.getString(R.string.onboarding_model_title), style = MaterialTheme.typography.headlineSmall)
                        listOf("deepseek-v4-pro" to R.string.onboarding_model_pro, "deepseek-v4-flash" to R.string.onboarding_model_flash).forEach { (id, label) ->
                            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { selectedModel = id }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = selectedModel == id, onClick = { selectedModel = id })
                                Text("$id\n${context.getString(label)}", modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                            }
                        }
                        Text(context.getString(R.string.onboarding_model_note), color = colors.placeholder, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall)
                    }
                    OnboardingStep.BACKUP -> Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(context.getString(R.string.onboarding_backup_title), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                        Text(context.getString(R.string.onboarding_backup_hint), style = MaterialTheme.typography.bodySmall, color = colors.placeholder, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(18.dp))
                        CapsuleButton(if (imported) context.getString(R.string.onboarding_imported) else context.getString(R.string.onboarding_import), onClick = { importLauncher.launch(arrayOf("*/*")) }, enabled = !busy)
                        if (busy) {
                            if ((progress ?: 0f) > 0f) LinearProgressIndicator(progress = { (progress ?: 0f).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                            else CircularProgressIndicator()
                        }
                        if (error != null) Text(error.orEmpty(), color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                    }
                    OnboardingStep.THEME -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        Text(context.getString(R.string.onboarding_theme_title), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                        AccentColor.entries.chunked(4).forEach { row -> Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                            row.forEach { accent ->
                                val color = accent.primary(false)
                                Box(Modifier.size(48.dp).clip(CircleShape).background(color).then(if (selectedTheme == accent) Modifier.border(3.dp, MaterialTheme.colorScheme.onBackground, CircleShape) else Modifier).clickable { selectedTheme = accent })
                            }
                        } }
                    }
                }
            }
        }
        when (step) {
            OnboardingStep.WELCOME -> Unit
            OnboardingStep.KEY -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { apiKey = ""; step = OnboardingStep.BACKUP }) { Text(context.getString(R.string.onboarding_later)) }
                TextButton(enabled = !busy, onClick = {
                    if (!apiKey.startsWith("sk-") || apiKey.length < 12 || apiKey.any { it.code !in 0x21..0x7e }) {
                        error = context.getString(R.string.onboarding_key_invalid)
                    } else scope.launch {
                        busy = true
                        error = null
                        val result = chatApi.fetchModels(ProviderKind.DEEPSEEK.defaultBaseUrl, apiKey.trim())
                        busy = false
                        if (result.isSuccess) next() else error = context.getString(R.string.onboarding_key_rejected)
                    }
                }) { Text(context.getString(R.string.onboarding_next)) }
            }
            OnboardingStep.MODEL, OnboardingStep.BACKUP -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { next() }) { Text(if (step == OnboardingStep.BACKUP && !imported) context.getString(R.string.onboarding_skip) else context.getString(R.string.onboarding_next)) }
            }
            OnboardingStep.THEME -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Box(
                    Modifier.clip(CircleShape).background(selectedTheme.primary(false)).clickable { scope.launch { finish() } }.padding(horizontal = 22.dp, vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(context.getString(R.string.onboarding_start), color = Color.White, style = MaterialTheme.typography.labelLarge) }
            }
        }
    }
}
