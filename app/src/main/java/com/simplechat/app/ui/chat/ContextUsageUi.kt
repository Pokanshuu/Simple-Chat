package com.simplechat.app.ui.chat

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.simplechat.app.R
import com.simplechat.app.ui.theme.AccentGreenDark
import com.simplechat.app.ui.theme.AccentGreenLight
import com.simplechat.app.ui.theme.AccentPinkDark
import com.simplechat.app.ui.theme.AccentPinkLight
import com.simplechat.app.ui.theme.AccentPurpleDark
import com.simplechat.app.ui.theme.AccentPurpleLight
import com.simplechat.app.ui.theme.LocalChatColors
import com.simplechat.app.ui.theme.tokens.AppTheme

/** 同一套颜色同时驱动圆环、进度条和图例，避免三处各画各的。 */
@Composable
@ReadOnlyComposable
internal fun contextSegmentColor(kind: ContextSegmentKind): Color {
    val colors = LocalChatColors.current
    val dark = AppTheme.tokens.dark
    return when (kind) {
        ContextSegmentKind.PRESETS -> colors.warning
        ContextSegmentKind.COMPACTED_HISTORY -> if (dark) AccentPurpleDark else AccentPurpleLight
        ContextSegmentKind.MESSAGES -> MaterialTheme.colorScheme.primary
        ContextSegmentKind.ATTACHMENTS -> if (dark) AccentPinkDark else AccentPinkLight
        ContextSegmentKind.TOOL_CALLS -> if (dark) AccentGreenDark else AccentGreenLight
        ContextSegmentKind.MEMORY_RESERVE -> colors.placeholder.copy(alpha = 0.38f)
        ContextSegmentKind.AUTO_COMPACT_RESERVE -> colors.placeholder.copy(alpha = 0.58f)
        ContextSegmentKind.FREE_SPACE -> colors.placeholder.copy(alpha = 0.16f)
    }
}

@Composable
@ReadOnlyComposable
internal fun contextSegmentLabel(kind: ContextSegmentKind): String = stringResource(
    when (kind) {
        ContextSegmentKind.PRESETS -> R.string.context_segment_presets
        ContextSegmentKind.COMPACTED_HISTORY -> R.string.context_segment_compacted
        ContextSegmentKind.MESSAGES -> R.string.context_segment_messages
        ContextSegmentKind.ATTACHMENTS -> R.string.context_segment_attachments
        ContextSegmentKind.TOOL_CALLS -> R.string.context_segment_tools
        ContextSegmentKind.MEMORY_RESERVE -> R.string.context_segment_memory_reserve
        ContextSegmentKind.AUTO_COMPACT_RESERVE -> R.string.context_segment_auto_reserve
        ContextSegmentKind.FREE_SPACE -> R.string.context_segment_free
    },
)

