package com.simplechat.app.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import com.simplechat.app.R
import com.simplechat.app.ui.common.ContainerShape
import com.simplechat.app.data.TokenEstimate
import com.simplechat.app.db.CompactionEntity
import com.simplechat.app.net.ModelInfo
import com.simplechat.app.ui.common.AppIcons
import com.simplechat.app.ui.common.AppMenu
import com.simplechat.app.ui.common.AppMenuItem
import com.simplechat.app.ui.common.AppShadow
import com.simplechat.app.ui.common.AppSlider
import com.simplechat.app.ui.common.CapsuleButton
import com.simplechat.app.ui.common.PillShape
import com.simplechat.app.ui.common.SegmentedSwitch
import com.simplechat.app.ui.common.SoftShape
import com.simplechat.app.ui.theme.LocalChatColors
import kotlin.math.roundToInt

/** 输入栏下方的面板容器。输入栏会上移，和选图面板一致。 */
@Composable
fun InputPanel(
    visible: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalChatColors.current
    val shape = ContainerShape

    // 面板自己的投影也做淡入，和输入栏投影的淡出对得上
    val elevation by animateDpAsState(
        targetValue = if (visible) AppShadow.PanelElevation else 0.dp,
        animationSpec = tween(durationMillis = 220),
        label = "inputPanelShadow",
    )

    AnimatedVisibility(
        visible = visible,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .shadow(
                    elevation = elevation,
                    shape = shape,
                    clip = false,
                    ambientColor = AppShadow.PanelAmbient,
                    spotColor = AppShadow.PanelSpot,
                )
                .clip(shape)
                .background(colors.card)
                .border(1.dp, colors.outline, shape)
                .heightIn(max = 360.dp)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            content = content,
        )
    }
}

/** 面板内的小节标题。 */
@Composable
fun PanelSectionTitle(
    text: String,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
) {
    val colors = LocalChatColors.current
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = colors.processText,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
    }
}

@Composable
private fun PanelDivider(modifier: Modifier = Modifier) {
    val colors = LocalChatColors.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(colors.divider),
    )
}

// ══════════════════════════════════════════════════════════
//  面板一：思考档位 + 模型列表
// ══════════════════════════════════════════════════════════

/**
 * 思考档位与强度**合并为一个滑块**（3 档）：
 *
 * ```
 * 关闭  ────  high  ────  max
 * ```
 *
 * 滑到最左即关闭。这比「开关 + 强度」两个控件更省一次操作，
 * 也与服务端语义一致 —— `thinking.type` 与 `reasoning_effort` 本就是一件事。
 */
@Composable
fun ThinkingPanelContent(
    settings: ConversationSettings,
    onSettingsChange: (ConversationSettings) -> Unit,
    models: List<ModelInfo>,
    onRefreshModels: () -> Unit,
    refreshing: Boolean = false,
    hint: String? = null,
    modifier: Modifier = Modifier,
) {
    val colors = LocalChatColors.current
    val levels = ReasoningLevel.sliderOrder
    val supportsThinking = settings.model.supportsThinking

    Column(modifier = modifier.fillMaxWidth()) {

        // ── 思考档位 ──────────────────────────────────────
        PanelSectionTitle(
            text = stringResource(R.string.input_thinking),
            trailing = {
                Text(
                    text = if (supportsThinking) settings.reasoningLevel.label else stringResource(R.string.input_model_unsupported),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (supportsThinking) MaterialTheme.colorScheme.primary else colors.placeholder,
                )
            },
        )

        Spacer(Modifier.size(4.dp))

        // 与「字体大小」页用**同一个** AppSlider：正圆指示器、按压缩放、点击可跳档
        AppSlider(
            value = ReasoningLevel.sliderPositionOf(settings.reasoningLevel).toFloat(),
            onValueChange = { raw ->
                onSettingsChange(
                    settings.copy(
                        reasoningLevel = ReasoningLevel.fromSliderPosition(raw.roundToInt()),
                    ),
                )
            },
            valueRange = 0f..levels.lastIndex.toFloat(),
            intervals = (levels.size - 1).coerceAtLeast(0),
            enabled = supportsThinking,
            // 文案交给滑块自己排，中心才会和刻度严格对齐
            tickLabels = levels.map { it.label },
            selectedTick = ReasoningLevel.sliderPositionOf(settings.reasoningLevel),
        )

        Spacer(Modifier.size(8.dp))

        Text(
            text = if (supportsThinking) {
                stringResource(R.string.input_thinking_hint)
            } else {
                stringResource(R.string.input_thinking_unsupported)
            },
            style = MaterialTheme.typography.labelSmall,
            color = colors.placeholder,
        )

        Spacer(Modifier.size(16.dp))
        PanelDivider()
        Spacer(Modifier.size(16.dp))

        // ── 模型列表 ──────────────────────────────────────
        PanelSectionTitle(
            text = stringResource(R.string.label_model),
            trailing = {
                CapsuleButton(
                    text = if (refreshing) stringResource(R.string.models_refreshing) else stringResource(R.string.models_refresh),
                    leadingIcon = AppIcons.Refresh,
                    enabled = !refreshing,
                    onClick = onRefreshModels,
                )
            },
        )

        hint?.let {
            Spacer(Modifier.size(6.dp))
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = colors.placeholder,
            )
        }

        Spacer(Modifier.size(8.dp))

        if (models.isEmpty()) {
            Text(
                text = stringResource(R.string.models_empty),
                style = MaterialTheme.typography.labelSmall,
                color = colors.placeholder,
            )
        } else {
            models.forEach { model ->
                ModelRow(
                    model = model,
                    selected = model.id == settings.model.id,
                    onClick = { onSettingsChange(settings.copy(model = model)) },
                )
            }
        }
    }
}

