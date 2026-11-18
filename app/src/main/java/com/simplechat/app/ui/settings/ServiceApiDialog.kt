package com.simplechat.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.simplechat.app.R
import com.simplechat.app.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun ServiceApiDialog(files: Boolean, stored: StoredSettings, store: SettingsStore, onDismiss: () -> Unit) {
    val original = if (files) stored.filesApi else stored.searchApi
    var enabled by remember { mutableStateOf(original.enabled) }
    var base by remember { mutableStateOf(original.baseUrl) }
    var reuse by remember { mutableStateOf(original.reuseChatKey) }
    var key by remember { mutableStateOf("") }
    var path by remember { mutableStateOf(if (files) original.filesPath else original.searchPath) }
    var fetch by remember { mutableStateOf(original.fetchPath) }
    var count by remember { mutableStateOf(original.count.toString()) }
    var purpose by remember { mutableStateOf(original.purpose) }
    var threshold by remember { mutableStateOf(original.longTextChars.toString()) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val profileId = remember { stored.activeApiProfileId }
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(stringResource(if (files) R.string.service_files_title else R.string.service_search_title)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(if (files) R.string.service_files_hint else R.string.service_search_hint), style = MaterialTheme.typography.bodySmall)
            Row { Text(stringResource(R.string.service_enabled), Modifier.weight(1f)); Switch(enabled, { enabled = it }) }
            OutlinedTextField(base, { base = it }, label = { Text("Base URL") }, placeholder = { Text("https://api.deepseek.com") }, singleLine = true)
            Text(stringResource(R.string.service_base_hint), style = MaterialTheme.typography.labelSmall)
            Row { Text(stringResource(R.string.service_reuse_key), Modifier.weight(1f)); Switch(reuse, { reuse = it }) }
            if (!reuse) OutlinedTextField(key, { key = it }, label = { Text(stringResource(R.string.service_custom_key)) },
                supportingText = { Text(stringResource(R.string.service_key_hint)) }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
            OutlinedTextField(path, { path = it }, label = { Text(stringResource(R.string.service_path)) }, singleLine = true)
            if (!files) {
                OutlinedTextField(fetch, { fetch = it }, label = { Text(stringResource(R.string.service_fetch_path)) }, singleLine = true)
                OutlinedTextField(count, { count = it }, label = { Text(stringResource(R.string.service_count)) }, singleLine = true)
            } else {
                OutlinedTextField(threshold, { threshold = it }, label = { Text(stringResource(R.string.service_text_threshold)) }, singleLine = true)
                Text(stringResource(R.string.service_purpose))
                listOf("text_recognition" to R.string.service_text_only, "rich_layout_recognition" to R.string.service_rich_layout).forEach { (id, label) ->
                    Row { RadioButton(purpose == id, { purpose = id }); TextButton({ purpose = id }) { Text(stringResource(label)) } }
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } },
        confirmButton = { TextButton(enabled = !saving, onClick = {
            scope.launch {
                saving = true
                try {
                    val n = count.toIntOrNull()
                    val chars = threshold.toIntOrNull()
                    if (chars == null || chars !in 4_000..1_000_000) throw AppTextException(R.string.service_threshold_invalid)
                    if (n == null || n !in 1..50) throw AppTextException(R.string.service_count_invalid)
                    val encrypted = if (key.isBlank()) original.apiKeyEncrypted else store.encryptServiceKey(key)
                    if (enabled && !reuse && encrypted.isBlank()) throw AppTextException(R.string.service_key_missing)
                    store.setServiceApi(files, original.copy(enabled = enabled, baseUrl = base.trim(), reuseChatKey = reuse,
                        apiKeyEncrypted = encrypted, searchPath = if (files) original.searchPath else path.trim(),
                        filesPath = if (files) path.trim() else original.filesPath, fetchPath = fetch.trim(), count = n, purpose = purpose, longTextChars = chars), profileId)
                    onDismiss()
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { error = failure.userMessage() }
                finally { saving = false }
            }
        }) { Text(stringResource(android.R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !saving) { Text(stringResource(android.R.string.cancel)) } },
    )
}
