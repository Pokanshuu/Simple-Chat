package com.simplechat.app.ui.settings

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.simplechat.app.R
import com.simplechat.app.data.FontFiles
import com.simplechat.app.data.FontSlot
import com.simplechat.app.data.ImportedFont
import com.simplechat.app.data.SettingsStore
import com.simplechat.app.ui.common.AppConfirmDialog
import com.simplechat.app.ui.common.AppIcons
import com.simplechat.app.ui.common.IconCircleButton
import com.simplechat.app.ui.common.Space
import com.simplechat.app.ui.theme.forgetFontFamily
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun FontLibraryDialog(fonts: List<ImportedFont>, store: SettingsStore, onRemove: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var remove by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<ImportedFont?>(null) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = Space.Screen).heightIn(max = 560.dp)
            .background(com.simplechat.app.ui.theme.LocalChatColors.current.card)
            .verticalScroll(rememberScrollState()).padding(Space.Card), verticalArrangement = Arrangement.spacedBy(Space.Gap)) {
            Text(stringResource(R.string.settings_custom_fonts), style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
            if (fonts.isEmpty()) Text(stringResource(R.string.fonts_empty_hint), style = androidx.compose.material3.MaterialTheme.typography.bodyMedium)
            fonts.forEach { font ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("${font.displayName}\n${stringResource(R.string.fonts_preview_line)}", Modifier.weight(1f),
                        style = androidx.compose.material3.MaterialTheme.typography.bodyMedium)
                    IconCircleButton(AppIcons.Trash, stringResource(R.string.fonts_remove), { remove = font }, tint = com.simplechat.app.ui.theme.LocalChatColors.current.danger)
                }
            }
            IconCircleButton(AppIcons.Close, stringResource(R.string.action_close), onDismiss)
        }
    }
    remove?.let { font ->
        AppConfirmDialog(
            title = stringResource(R.string.fonts_remove),
            message = font.displayName,
            confirmText = stringResource(R.string.action_delete),
            onDismiss = { remove = null },
            onConfirm = {
                remove = null
                scope.launch {
                    store.removeImportedFont(font.id)
                    onRemove(font.id)
                    withContext(Dispatchers.IO) { FontFiles.delete(context, font) }
                    forgetFontFamily(context, font)
                }
            },
        )
    }
}
