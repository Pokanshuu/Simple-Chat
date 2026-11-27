package com.simplechat.app.ui.favorites

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simplechat.app.R
import com.simplechat.app.data.Attachment
import com.simplechat.app.data.Attachments
import com.simplechat.app.data.FavoriteContent
import com.simplechat.app.data.FavoriteGroupMode
import com.simplechat.app.db.FavoriteEntity
import com.simplechat.app.db.FavoriteFolderEntity
import com.simplechat.app.ui.chat.AttachmentViewerDialog
import com.simplechat.app.ui.chat.rememberAttachmentImage
import com.simplechat.app.ui.common.AppConfirmDialog
import com.simplechat.app.ui.common.AppIcons
import com.simplechat.app.ui.common.AppMenu
import com.simplechat.app.ui.common.AppMenuItem
import com.simplechat.app.ui.common.AppTextInputDialog
import com.simplechat.app.ui.common.CapsuleButton
import com.simplechat.app.ui.common.ContainerShape
import com.simplechat.app.ui.common.IconCircleButton
import com.simplechat.app.ui.common.PillShape
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
    val groupMode by vm.groupMode.collectAsStateWithLifecycle()
    val defaultName = stringResource(R.string.favorites_default)
    var manageOpen by remember { mutableStateOf(false) }
    var folderTarget by remember { mutableStateOf<FavoriteEntity?>(null) }
    var noteTarget by remember { mutableStateOf<FavoriteEntity?>(null) }
    var deleteTarget by remember { mutableStateOf<FavoriteEntity?>(null) }
    var viewer by remember { mutableStateOf<Pair<List<Attachment>, Attachment>?>(null) }
    var selectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var batchDeleteOpen by remember { mutableStateOf(false) }
    var batchFolderOpen by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.ensureDefault(defaultName) }
    BackHandler(enabled = selectionMode) {
        selectionMode = false
        selectedIds = emptySet()
    }

    val folderNames = remember(folders) { folders.associate { it.id to it.name } }
    val membershipsByFavorite = remember(memberships) { memberships.groupBy { it.favoriteId } }
    val visible = (vm.results ?: favorites).filter { favorite ->
        val filter = vm.folderFilter
        filter == null || membershipsByFavorite[favorite.id].orEmpty().any { it.folderId == filter }
    }
    val groups = remember(visible, groupMode, folders, folderNames, membershipsByFavorite) {
        when (groupMode) {
            FavoriteGroupMode.CONVERSATION -> visible
                .groupBy { it.conversationId }
                .map { (conversationId, items) ->
                    FavoriteGroup(
                        key = conversationId,
                        title = items.first().conversationTitle.takeIf { it.isNotBlank() }
                            ?: "",
                        items = items,
                    )
                }

            FavoriteGroupMode.FOLDER -> folders
                .filter { folder -> vm.folderFilter == null || folder.id == vm.folderFilter }
                .map { folder ->
                    FavoriteGroup(
                        key = folder.id,
                        title = folder.name,
                        items = visible.filter { favorite ->
                            membershipsByFavorite[favorite.id].orEmpty().any { it.folderId == folder.id }
                        },
                        folderId = folder.id,
                    )
                }
        }
    }
    // 会话标题为空时在 UI 层回退到「未命名会话」，避免把本地化字符串塞进数据层。
    val collapsed = remember { mutableStateMapOf<String, Boolean>() }

    Column(
        modifier.fillMaxSize().background(colors.pageBackground)
            .statusBarsPadding().navigationBarsPadding(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 8.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconCircleButton(
                AppIcons.ChevronLeft,
                stringResource(R.string.action_back),
                if (selectionMode) {
                    { selectionMode = false; selectedIds = emptySet() }
                } else {
                    onBack
                },
                size = 40.dp,
                iconSize = 20.dp,
            )
            Text(
                if (selectionMode) {
                    stringResource(R.string.favorites_selected_count, selectedIds.size)
                } else {
                    stringResource(R.string.favorites_title)
                },
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
            if (selectionMode) {
                IconCircleButton(
                    AppIcons.Close,
                    stringResource(R.string.action_cancel),
                    onClick = { selectionMode = false; selectedIds = emptySet() },
                    size = 40.dp,
                    iconSize = 19.dp,
                )
            } else {
                IconCircleButton(
                    AppIcons.ListChecks,
                    stringResource(R.string.favorites_select_mode),
                    onClick = { selectionMode = true },
                    size = 40.dp,
                    iconSize = 19.dp,
                )
                val nextMode = if (groupMode == FavoriteGroupMode.CONVERSATION) {
                    FavoriteGroupMode.FOLDER
                } else {
                    FavoriteGroupMode.CONVERSATION
                }
                IconCircleButton(
                    if (groupMode == FavoriteGroupMode.CONVERSATION) AppIcons.Folder else AppIcons.MessageSquare,
                    stringResource(
                        if (nextMode == FavoriteGroupMode.FOLDER) {
                            R.string.favorites_group_by_folder
                        } else {
                            R.string.favorites_group_by_conversation
                        },
                    ),
                    onClick = { vm.setGroupMode(nextMode) },
                    size = 40.dp,
                    iconSize = 19.dp,
                )
                IconCircleButton(
                    AppIcons.FolderPlus,
                    stringResource(R.string.favorites_manage),
                    onClick = { manageOpen = true },
                    size = 40.dp,
                    iconSize = 19.dp,
                )
            }
        }

        OutlinedTextField(
            value = vm.query,
            onValueChange = vm::updateQuery,
            modifier = Modifier.fillMaxWidth().padding(horizontal = Space.Screen),
            singleLine = true,
            shape = PillShape,
            textStyle = MaterialTheme.typography.bodyMedium,
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = colors.fieldBackground,
                unfocusedContainerColor = colors.fieldBackground,
                focusedBorderColor = Color.Transparent,
                unfocusedBorderColor = Color.Transparent,
                cursorColor = MaterialTheme.colorScheme.primary,
            ),
            leadingIcon = { Icon(AppIcons.Search, null, tint = colors.placeholder) },
            trailingIcon = if (vm.query.isNotEmpty()) {
                {
                    IconCircleButton(AppIcons.Close, stringResource(R.string.action_close), {
                        vm.updateQuery("")
                    }, size = 32.dp, iconSize = 14.dp)
                }
            } else null,
            placeholder = {
                Text(stringResource(R.string.favorites_search_hint), color = colors.placeholder)
            },
        )

        LazyRow(
            Modifier.fillMaxWidth().padding(horizontal = Space.Screen, vertical = Space.Gap),
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

        Box(Modifier.weight(1f)) {
            if (favorites.isEmpty() && vm.query.isBlank()) {
                FavoritesEmptyState(searching = false)
            } else if (visible.isEmpty()) {
                FavoritesEmptyState(searching = vm.query.isNotBlank(), folderEmpty = vm.folderFilter != null)
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = if (selectionMode) 80.dp else 24.dp),
                ) {
                    groups.forEach { group ->
                        val expanded = collapsed["${groupMode.id}:${group.key}"] ?: true
                        item(key = "header-${groupMode.id}-${group.key}") {
                            FavoriteGroupHeader(
                                title = group.title.ifBlank { stringResource(R.string.favorites_untitled) },
                                count = group.items.size,
                                expanded = expanded,
                                onClick = {
                                    collapsed["${groupMode.id}:${group.key}"] = !expanded
                                },
                            )
                        }
                        if (expanded) {
                            items(group.items, key = { "${group.key}:${it.id}" }) { favorite ->
                                val folderIds = membershipsByFavorite[favorite.id].orEmpty().map { it.folderId }
                                val isSelected = favorite.id in selectedIds
                                FavoriteRow(
                                    favorite = favorite,
                                    folderNames = folderIds.mapNotNull { folderNames[it] },
                                    groupsInFolder = groupMode == FavoriteGroupMode.FOLDER,
                                    selectionMode = selectionMode,
                                    selected = isSelected,
                                    onClick = {
                                        if (selectionMode) {
                                            selectedIds = if (isSelected) selectedIds - favorite.id else selectedIds + favorite.id
                                        } else {
                                            onOpen(favorite)
                                        }
                                    },
                                    onLongClick = {
                                        selectionMode = true
                                        selectedIds = selectedIds + favorite.id
                                    },
                                    onManageFolders = { folderTarget = favorite },
                                    onEditNote = { noteTarget = favorite },
                                    onDelete = { deleteTarget = favorite },
                                    onOpenImages = { images, initial -> viewer = images to initial },
                                )
                            }
                        }
                    }
                }
            }
            if (selectionMode) {
                FavoriteSelectionBar(
                    onDelete = { batchDeleteOpen = true },
                    onChooseFolders = { batchFolderOpen = true },
                    onCancel = { selectionMode = false; selectedIds = emptySet() },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
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
            counts = memberships.groupingBy { it.folderId }.eachCount(),
            onDismiss = { folderTarget = null },
            onApply = { ids ->
                vm.setFolders(favorite.id, ids)
                folderTarget = null
            },
            onCreateFolder = {
                folderTarget = null
                manageOpen = true
            },
            onManageFolders = {
                folderTarget = null
                manageOpen = true
            },
        )
    }
    noteTarget?.let { favorite ->
        AppTextInputDialog(
            title = stringResource(R.string.favorites_edit_note),
            initial = favorite.note.orEmpty(),
            placeholder = stringResource(R.string.favorites_note_hint),
            onDismiss = { noteTarget = null },
            onConfirm = { note -> vm.updateNote(favorite.id, note); noteTarget = null },
        )
    }
    deleteTarget?.let { favorite ->
        AppConfirmDialog(
            title = stringResource(R.string.favorites_delete_title),
            message = stringResource(R.string.favorites_delete_body),
            confirmText = stringResource(R.string.action_delete),
            destructive = true,
            onDismiss = { deleteTarget = null },
            onConfirm = { vm.deleteFavorite(favorite.id); deleteTarget = null },
        )
    }
    if (batchDeleteOpen) {
        AppConfirmDialog(
            title = stringResource(R.string.favorites_delete_title),
            message = stringResource(R.string.favorites_batch_delete_body, selectedIds.size),
            confirmText = stringResource(R.string.action_delete),
            destructive = true,
            onDismiss = { batchDeleteOpen = false },
            onConfirm = {
                vm.deleteFavorites(selectedIds)
                batchDeleteOpen = false
                selectionMode = false
                selectedIds = emptySet()
            },
        )
    }
    if (batchFolderOpen) {
        FavoriteFolderSheet(
            folders = folders,
            selected = emptyList(),
            counts = memberships.groupingBy { it.folderId }.eachCount(),
            confirmText = stringResource(R.string.favorites_done),
            onDismiss = { batchFolderOpen = false },
            onApply = { ids ->
                vm.addFoldersToFavorites(selectedIds, ids)
                batchFolderOpen = false
                selectionMode = false
                selectedIds = emptySet()
            },
        )
    }
    viewer?.let { (images, initial) ->
        AttachmentViewerDialog(images = images, initial = initial, onDismiss = { viewer = null })
    }
}

