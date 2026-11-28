package com.simplechat.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simplechat.app.R
import com.simplechat.app.data.StoredSettings
import com.simplechat.app.data.sync.SyncCoordinator
import com.simplechat.app.ui.common.AppConfirmDialog
import com.simplechat.app.ui.common.AppIcons
import com.simplechat.app.ui.common.AppTextInputDialog
import com.simplechat.app.ui.common.Space
import com.simplechat.app.ui.theme.LocalChatColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 云同步设置页（docs/40 §50.11）。
 *
 * 只做三件事：填账号、点同步、退出。**不做**自动同步开关、也不做"删除云端数据" ——
 * 前者需要 WorkManager 与后台策略，后者是独立危险动作，MVP 都不进。
 */
@Composable
internal fun SyncSettingsPage(
    stored: StoredSettings,
    coordinator: SyncCoordinator,
    onSetBaseUrl: (String) -> Unit,
    onSetUsername: (String) -> Unit,
    onSetPassword: (String) -> Unit,
    onSetRootDir: (String) -> Unit,
    onClear: () -> Unit,
) {
    val colors = LocalChatColors.current
    val status by coordinator.status.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<SyncField?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    val running = status is SyncCoordinator.Status.Running
    val configured = stored.syncBaseUrl.isNotBlank() &&
        stored.syncUsername.isNotBlank() &&
        stored.syncHasPassword

    val notSet = stringResource(R.string.sync_not_set)
    val passwordValue = if (stored.syncHasPassword) {
        stringResource(R.string.sync_password_saved)
    } else {
        notSet
    }

    SettingsSectionLabel(stringResource(R.string.sync_group_account))
    SettingsCard {
        SettingsNavRow(
            icon = AppIcons.Globe,
            label = stringResource(R.string.sync_server_url),
            value = stored.syncBaseUrl.ifBlank { notSet },
            onClick = { editing = SyncField.Server },
        )
        RowDivider(inset = Space.Screen)
        SettingsNavRow(
            icon = null,
            label = stringResource(R.string.sync_username),
            value = stored.syncUsername.ifBlank { notSet },
            onClick = { editing = SyncField.Username },
        )
        RowDivider(inset = Space.Screen)
        SettingsNavRow(
            icon = null,
            label = stringResource(R.string.sync_password),
            value = passwordValue,
            onClick = { editing = SyncField.Password },
        )
        RowDivider(inset = Space.Screen)
        SettingsNavRow(
            icon = AppIcons.Folder,
            label = stringResource(R.string.sync_root_dir),
            value = stored.syncRootDir,
            onClick = { editing = SyncField.Root },
        )
    }

    SettingsSectionLabel(stringResource(R.string.sync_group_status))
    SettingsCard {
        SettingsActionRow(
            label = stringResource(if (running) R.string.sync_running else R.string.sync_now),
            hint = statusHint(stored, status),
            onClick = { coordinator.syncAsync() },
            enabled = configured && !running,
        )
    }

    SettingsSectionLabel(stringResource(R.string.sync_group_danger))
    SettingsCard {
        SettingsActionRow(
            label = stringResource(R.string.sync_logout),
            hint = stringResource(R.string.sync_logout_body),
            onClick = { if (!running) confirmClear = true },
            enabled = !running,
        )
    }

    if (confirmClear) {
        AppConfirmDialog(
            title = stringResource(R.string.sync_logout),
            message = stringResource(R.string.sync_logout_body),
            confirmText = stringResource(R.string.sync_logout_confirm),
            destructive = true,
            onDismiss = { confirmClear = false },
            onConfirm = {
                confirmClear = false
                onClear()
            },
        )
    }

    editing?.let { field ->
        val config = when (field) {
            SyncField.Server -> FieldConfig(
                title = stringResource(R.string.sync_server_url),
                initial = stored.syncBaseUrl,
                placeholder = "https://dav.jianguoyun.com/dav/",
            )
            SyncField.Username -> FieldConfig(
                title = stringResource(R.string.sync_username),
                initial = stored.syncUsername,
                placeholder = "",
            )
            SyncField.Password -> FieldConfig(
                title = stringResource(R.string.sync_password),
                initial = "",
                placeholder = stringResource(R.string.sync_password_placeholder),
            )
            SyncField.Root -> FieldConfig(
                title = stringResource(R.string.sync_root_dir),
                initial = stored.syncRootDir,
                placeholder = "SimpleChat",
            )
        }
        AppTextInputDialog(
            title = config.title,
            initial = config.initial,
            placeholder = config.placeholder,
            onDismiss = { editing = null },
            onConfirm = { value ->
                editing = null
                when (field) {
                    SyncField.Server -> onSetBaseUrl(value)
                    SyncField.Username -> onSetUsername(value)
                    SyncField.Password -> if (value.isNotBlank()) onSetPassword(value)
                    SyncField.Root -> onSetRootDir(value)
                }
            },
        )
    }

    // 诊断提示：密码/令牌永不落到这里，只显示计数与一句人话。
    if (status is SyncCoordinator.Status.Failed) {
        Text(
            text = (status as SyncCoordinator.Status.Failed).text.resolve(),
            style = MaterialTheme.typography.bodySmall,
            color = colors.danger,
            modifier = Modifier.padding(horizontal = Space.Screen, vertical = 6.dp),
        )
    }
}

private enum class SyncField { Server, Username, Password, Root }

private data class FieldConfig(val title: String, val initial: String, val placeholder: String)

@Composable
private fun statusHint(stored: StoredSettings, status: SyncCoordinator.Status): String = when (status) {
    is SyncCoordinator.Status.Running -> stringResource(
        R.string.sync_progress,
        (status.progress.coerceIn(0f, 1f) * 100).toInt(),
    )
    is SyncCoordinator.Status.Done -> {
        val report = status.report
        buildString {
            append(
                stringResource(
                    R.string.sync_result,
                    report.pushed,
                    report.pulled,
                    report.deleted,
                    report.conflicts,
                ),
            )
            if (report.failed > 0) {
                append(" · ")
                append(stringResource(R.string.sync_partial_failed, report.failed))
            }
        }
    }
    is SyncCoordinator.Status.Failed -> status.text.resolve()
    SyncCoordinator.Status.Idle -> if (stored.syncLastAtMillis > 0L) {
        stringResource(R.string.sync_last_at, formatTimestamp(stored.syncLastAtMillis))
    } else {
        stringResource(R.string.sync_never)
    }
}

internal fun formatTimestamp(millis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(millis))
