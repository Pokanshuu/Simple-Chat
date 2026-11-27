package com.simplechat.app.ui.favorites

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simplechat.app.R
import com.simplechat.app.data.Attachments
import com.simplechat.app.db.FavoriteEntity
import com.simplechat.app.db.FavoriteFolderEntity
import com.simplechat.app.ui.chat.rememberAttachmentImage
import com.simplechat.app.ui.common.AppConfirmDialog
import com.simplechat.app.ui.common.AppIcons
import com.simplechat.app.ui.common.AppMenu
import com.simplechat.app.ui.common.AppMenuItem
import com.simplechat.app.ui.common.AppTextInputDialog
import com.simplechat.app.ui.common.CapsuleButton
import com.simplechat.app.ui.common.ContainerShape
import com.simplechat.app.ui.common.IconCircleButton
import com.simplechat.app.ui.common.SoftShape
import com.simplechat.app.ui.common.Space
import com.simplechat.app.ui.theme.LocalChatColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun FavoritesScreen(
    vm: FavoritesViewModel,
    onBack: () -> Unit,
    onOpen: (FavoriteEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalChatColors.current
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    val folders by vm.folders.collectAsStateWithLifecycle()
    val memberships by vm.memberships.collectAsStateWithLifecycle()
    val defaultName = stringResource(R.string.favorites_default)
    var manageOpen by remember { mutableStateOf(false) }
    var folderTarget by remember { mutableStateOf<FavoriteEntity?>(null) }
    var noteTarget by remember { mutableStateOf<FavoriteEntity?>(null) }
    var deleteTarget by remember { mutableStateOf<FavoriteEntity?>(null) }

    LaunchedEffect(Unit) { vm.ensureDefault(defaultName) }

    val folderNames = remember(folders) { folders.associate { it.id to it.name } }
    val membershipsByFavorite = remember(memberships) { memberships.groupBy { it.favoriteId } }
    val visible = (vm.results ?: favorites).filter { favorite ->
        val filter = vm.folderFilter
        filter == null || membershipsByFavorite[favorite.id].orEmpty().any { it.folderId == filter }
    }
    val grouped = visible.groupBy { it.conversationId }

    Column(
        modifier.fillMaxSize().background(colors.pageBackground)
            .statusBarsPadding().navigationBarsPadding(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconCircleButton(AppIcons.ChevronLeft, stringResource(R.string.action_back), onBack)
            Text(
                stringResource(R.string.favorites_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
            IconCircleButton(AppIcons.FolderPlus, stringResource(R.string.favorites_manage), onClick = {
                manageOpen = true
            })
        }
        OutlinedTextField(
            value = vm.query,
            onValueChange = vm::updateQuery,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            singleLine = true,
            shape = ContainerShape,
            leadingIcon = { Icon(AppIcons.Search, null) },
            trailingIcon = if (vm.query.isNotEmpty()) {
                {
                    IconCircleButton(AppIcons.Close, stringResource(R.string.action_close), {
                        vm.updateQuery("")
                    }, size = 32.dp, iconSize = 14.dp)
                }
            } else null,
            placeholder = { Text(stringResource(R.string.favorites_search_hint)) },
        )
        LazyRow(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(Space.Gap),
        ) {
            item {
                CapsuleButton(
                    text = stringResource(R.string.favorites_all),
                    onClick = { vm.selectFolder(null) },
                    selected = vm.folderFilter == null,
                )
            }
            items(folders, key = { it.id }) { folder ->
                CapsuleButton(
                    text = folder.name,
                    onClick = { vm.selectFolder(folder.id) },
                    selected = vm.folderFilter == folder.id,
                )
            }
        }
        if (visible.isEmpty()) {
            FavoritesEmptyState(searching = vm.query.isNotBlank())
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                grouped.forEach { (conversationId, items) ->
                    item(key = "header-$conversationId") {
                        Text(
                            items.first().conversationTitle.ifBlank { stringResource(R.string.favorites_untitled) },
                            style = MaterialTheme.typography.labelLarge,
                            color = colors.placeholder,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                    items(items, key = { it.id }) { favorite ->
                        FavoriteRow(
                            favorite = favorite,
                            folderNames = membershipsByFavorite[favorite.id].orEmpty()
                                .mapNotNull { folderNames[it.folderId] },
                            onClick = { onOpen(favorite) },
                            onManageFolders = { folderTarget = favorite },
                            onEditNote = { noteTarget = favorite },
                            onDelete = { deleteTarget = favorite },
                        )
                    }
                }
            }
        }
    }

    if (manageOpen) {
        FavoriteFoldersDialog(
            folders = folders,
            counts = memberships.groupingBy { it.folderId }.eachCount(),
            onDismiss = { manageOpen = false },
            onCreate = { name, done -> vm.createFolder(name, done) },
            onRename = { id, name, done -> vm.renameFolder(id, name, done) },
            onDelete = vm::deleteFolder,
        )
    }
    folderTarget?.let { favorite ->
        FavoriteFolderSheet(
            folders = folders,
            selected = vm.folderIdsOf(favorite.id),
            onDismiss = { folderTarget = null },
            onApply = { ids ->
                vm.setFolders(favorite.id, ids)
                folderTarget = null
            },
        )
    }
    noteTarget?.let { favorite ->
        AppTextInputDialog(
            title = stringResource(R.string.favorites_edit_note),
            initial = favorite.note.orEmpty(),
            placeholder = stringResource(R.string.favorites_note_hint),
            onDismiss = { noteTarget = null },
            onConfirm = { note ->
                vm.updateNote(favorite.id, note)
                noteTarget = null
            },
        )
    }
    deleteTarget?.let { favorite ->
        AppConfirmDialog(
            title = stringResource(R.string.favorites_delete_title),
            message = stringResource(R.string.favorites_delete_body),
            confirmText = stringResource(R.string.action_delete),
            destructive = true,
            onDismiss = { deleteTarget = null },
            onConfirm = {
                vm.deleteFavorite(favorite.id)
                deleteTarget = null
            },
        )
    }
}

@Composable
private fun FavoritesEmptyState(searching: Boolean) {
    val colors = LocalChatColors.current
    Column(
        Modifier.fillMaxSize().padding(horizontal = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier.size(72.dp).clip(CircleShape).background(colors.fieldBackground),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (searching) AppIcons.Search else AppIcons.Bookmark,
                contentDescription = null,
                tint = colors.placeholder,
                modifier = Modifier.size(28.dp),
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(if (searching) R.string.favorites_no_match else R.string.favorites_empty),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(if (searching) R.string.favorites_no_match_hint else R.string.favorites_empty_hint),
            style = MaterialTheme.typography.bodySmall,
            color = colors.placeholder,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun FavoriteRow(
    favorite: FavoriteEntity,
    folderNames: List<String>,
    onClick: () -> Unit,
    onManageFolders: () -> Unit,
    onEditNote: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = LocalChatColors.current
    var menu by remember { mutableStateOf(false) }
    val cover = remember(favorite.coverImage) { Attachments.decode(favorite.coverImage).firstOrNull() }
    val bitmap = cover?.let { rememberAttachmentImage(it, 120) }
    val date = remember(favorite.createdAt) {
        SimpleDateFormat("MM-dd", Locale.getDefault()).format(Date(favorite.createdAt))
    }
    val roleMe = stringResource(R.string.export_role_me)
    val roleAi = stringResource(R.string.export_role_ai)
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(56.dp).clip(SoftShape).background(colors.fieldBackground),
            contentAlignment = Alignment.Center,
        ) {
            if (bitmap != null) {
                Image(bitmap, null, Modifier.fillMaxSize())
            } else {
                Icon(AppIcons.Bookmark, null, tint = colors.placeholder, modifier = Modifier.size(22.dp))
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                favorite.preview.ifBlank { stringResource(R.string.favorites_empty_preview) },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            favorite.note?.takeIf { it.isNotBlank() }?.let { note ->
                Text(
                    note,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val meta = buildString {
                append(if (favorite.role == "user") roleMe else roleAi)
                append(" · ")
                append(date)
                if (folderNames.isNotEmpty()) {
                    append(" · ")
                    append(folderNames.joinToString(" / "))
                }
            }
            Text(meta, style = MaterialTheme.typography.labelSmall, color = colors.placeholder, maxLines = 1)
        }
        Box {
            IconCircleButton(AppIcons.More, stringResource(R.string.favorites_more), onClick = { menu = true })
            AppMenu(expanded = menu, onDismissRequest = { menu = false }) {
                AppMenuItem(stringResource(R.string.favorites_manage), AppIcons.Folder) {
                    menu = false
                    onManageFolders()
                }
                AppMenuItem(stringResource(R.string.favorites_edit_note), AppIcons.Edit) {
                    menu = false
                    onEditNote()
                }
                AppMenuItem(stringResource(R.string.action_delete), AppIcons.Trash, destructive = true) {
                    menu = false
                    onDelete()
                }
            }
        }
    }
}

@Composable
internal fun FavoriteFolderSheet(
    folders: List<FavoriteFolderEntity>,
    selected: List<String>,
    onDismiss: () -> Unit,
    onApply: (List<String>) -> Unit,
) {
    var checked by remember(selected) { mutableStateOf(selected.toSet()) }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().clip(ContainerShape).background(LocalChatColors.current.card)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.favorites_choose), style = MaterialTheme.typography.titleMedium)
            Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                folders.forEach { folder ->
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            checked = if (folder.id in checked) checked - folder.id else checked + folder.id
                        }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = folder.id in checked, onCheckedChange = null)
                        Text(folder.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
                TextButton(onClick = { onApply(checked.toList()) }) { Text(stringResource(R.string.action_save)) }
            }
        }
    }
}

@Composable
private fun FavoriteFoldersDialog(
    folders: List<FavoriteFolderEntity>,
    counts: Map<String, Int>,
    onDismiss: () -> Unit,
    onCreate: (String, (Boolean) -> Unit) -> Unit,
    onRename: (String, String, (Boolean) -> Unit) -> Unit,
    onDelete: (String) -> Unit,
) {
    var creating by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<FavoriteFolderEntity?>(null) }
    var deleteTarget by remember { mutableStateOf<FavoriteFolderEntity?>(null) }
    var error by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().clip(ContainerShape).background(LocalChatColors.current.card)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.favorites_manage), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                IconCircleButton(AppIcons.PlusCircle, stringResource(R.string.favorites_new_folder), onClick = {
                    creating = true
                })
            }
            if (error) Text(
                stringResource(R.string.favorites_folder_error),
                style = MaterialTheme.typography.bodySmall,
                color = LocalChatColors.current.danger,
            )
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                folders.forEach { folder ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(folder.name, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                pluralStringResource(
                                    R.plurals.favorites_folder_items,
                                    counts[folder.id] ?: 0,
                                    counts[folder.id] ?: 0,
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = LocalChatColors.current.placeholder,
                            )
                        }
                        TextButton(onClick = { renameTarget = folder }) { Text(stringResource(R.string.action_rename)) }
                        if (folder.id != com.simplechat.app.data.FavoriteFolders.DefaultId) {
                            TextButton(onClick = { deleteTarget = folder }) {
                                Text(stringResource(R.string.action_delete), color = LocalChatColors.current.danger)
                            }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
            }
        }
    }

    if (creating) {
        AppTextInputDialog(
            title = stringResource(R.string.favorites_new_folder),
            initial = "",
            onDismiss = { creating = false },
            onConfirm = { name ->
                onCreate(name) { ok -> error = !ok; if (ok) creating = false }
            },
        )
    }
    renameTarget?.let { folder ->
        AppTextInputDialog(
            title = stringResource(R.string.action_rename),
            initial = folder.name,
            onDismiss = { renameTarget = null },
            onConfirm = { name ->
                onRename(folder.id, name) { ok -> error = !ok; if (ok) renameTarget = null }
            },
        )
    }
    deleteTarget?.let { folder ->
        AppConfirmDialog(
            title = stringResource(R.string.favorites_delete_folder),
            message = stringResource(R.string.favorites_delete_folder_body),
            confirmText = stringResource(R.string.action_delete),
            destructive = true,
            onDismiss = { deleteTarget = null },
            onConfirm = {
                onDelete(folder.id)
                deleteTarget = null
            },
        )
    }
}
