package com.simplechat.app.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.simplechat.app.R
import com.simplechat.app.db.ConversationEntity
import com.simplechat.app.db.ConversationSearchHit
import com.simplechat.app.ui.common.AppIcons
import com.simplechat.app.ui.common.FieldThickness
import com.simplechat.app.ui.common.PillShape
import com.simplechat.app.ui.theme.LocalChatColors

/**
 * 全屏搜索页。
 *
 * 从抽屉的搜索框进入 —— 抽屉是「浏览」，搜索是「查找」，
 * 全屏让结果有足够空间，也避免在窄抽屉里挤着看。
 */
@Composable
fun ConversationSearchPage(
    hits: List<ConversationSearchHit>,
    currentConversationId: String?,
    query: String,
    onQueryChange: (String) -> Unit,
    onBack: () -> Unit,
    onClick: (ConversationEntity) -> Unit,
    onRename: (ConversationEntity) -> Unit,
    onTogglePin: (ConversationEntity) -> Unit,
    onDelete: (ConversationEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalChatColors.current
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.card),
    ) {
        Spacer(Modifier.statusBarsPadding())

        // ── 顶栏：返回 + 搜索输入 ─────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .clickable {
                        keyboard?.hide()
                        onBack()
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = AppIcons.ChevronLeft,
                    contentDescription = stringResource(R.string.action_back),
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(20.dp),
                )
            }

            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(PillShape)
                    .background(colors.fieldBackground)
                    .padding(horizontal = 14.dp, vertical = FieldThickness),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = AppIcons.Search,
                    contentDescription = null,
                    tint = colors.placeholder,
                    modifier = Modifier.size(17.dp),
                )
                Box(modifier = Modifier.weight(1f)) {
                    if (query.isEmpty()) {
                        Text(
                            text = stringResource(R.string.search_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.placeholder,
                        )
                    }
                    BasicTextField(
                        value = query,
                        onValueChange = onQueryChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        singleLine = true,
                    )
                }
                if (query.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .size(18.dp)
                            .clip(CircleShape)
                            .clickable { onQueryChange("") },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = AppIcons.Close,
                            contentDescription = stringResource(R.string.action_clear),
                            tint = colors.placeholder,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            }
        }

        // ── 结果 ─────────────────────────────────────────
        if (hits.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (query.isBlank()) {
                        stringResource(R.string.search_prompt)
                    } else {
                        stringResource(R.string.search_no_result)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.placeholder,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(top = 4.dp, bottom = 12.dp),
            ) {
                items(items = hits, key = { it.conversation.id }) { hit ->
                    val conversation = hit.conversation
                    ConversationRow(
                        conversation = conversation,
                        current = conversation.id == currentConversationId,
                        selectionMode = false,
                        selected = false,
                        onClick = {
                            keyboard?.hide()
                            onClick(conversation)
                        },
                        onEnterSelection = {},
                        onRename = { onRename(conversation) },
                        onTogglePin = { onTogglePin(conversation) },
                        onDelete = { onDelete(conversation) },
                        highlightQuery = query,
                        // 命中正文时补一行片段，能一眼看出"是在哪句话里命中的"
                        snippet = snippetForDisplay(hit.snippet, query),
                        showMultiSelect = false,
                    )
                }
            }
        }

        Spacer(Modifier.navigationBarsPadding())
    }
}