private data class FavoriteGroup(
    val key: String,
    val title: String,
    val items: List<FavoriteEntity>,
    val folderId: String? = null,
)

@Composable
private fun FavoriteGroupHeader(
    title: String,
    count: Int,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalChatColors.current
    val stateLabel = stringResource(
        if (expanded) R.string.favorites_group_expanded else R.string.favorites_group_collapsed,
    )
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick)
            .semantics {
                heading()
                stateDescription = stateLabel
            }
            .heightIn(min = Space.Row)
            .padding(horizontal = Space.Screen),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.Gap),
    ) {
        Icon(
            if (expanded) AppIcons.ChevronDown else AppIcons.ChevronRight,
            contentDescription = null,
            tint = colors.placeholder,
            modifier = Modifier.size(16.dp),
        )
        Text(
            title,
            style = MaterialTheme.typography.labelMedium,
            color = colors.processText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            pluralStringResource(R.plurals.favorites_folder_items, count, count),
            style = MaterialTheme.typography.labelMedium,
            color = colors.placeholder,
        )
    }
}

@Composable
private fun FavoritesEmptyState(searching: Boolean, folderEmpty: Boolean = false) {
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
                if (searching) AppIcons.Search else if (folderEmpty) AppIcons.Folder else AppIcons.Bookmark,
                contentDescription = null,
                tint = colors.placeholder,
                modifier = Modifier.size(28.dp),
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(
                when {
                    searching -> R.string.favorites_no_match
                    folderEmpty -> R.string.favorites_empty_folder
                    else -> R.string.favorites_empty
                },
            ),
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
    groupsInFolder: Boolean,
    selectionMode: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onManageFolders: () -> Unit,
    onEditNote: () -> Unit,
    onDelete: () -> Unit,
    onOpenImages: (List<Attachment>, Attachment) -> Unit,
) {
    val colors = LocalChatColors.current
    val density = LocalDensity.current
    var menu by remember { mutableStateOf(false) }
    val images = remember(favorite.attachments) { Attachments.decode(favorite.attachments) }
    val cover = remember(favorite.coverImage, images) { images.firstOrNull() ?: Attachments.decode(favorite.coverImage).firstOrNull() }
    val targetPx = with(density) { 64.dp.roundToPx() }
    val bitmap = cover?.let { rememberAttachmentImage(it, targetPx) }
    val date = remember(favorite.messageCreatedAt) {
        SimpleDateFormat("MM-dd", Locale.getDefault()).format(Date(favorite.messageCreatedAt))
    }
    val roleMe = stringResource(R.string.export_role_me)
    val roleAi = stringResource(R.string.export_role_ai)
    val imageNote = stringResource(R.string.favorites_image_preview)
    val emptyNote = stringResource(R.string.favorites_empty_preview)
    val attachmentNote = stringResource(R.string.favorites_attachment_unsaved)
    val thumbnailLabel = stringResource(R.string.favorites_view_images, maxOf(favorite.imageCount, 1))
    val otherFolders = (folderNames.size - if (groupsInFolder) 1 else 0).coerceAtLeast(0)
    val trailing = when {
        groupsInFolder && otherFolders > 0 -> stringResource(R.string.favorites_other_folders, otherFolders)
        folderNames.isNotEmpty() -> folderNames.joinToString(" / ")
        else -> ""
    }
    val names = FavoriteContent.decodeNames(favorite.attachmentNames)
    val summary = when {
        favorite.preview.isNotBlank() -> favorite.preview
        favorite.imageCount > 0 || images.isNotEmpty() -> imageNote
        names.isNotEmpty() -> String.format(attachmentNote, names.joinToString("，"))
        else -> emptyNote
    }

    Column(
        Modifier.fillMaxWidth()
            .background(if (selected) colors.capsuleSelected else Color.Transparent),
    ) {
        Row(
            Modifier.fillMaxWidth()
                .combinedClickable(
                    role = Role.Button,
                    onClickLabel = stringResource(R.string.favorites_open_original),
                    onClick = onClick,
                    onLongClick = onLongClick,
                )
                .padding(horizontal = Space.Screen, vertical = Space.RowPadY),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(Space.Inline),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${if (favorite.role == "user") roleMe else roleAi} · $date",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.placeholder,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                    if (trailing.isNotEmpty()) {
                        Text(
                            trailing,
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.placeholder,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Text(
                    summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.processText,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                favorite.note?.takeIf { it.isNotBlank() }?.let { note ->
                    Text(
                        note,
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.placeholder,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Box(
                Modifier.size(64.dp).clip(SoftShape).background(colors.fieldBackground)
                    .semantics { contentDescription = thumbnailLabel }
                    .clickable(role = Role.Button, enabled = !selectionMode) {
                        val initial = cover ?: return@clickable
                        onOpenImages(images.ifEmpty { listOf(initial) }, initial)
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (bitmap != null) {
                    Image(bitmap, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                } else {
                    Icon(AppIcons.Bookmark, null, tint = colors.placeholder, modifier = Modifier.size(22.dp))
                }
                if (favorite.imageCount > 1) {
                    Box(
                        Modifier.align(Alignment.BottomEnd).padding(4.dp)
                            .clip(RoundedCornerShape(6.dp)).background(colors.scrim)
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                    ) {
                        Text(
                            "+${favorite.imageCount - 1}",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                        )
                    }
                }
            }
            Box {
                if (!selectionMode) {
                    IconCircleButton(
                        AppIcons.More,
                        stringResource(R.string.favorites_more),
                        onClick = { menu = true },
                        size = 30.dp,
                        iconSize = 18.dp,
                    )
                }
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
        HorizontalDivider(
            Modifier.padding(start = Space.Screen, end = Space.Screen),
            color = colors.divider,
        )
    }
}

@Composable
private fun FavoriteSelectionBar(
    onDelete: () -> Unit,
    onChooseFolders: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalChatColors.current
    Row(
        modifier.fillMaxWidth().background(colors.card)
            .heightIn(min = Space.Row).padding(horizontal = Space.Gap, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onChooseFolders, modifier = Modifier.weight(1f).heightIn(min = Space.Row)) {
            Icon(AppIcons.Folder, null, Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.favorites_choose_short), maxLines = 1)
        }
        TextButton(onClick = onDelete, modifier = Modifier.weight(1f).heightIn(min = Space.Row)) {
            Icon(AppIcons.Trash, null, Modifier.size(18.dp), tint = colors.danger)
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.favorites_remove_short), color = colors.danger, maxLines = 1)
        }
        TextButton(onClick = onCancel, modifier = Modifier.weight(1f).heightIn(min = Space.Row)) {
            Text(stringResource(R.string.action_cancel), maxLines = 1)
        }
    }
}

@Composable
internal fun FavoriteFolderSheet(
    folders: List<FavoriteFolderEntity>,
    selected: List<String>,
    onDismiss: () -> Unit,
    onApply: (List<String>) -> Unit,
    counts: Map<String, Int> = emptyMap(),
    onCreateFolder: (() -> Unit)? = null,
    onManageFolders: (() -> Unit)? = null,
    confirmText: String = stringResource(R.string.action_save),
) {
    val colors = LocalChatColors.current
    var checked by remember(selected) { mutableStateOf(selected.toSet()) }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Column(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                    .background(colors.card)
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.favorites_choose),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    IconCircleButton(
                        AppIcons.Close,
                        stringResource(R.string.action_close),
                        onDismiss,
                        size = 30.dp,
                        iconSize = 15.dp,
                    )
                }
                Column(
                    Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                ) {
                    folders.forEach { folder ->
                        val isChecked = folder.id in checked
                        val stateLabel = stringResource(
                            if (isChecked) R.string.favorites_included else R.string.favorites_not_included,
                        )
                        Row(
                            Modifier.fillMaxWidth()
                                .clip(SoftShape)
                                .background(if (isChecked) colors.capsuleSelected else Color.Transparent)
                                .clickable(role = Role.Checkbox) {
                                    checked = if (isChecked) checked - folder.id else checked + folder.id
                                }
                                .semantics { stateDescription = stateLabel }
                                .heightIn(min = Space.Row)
                                .padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = isChecked, onCheckedChange = null)
                            Text(
                                folder.name,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                pluralStringResource(
                                    R.plurals.favorites_folder_items,
                                    counts[folder.id] ?: 0,
                                    counts[folder.id] ?: 0,
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.placeholder,
                            )
                        }
                    }
                }
                if (onCreateFolder != null || onManageFolders != null) {
                    HorizontalDivider(color = colors.divider)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (onCreateFolder != null) {
                            TextButton(onClick = onCreateFolder) {
                                Icon(AppIcons.PlusCircle, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.favorites_new_folder))
                            }
                        }
                        if (onManageFolders != null) {
                            TextButton(onClick = onManageFolders) {
                                Icon(AppIcons.Folder, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.favorites_manage))
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
                    Button(
                        onClick = { onApply(checked.toList()) },
                        shape = PillShape,
                        modifier = Modifier.heightIn(min = Space.Row),
                    ) { Text(confirmText) }
                }
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
    val colors = LocalChatColors.current
    var creating by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<FavoriteFolderEntity?>(null) }
    var deleteTarget by remember { mutableStateOf<FavoriteFolderEntity?>(null) }
    var error by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().clip(ContainerShape).background(colors.card)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.favorites_manage),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                IconCircleButton(AppIcons.PlusCircle, stringResource(R.string.favorites_new_folder), onClick = {
                    creating = true
                })
            }
            if (error) Text(
                stringResource(R.string.favorites_folder_error),
                style = MaterialTheme.typography.bodySmall,
                color = colors.danger,
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
                                color = colors.placeholder,
                            )
                        }
                        TextButton(onClick = { renameTarget = folder }) { Text(stringResource(R.string.action_rename)) }
                        if (folder.id != com.simplechat.app.data.FavoriteFolders.DefaultId) {
                            TextButton(onClick = { deleteTarget = folder }) {
                                Text(stringResource(R.string.action_delete), color = colors.danger)
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
            onConfirm = { name -> onCreate(name) { ok -> error = !ok; if (ok) creating = false } },
        )
    }
    renameTarget?.let { folder ->
        AppTextInputDialog(
            title = stringResource(R.string.action_rename),
            initial = folder.name,
            onDismiss = { renameTarget = null },
            onConfirm = { name -> onRename(folder.id, name) { ok -> error = !ok; if (ok) renameTarget = null } },
        )
    }
    deleteTarget?.let { folder ->
        AppConfirmDialog(
            title = stringResource(R.string.favorites_delete_folder),
            message = stringResource(R.string.favorites_delete_folder_body),
            confirmText = stringResource(R.string.action_delete),
            destructive = true,
            onDismiss = { deleteTarget = null },
            onConfirm = { onDelete(folder.id); deleteTarget = null },
        )
    }
}