@Composable
private fun ModelRow(
    model: ModelInfo,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalChatColors.current
    val shape = SoftShape
    val tagVision = stringResource(R.string.model_tag_vision)
    val tagThinking = stringResource(R.string.model_tag_thinking)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .then(
                if (selected) Modifier.background(colors.capsuleSelected) else Modifier,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = model.displayName,
                style = MaterialTheme.typography.titleSmall,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = buildString {
                    append(model.id)
                    if (model.supportsVision) append(" · $tagVision")
                    if (model.supportsThinking) append(" · $tagThinking")
                    append(" · ${model.contextWindow / 1000}K")
                },
                style = MaterialTheme.typography.labelSmall,
                color = colors.placeholder,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (selected) {
            Icon(
                imageVector = AppIcons.Check,
                contentDescription = stringResource(R.string.model_current),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

// ══════════════════════════════════════════════════════════
//  面板二：对话统计 + 上下文压缩
// ══════════════════════════════════════════════════════════

/**
 * 统计面板。
 *
 * 所有 token 数都是**本地估算**（`TokenEstimate`），面板里会写明 ——
 * 让用户以为这是精确值，比给个粗略值更糟。
 */
@Composable
fun ContextPanelContent(
    stats: ContextStats,
    compaction: CompactionEntity?,
    compacting: Boolean,
    compactPreview: String,
    onCompact: () -> Unit,
    onCancelCompact: () -> Unit,
    onClearCompaction: () -> Unit,
    onUpdateSummary: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalChatColors.current

    Column(modifier = modifier.fillMaxWidth()) {

        // ── 占用 ──────────────────────────────────────────
        PanelSectionTitle(
            text = stringResource(R.string.stats_context_usage),
            trailing = {
                Text(
                    text = "${stats.percent}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = when {
                        stats.ratio >= 0.9f -> colors.danger
                        stats.ratio >= 0.7f -> colors.warning
                        else -> MaterialTheme.colorScheme.primary
                    },
                )
            },
        )

        Spacer(Modifier.size(8.dp))

        UsageBar(ratio = stats.ratio)

        Spacer(Modifier.size(8.dp))

        Text(
            text = stringResource(
                R.string.stats_tokens_estimate,
                TokenEstimate.format(stats.usedTokens),
                TokenEstimate.format(stats.windowTokens),
            ),
            style = MaterialTheme.typography.labelSmall,
            color = colors.placeholder,
        )

        if (stats.compactedTokenSaving > 0) {
            Spacer(Modifier.size(6.dp))
            Text(
                text = stringResource(
                    R.string.stats_compacted_saving,
                    TokenEstimate.format(stats.compactedTokenSaving),
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        Spacer(Modifier.size(16.dp))
        PanelDivider()
        Spacer(Modifier.size(16.dp))

        // ── 统计 ──────────────────────────────────────────
        PanelSectionTitle(text = stringResource(R.string.stats_title))

        Spacer(Modifier.size(10.dp))

        StatRow(stringResource(R.string.stats_total_tokens), "${TokenEstimate.format(stats.totalTokens)} tokens")
        StatRow(stringResource(R.string.stats_message_count), stringResource(R.string.stats_messages, stats.messageCount))
        StatRow(
            label = stringResource(R.string.stats_avg_ttft),
            value = stats.avgTtftMs?.let { "$it ms" } ?: "—",
        )
        StatRow(
            label = stringResource(R.string.stats_avg_tps),
            value = stats.avgTps?.let { "${it.roundToInt()} tok/s" } ?: "—",
        )
        StatRow(
            label = stringResource(R.string.stats_total_thinking),
            value = stats.totalThinkingSeconds?.let { stringResource(R.string.stats_seconds, it) } ?: "—",
        )

        Spacer(Modifier.size(16.dp))
        PanelDivider()
        Spacer(Modifier.size(16.dp))

        // ── 压缩 ──────────────────────────────────────────
        CompactionSection(
            compaction = compaction,
            compacting = compacting,
            compactPreview = compactPreview,
            onCompact = onCompact,
            onCancelCompact = onCancelCompact,
            onClearCompaction = onClearCompaction,
            onUpdateSummary = onUpdateSummary,
        )
    }
}

/** 线性占用条。只读，不可拖。 */
@Composable
private fun UsageBar(ratio: Float) {
    val colors = LocalChatColors.current
    val accent = when {
        ratio >= 0.9f -> colors.danger
        ratio >= 0.7f -> colors.warning
        else -> MaterialTheme.colorScheme.primary
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(PillShape)
            .background(colors.outline),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(ratio.coerceIn(0f, 1f))
                .height(6.dp)
                .clip(PillShape)
                .background(accent),
        )
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    val colors = LocalChatColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = colors.placeholder,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun CompactionSection(
    compaction: CompactionEntity?,
    compacting: Boolean,
    compactPreview: String,
    onCompact: () -> Unit,
    onCancelCompact: () -> Unit,
    onClearCompaction: () -> Unit,
    onUpdateSummary: (String) -> Unit,
) {
    val colors = LocalChatColors.current
    var editing by remember { mutableStateOf(false) }
    var draft by remember(compaction?.id) { mutableStateOf(compaction?.summary.orEmpty()) }

    PanelSectionTitle(text = stringResource(R.string.compact_title))

    Spacer(Modifier.size(6.dp))

    Text(
        text = stringResource(R.string.compact_note),
        style = MaterialTheme.typography.labelSmall,
        color = colors.placeholder,
    )

    Spacer(Modifier.size(12.dp))

    when {
        // ── 正在压缩 ─────────────────────────────────────
        compacting -> {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.compact_running),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                CapsuleButton(text = stringResource(R.string.action_cancel), onClick = onCancelCompact)
            }
            if (compactPreview.isNotBlank()) {
                Spacer(Modifier.size(8.dp))
                ReadOnlyBox(text = compactPreview, maxHeight = 160.dp)
            }
        }

        // ── 已有摘要 ─────────────────────────────────────
        compaction != null -> {
            Text(
                text = stringResource(R.string.compact_summarized),
                style = MaterialTheme.typography.labelSmall,
                color = colors.placeholder,
            )
            Spacer(Modifier.size(8.dp))

            if (editing) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(SoftShape)
                        .background(colors.fieldBackground)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    BasicTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 120.dp, max = 260.dp),
                        textStyle = MaterialTheme.typography.bodySmall.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    )
                }
                Spacer(Modifier.size(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End,
                ) {
                    CapsuleButton(
                        text = stringResource(R.string.action_cancel),
                        onClick = {
                            draft = compaction.summary
                            editing = false
                        },
                    )
                    Spacer(Modifier.size(8.dp))
                    CapsuleButton(
                        text = stringResource(R.string.action_save),
                        selected = true,
                        onClick = {
                            onUpdateSummary(draft)
                            editing = false
                        },
                    )
                }
            } else {
                ReadOnlyBox(text = compaction.summary, maxHeight = 200.dp)
                Spacer(Modifier.size(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CapsuleButton(
                        text = stringResource(R.string.action_edit),
                        leadingIcon = AppIcons.Edit,
                        onClick = {
                            draft = compaction.summary
                            editing = true
                        },
                    )
                    CapsuleButton(
                        text = stringResource(R.string.compact_redo),
                        leadingIcon = AppIcons.Refresh,
                        onClick = onCompact,
                    )
                    CapsuleButton(
                        text = stringResource(R.string.compact_discard),
                        leadingIcon = AppIcons.Close,
                        onClick = onClearCompaction,
                    )
                }
            }
        }

        // ── 尚未压缩 ─────────────────────────────────────
        else -> {
            CapsuleButton(
                text = stringResource(R.string.compact_start),
                leadingIcon = AppIcons.Collapse,
                /*
                 * ⚠️ 这里**不要** `selected = true`。
                 *
                 * `selected` 的语义是"这个选项当前是开着的"，而这个分支的前提恰恰是
                 * **还没压缩** —— 顶着一个选中态等于告诉用户"已经压过了"。
                 * 之前它的底色是淡蓝、不扎眼；换成别的主题色之后一眼就是错的。
                 *
                 * 这一屏只有它一个按钮、下面还跟着说明，本来就是视觉重心，
                 * 不需要再借选中态来强调。
                 */
                onClick = onCompact,
            )
            Spacer(Modifier.size(8.dp))
            Text(
                text = stringResource(R.string.compact_desc),
                style = MaterialTheme.typography.labelSmall,
                color = colors.placeholder,
            )
        }
    }
}

@Composable
private fun ReadOnlyBox(text: String, maxHeight: androidx.compose.ui.unit.Dp) {
    val colors = LocalChatColors.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(SoftShape)
            .background(colors.fieldBackground)
            .heightIn(max = maxHeight)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = colors.processText,
        )
    }
}

// ══════════════════════════════════════════════════════════
//  面板四：对话设置
// ══════════════════════════════════════════════════════════

/**
 * 对话设置面板：对话模式 / 系统提示词 / 采样参数。
 *
 * 参数区严格按服务端真实行为置灰（设计文档 §5.4）：
 * `temperature` 思考模式下**静默失效**，`top_p` 反而只在思考模式下生效。
 * 这两条不说明白，用户会以为参数生效了其实没有。
 */
/**
 * 对话名称输入框。
 *
 * ### 为什么要自己存一份草稿
 *
 * 不能直接把库里的标题当输入值：那个值是**异步观察**回来的
 * （抽屉改名、AI 起名都会让 Flow 重发），而流式期间会话行还会被 checkpoint
 * 反复更新 —— 直接绑库，打字打到一半就可能被旧值盖回去。
 *
 * 所以草稿留在本地、落库走防抖；顶栏与抽屉由库那边统一同步。
 *
 * [conversationId] 只当 `remember` 的 key：换会话要换一份草稿。「新对话」时是 `null`，
 * 那一份草稿跟它的待用标题一样，都挂在还没建的那个会话名下。
 */
@Composable
private fun NameField(
    conversationId: String?,
    title: String,
    onTitleChange: (String) -> Unit,
) {
    val colors = LocalChatColors.current
    var draft by remember(conversationId) { mutableStateOf(title) }
    var focused by remember(conversationId) { mutableStateOf(false) }

    /*
     * 外部改名（抽屉 / AI 起名）时跟上，但**正在输入时不打断**。
     *
     * 刻意只把 `title` 当 key、不把 `focused` 一起放进去：失焦那一瞬间库里
     * 往往还是旧值（防抖刚落库），那时重跑会把用户刚打的字盖回去。
     */
    LaunchedEffect(title) {
        if (!focused) draft = title
    }

    // 停手 400ms 落库 —— 与「会话补充提示词」同一档。理由也一样：
    // 逐字写库会让抽屉里的排序和标题一路乱跳
    LaunchedEffect(draft) {
        if (draft == title) return@LaunchedEffect
        delay(400)
        onTitleChange(draft)
    }

    PanelSectionTitle(text = stringResource(R.string.input_conversation_name))
    Spacer(Modifier.size(8.dp))
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(SoftShape)
            .background(colors.fieldBackground)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        BasicTextField(
            value = draft,
            onValueChange = { draft = it },
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { focused = it.isFocused },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                color = MaterialTheme.colorScheme.onSurface,
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            decorationBox = { inner ->
                Box {
                    if (draft.isEmpty()) {
                        Text(
                            text = stringResource(R.string.input_conversation_name_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.placeholder,
                        )
                    }
                    inner()
                }
            },
        )
    }
}

@Composable
fun ConversationSettingsPanelContent(
    settings: ConversationSettings,
    onSettingsChange: (ConversationSettings) -> Unit,
    presets: List<Preset>,
    /** 当前会话 id；未落库的新对话为 null。 */
    conversationId: String?,
    /** 当前标题。**从库来**（见 `ChatViewModel.observeTitle`），不是本地副本。 */
    title: String,
    /** 改名回调；未落库的新对话传 null（还没有会话可改）。 */
    onTitleChange: ((String) -> Unit)?,
    modifier: Modifier = Modifier,
    /** 有会话可导出时为非空；未落库的新对话传 null。 */
    onExportMarkdown: (() -> Unit)? = null,
    onExportJson: (() -> Unit)? = null,
    exportHint: String? = null,
) {
    val colors = LocalChatColors.current
    var promptPreviewExpanded by remember { mutableStateOf(false) }
    var exportMenuOpen by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth()) {

        /*
         * ── ⓪ 对话名称 ────────────────────────────────────
         *
         * 放在**最上面**：它是这个面板里唯一"改完立刻在别处看得见"的项
         * （顶栏 + 抽屉），而下面的系统提示词、预设都只影响下一轮请求。
         *
         * 到这里为止，标题一共有**四种**确定方式：抽屉里重命名、AI 用 flash
         * 档起名、这个框、以及**「新对话」的待用标题**（发出第一条消息之前
         * 就起好的名，见 §35）。前三者都只写库、界面统一由 `observeTitle` 回流；
         * 待用标题是例外 —— 那时库里还没有行，内存里这份就是唯一的真相。
         */
        if (onTitleChange != null) {
            NameField(
                conversationId = conversationId,
                title = title,
                onTitleChange = onTitleChange,
            )
            Spacer(Modifier.size(12.dp))
        }

        /*
         * 对话模式切换**暂时撤下**：扮演模式是远期功能，现在把两个选项
         * 并列摆出来，等于让用户在两件当前不等价的事情之间做选择。
         * 默认创作模式，`ConversationMode` 与 `SegmentedSwitch` 都保留给以后。
         */

        // ── ① 系统提示词 ──────────────────────────────────
        PanelSectionTitle(
            text = stringResource(R.string.input_system_prompt),
            trailing = {
                Text(
                    text = stringResource(
                        R.string.input_preset_count,
                        settings.enabledPresetIds.size,
                        presets.size,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.placeholder,
                )
            },
        )

        Spacer(Modifier.size(8.dp))

        // 预设：可多选、按序拼接（扮演模式下每张预设即一个角色）
        presets.forEach { preset ->
            val enabled = preset.id in settings.enabledPresetIds
            val shape = SoftShape
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .then(if (enabled) Modifier.background(colors.capsuleSelected) else Modifier)
                    .clickable {
                        val next = if (enabled) {
                            settings.enabledPresetIds - preset.id
                        } else {
                            settings.enabledPresetIds + preset.id
                        }
                        onSettingsChange(settings.copy(enabledPresetIds = next))
                    }
                    .padding(horizontal = 10.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = if (enabled) AppIcons.CircleCheck else AppIcons.Circle,
                    contentDescription = null,
                    tint = if (enabled) MaterialTheme.colorScheme.primary else colors.placeholder,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.size(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = preset.name,
                        style = MaterialTheme.typography.titleSmall,
                        color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = preset.content,
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.placeholder,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        Spacer(Modifier.size(12.dp))

        PanelSectionTitle(text = stringResource(R.string.input_extra_prompt))
        Spacer(Modifier.size(8.dp))

        val fieldShape = SoftShape
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(fieldShape)
                .background(colors.fieldBackground)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            BasicTextField(
                value = settings.systemPrompt,
                onValueChange = { onSettingsChange(settings.copy(systemPrompt = it)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp, max = 140.dp),
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                decorationBox = { inner ->
                    Box {
                        if (settings.systemPrompt.isEmpty()) {
                            Text(
                                text = stringResource(R.string.input_extra_prompt_hint),
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.placeholder,
                            )
                        }
                        inner()
                    }
                },
            )
        }

        // 最终提示词预览：创作工具最怕黑盒，必须能一眼看到实际发出去的内容
        Spacer(Modifier.size(10.dp))
        val composed = settings.composedSystemPrompt(presets)
        Row(
            modifier = Modifier
                .clip(PillShape)
                .clickable { promptPreviewExpanded = !promptPreviewExpanded }
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = if (composed.isBlank()) {
                    stringResource(R.string.input_prompt_empty)
                } else {
                    stringResource(R.string.input_prompt_preview)
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Icon(
                imageVector = if (promptPreviewExpanded) AppIcons.ChevronUp else AppIcons.ChevronDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(14.dp),
            )
        }

        AnimatedVisibility(visible = promptPreviewExpanded) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(SoftShape)
                    .background(colors.fieldBackground)
                    .padding(12.dp),
            ) {
                Text(
                    text = composed.ifBlank { stringResource(R.string.input_prompt_blank) },
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.processText,
                )
            }
        }

        Spacer(Modifier.size(16.dp))
        PanelDivider()
        Spacer(Modifier.size(16.dp))

        // ── ③ 采样参数 ────────────────────────────────────
        PanelSectionTitle(text = stringResource(R.string.input_sampling))

        Spacer(Modifier.size(8.dp))

        ParamSlider(
            label = stringResource(R.string.input_param_temperature),
            valueText = formatFloat(settings.temperature),
            value = settings.temperature,
            range = 0f..2f,
            enabled = !settings.thinkingEnabled,
            disabledHint = stringResource(R.string.input_param_temp_disabled),
            onValueChange = { onSettingsChange(settings.copy(temperature = it)) },
        )

        ParamSlider(
            label = "top_p",
            valueText = formatFloat(settings.topP),
            value = settings.topP,
            range = 0.95f..1f,
            enabled = settings.thinkingEnabled,
            disabledHint = stringResource(R.string.input_param_top_p_disabled),
            onValueChange = { onSettingsChange(settings.copy(topP = it)) },
        )

        // ── 导出当前对话 ──────────────────────────────────
        Spacer(Modifier.size(16.dp))
        PanelDivider()
        Spacer(Modifier.size(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.input_export_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = if (onExportMarkdown != null) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        colors.placeholder
                    },
                )
                Text(
                    text = exportHint ?: stringResource(R.string.input_export_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.placeholder,
                )
            }
            if (onExportMarkdown != null) {
                // 两种导出服务两种用途，用菜单分开（见仓库里 exportConversationMarkdown 的注释）
                Box {
                    CapsuleButton(
                        text = stringResource(R.string.input_export),
                        leadingIcon = AppIcons.Download,
                        onClick = { exportMenuOpen = true },
                    )
                    AppMenu(
                        expanded = exportMenuOpen,
                        onDismissRequest = { exportMenuOpen = false },
                    ) {
                        AppMenuItem(stringResource(R.string.input_export_markdown), AppIcons.Feather) {
                            exportMenuOpen = false
                            onExportMarkdown()
                        }
                        AppMenuItem(stringResource(R.string.input_export_json), AppIcons.Database) {
                            exportMenuOpen = false
                            onExportJson?.invoke()
                        }
                    }
                }
            }
        }
    }
}

/** 带条件说明的参数滑块。禁用时会明确写出原因，而不是只置灰。 */
@Composable
private fun ParamSlider(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    enabled: Boolean,
    disabledHint: String,
    onValueChange: (Float) -> Unit,
) {
    val colors = LocalChatColors.current

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else colors.placeholder,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = valueText,
                style = MaterialTheme.typography.labelMedium,
                color = if (enabled) MaterialTheme.colorScheme.primary else colors.placeholder,
            )
        }

        AppSlider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onValueChange,
            valueRange = range,
            enabled = enabled,
        )

        if (!enabled) {
            Text(
                text = disabledHint,
                style = MaterialTheme.typography.labelSmall,
                color = colors.placeholder,
            )
            Spacer(Modifier.size(8.dp))
        }
    }
}

private fun formatFloat(value: Float): String =
    ((value * 100f).roundToInt() / 100f).toString()
