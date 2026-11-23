package com.simplechat.app.ui.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.simplechat.app.R
import com.simplechat.app.ui.common.AppIcons
import com.simplechat.app.ui.common.ContainerShape
import com.simplechat.app.ui.common.IconCircleButton
import com.simplechat.app.ui.common.PillShape
import com.simplechat.app.ui.common.SoftShape
import com.simplechat.app.ui.theme.LocalChatColors

@Composable
internal fun ShareCheck(checked: Boolean, enabled: Boolean, modifier: Modifier = Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    val outline = MaterialTheme.colorScheme.outline.copy(alpha = if (enabled) 1f else .35f)
    Box(modifier.size(26.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            if (checked) drawCircle(primary)
            else drawCircle(outline, radius = size.minDimension / 2 - 1.dp.toPx(), style = Stroke(1.5.dp.toPx()))
        }
        if (checked) Icon(AppIcons.Check, null, tint = Color.White, modifier = Modifier.size(18.dp))
    }
}

/** Consume the child's DOWN so links, images and fold controls cannot act in multi-select mode. */
@Composable
internal fun ShareSelectableRow(
    group: MessageShareGroup, selected: Boolean, onToggle: () -> Unit, content: @Composable () -> Unit,
) {
    val toggle by rememberUpdatedState(onToggle)
    val enabled by rememberUpdatedState(group.available)
    val label = stringResource(R.string.share_group_select)
    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
        role = Role.Checkbox
        this.selected = selected
        contentDescription = label
        if (!group.available) disabled()
        onClick { if (enabled) toggle(); enabled }
    }.pointerInput(group.id) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            down.consume()
            var dragged = false
            var multiple = false
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.size > 1) multiple = true
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) dragged = true
                if (change.changedToUpIgnoreConsumed()) {
                    // A consumed drag release cancels LazyColumn's fling. Only intercept taps;
                    // scrolling must receive its original UP to retain release velocity.
                    if (!dragged && !multiple) {
                        change.consume()
                        if (enabled && change.uptimeMillis - down.uptimeMillis < viewConfiguration.longPressTimeoutMillis) toggle()
                    }
                    break
                }
            }
        }
    }, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ShareCheck(selected, group.available, Modifier.padding(top = 8.dp))
        Box(Modifier.weight(1f)) { content() }
    }
}

@Composable
internal fun MessageShareTopBar(
    selection: MessageShareSelection,
    groups: List<MessageShareGroup>,
    onClose: () -> Unit,
    showClose: Boolean = true,
) {
    val available = groups.filter { it.available }
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).clip(CircleShape).clickable { selection.toggleAll(groups) }.padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ShareCheck(available.isNotEmpty() && available.all { it.id in selection.selectedIds }, available.isNotEmpty())
            Text(stringResource(R.string.share_select_all), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        }
        if (showClose) IconCircleButton(AppIcons.Close, stringResource(R.string.share_exit), onClose)
    }
}

@Composable
internal fun MessageDeleteBottomBar(count: Int, onCancel: () -> Unit, onDelete: () -> Unit) {
    val colors = LocalChatColors.current
    Column(Modifier.fillMaxWidth().background(colors.card).navigationBarsPadding().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.share_selected_count, count), style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.action_cancel)) }
            Button(onClick = onDelete, enabled = count > 0, modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = colors.danger, contentColor = Color.White)) {
                Text(stringResource(R.string.action_delete))
            }
        }
    }
}

@Composable
internal fun MessageShareBottomBar(
    count: Int, busy: Boolean, hint: String?, onImage: () -> Unit, onMarkdown: () -> Unit,
    onCopy: () -> Unit, onShare: () -> Unit, modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().background(LocalChatColors.current.card)
        .navigationBarsPadding().padding(horizontal = 16.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.share_selected_count, count), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            ShareAction(AppIcons.Image, stringResource(R.string.share_image), count > 0 && !busy, onImage)
            ShareAction(AppIcons.Download, stringResource(R.string.share_markdown), count > 0 && !busy, onMarkdown)
            ShareAction(AppIcons.Copy, stringResource(R.string.share_copy_text), count > 0 && !busy, onCopy)
            ShareAction(AppIcons.More, stringResource(R.string.share_more), count > 0 && !busy, onShare)
        }
        if (busy) { Spacer(Modifier.height(12.dp)); LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (hint != null) {
            Spacer(Modifier.height(10.dp))
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun MessageShareImageOptions(
    mode: ShareImageMode,
    onModeChange: (ShareImageMode) -> Unit,
    hideBranding: Boolean,
    onHideBrandingChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onGenerate: (hideReasoning: Boolean) -> Unit,
) {
    val colors = LocalChatColors.current
    // Reasoning requires an explicit opt-in each time the options open.
    var hideReasoning by remember { mutableStateOf(true) }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().clip(ContainerShape).background(colors.card)
                .verticalScroll(rememberScrollState()).padding(20.dp),
        ) {
            Text(stringResource(R.string.share_image_layout), style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(14.dp))
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ShareImageMode.entries.forEach { option ->
                    val selected = mode == option
                    val long = option == ShareImageMode.LONG
                    Row(
                        Modifier.fillMaxWidth().clip(SoftShape)
                            .background(if (selected) colors.capsuleSelected else Color.Transparent)
                            .selectable(selected, role = Role.RadioButton, onClick = { onModeChange(option) })
                            .heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        RadioButton(selected = selected, onClick = null)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(stringResource(if (long) R.string.share_image_long else R.string.share_image_pages),
                                style = MaterialTheme.typography.titleSmall,
                                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                            Text(stringResource(if (long) R.string.share_image_long_hint else R.string.share_image_pages_hint),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp), color = colors.divider)
            ShareImageCheckbox(stringResource(R.string.share_image_hide_branding), hideBranding, onHideBrandingChange)
            ShareImageCheckbox(stringResource(R.string.share_image_hide_reasoning), hideReasoning) { hideReasoning = it }
            Spacer(Modifier.height(14.dp))
            Button(onClick = { onGenerate(hideReasoning) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                shape = PillShape) {
                Text(stringResource(R.string.share_image_generate), style = MaterialTheme.typography.titleSmall)
            }
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = PillShape) {
                Text(stringResource(R.string.action_cancel), style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

@Composable
private fun ShareImageCheckbox(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(SoftShape)
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = onCheckedChange)
            .heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun RowScope.ShareAction(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    Column(Modifier.weight(1f).clickable(enabled = enabled, onClick = onClick).padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(52.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) .15f else .05f), CircleShape),
            contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) 1f else .3f), modifier = Modifier.size(24.dp))
        }
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else .4f))
    }
}
