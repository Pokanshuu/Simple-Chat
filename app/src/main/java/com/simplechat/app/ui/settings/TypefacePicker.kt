package com.simplechat.app.ui.settings

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.simplechat.app.R
import com.simplechat.app.data.ImportedFont
import com.simplechat.app.ui.common.AppIcons
import com.simplechat.app.ui.common.AppMenu
import com.simplechat.app.ui.common.AppMenuDivider
import com.simplechat.app.ui.common.AppMenuItem
import com.simplechat.app.ui.common.IconCircleButton
import com.simplechat.app.ui.common.Space
import com.simplechat.app.ui.theme.fontFamilyOf

/** The host owns font selection, importing, and library management. */
@Composable
internal fun TypefacePicker(
    context: Context,
    fonts: List<ImportedFont>,
    selectedId: String?,
    onSelect: (String?) -> Unit,
    onImport: () -> Unit,
    onManage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val selectedFont = fonts.firstOrNull { it.id == selectedId }
    val selectedFamily = remember(context, selectedFont) { selectedFont?.let { fontFamilyOf(context, it) } }
    val defaultLabel = stringResource(R.string.fonts_system_default)

    Row(
        modifier = modifier.fillMaxWidth().padding(end = Space.Screen),
        horizontalArrangement = Arrangement.spacedBy(Space.Gap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth()
                    .clickable { menuOpen = true }
                    .heightIn(min = Space.Row)
                    .padding(horizontal = Space.Screen, vertical = Space.RowPadY),
                horizontalArrangement = Arrangement.spacedBy(Space.IconGap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(AppIcons.FontSize, contentDescription = null, modifier = Modifier.size(Space.Icon))
                Column(Modifier.weight(1f)) {
                    Text(
                        selectedFont?.displayName ?: defaultLabel,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val previewStyle = MaterialTheme.typography.labelSmall
                    Text(
                        stringResource(R.string.fonts_preview_line),
                        style = previewStyle.copy(fontFamily = selectedFamily ?: previewStyle.fontFamily),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(AppIcons.ChevronRight, contentDescription = null, modifier = Modifier.size(16.dp))
            }
            AppMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
                    AppMenuItem(label = defaultLabel, selected = selectedFont == null) {
                        menuOpen = false
                        onSelect(null)
                    }
                    fonts.forEach { font ->
                        AppMenuItem(label = font.displayName, selected = selectedFont?.id == font.id) {
                            menuOpen = false
                            onSelect(font.id)
                        }
                    }
                    AppMenuDivider()
                    AppMenuItem(label = stringResource(R.string.settings_custom_fonts), icon = AppIcons.Settings) {
                        menuOpen = false
                        onManage()
                    }
                }
            }
        }
        IconCircleButton(
            icon = AppIcons.PlusCircle,
            contentDescription = stringResource(R.string.fonts_import),
            onClick = onImport,
            size = 48.dp,
            iconSize = 22.dp,
        )
    }
}
