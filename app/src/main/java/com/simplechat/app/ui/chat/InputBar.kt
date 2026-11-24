package com.simplechat.app.ui.chat

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.simplechat.app.data.InterfaceControl
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.layout
import androidx.compose.ui.util.lerp
import com.simplechat.app.R
import com.simplechat.app.ui.common.ContainerShape
import com.simplechat.app.ui.common.layeredShadow
import com.simplechat.app.ui.common.AppIcons
import com.simplechat.app.ui.common.AppShadow
import com.simplechat.app.data.TokenEstimate
import com.simplechat.app.data.Res
import com.simplechat.app.ui.common.FoldAnimMs
import com.simplechat.app.ui.common.IconCircleButton
import com.simplechat.app.ui.common.MotionEasing
import com.simplechat.app.ui.common.PillShape
import com.simplechat.app.ui.common.ProgressRing
import com.simplechat.app.ui.theme.LocalChatColors
import com.simplechat.app.ui.theme.floatingGlassBackground
import com.simplechat.app.ui.theme.floatingPanelSurface
import com.simplechat.app.ui.theme.GlassForeground
import kotlin.math.ceil

/**
 * 输入区。
 *
 * 第二行布局（对齐 DeepSeek，但做了本项目自己的扩展）：
 *
 * ```
 * [ ⚙ ] [ 深度思考 ⌄ ]  ………………  [ ⊕ ] [ ↑/■ ]
 *   对话设置   思考档位+模型            附件   发送/停止
 * ```
 *
 * 两个入口都会在输入栏下方展开面板（与选图面板同一机制），输入栏随之上移。
 */
@Composable
internal fun InputBar(
    fieldState: TextFieldState,
    expanded: Boolean,
    onToggleExpanded: (() -> Unit)?,
    thinkingEnabled: Boolean,
    thinkingPanelOpen: Boolean,
    /**
     * 智能搜索是否开启。
     *
     * ✦ 是「这一轮怎么生成」的唯一入口（思考档位与联网搜索都在面板里），
     * 所以两者**任一开启**都算激活 —— 否则开了搜索、星星还是黑的，用户会以为没生效。
     */
    webSearchEnabled: Boolean = false,
    onOpenThinkingPanel: () -> Unit,
    settingsPanelOpen: Boolean,
    onOpenSettingsPanel: () -> Unit,
    streaming: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    /** 附件入口是否显示。关掉是为了不给"点了没反应"的控件留位置。 */
    canAttach: Boolean = true,
    /** 点 ⊕：由调用方展开附件面板（图片 / 纯文本文件）。 */
    onAttachClick: () -> Unit,
    /** 附件面板是否展开 —— 决定 ⊕ 要不要转成 ✕。 */
    attachPanelOpen: Boolean = false,
    /** 已挂上的附件数量。>0 时即使没打字也允许发送。 */
    pendingCount: Int = 0,
    onTextFieldFocused: () -> Unit = {},
    /** 非空即处于「修改输入」态：顶部显示该标题 + 取消按钮。 */
    editingLabel: String? = null,
    onCancelEdit: () -> Unit = {},
    /** 下方有面板展开时，输入栏的投影淡出（两块合看成一体）。 */
    panelExpanded: Boolean = false,
    /** 上下文占用。圆环 + 长度，点开是统计面板。 */
    contextStats: ContextStats,
    statsPanelOpen: Boolean,
    onOpenStatsPanel: () -> Unit,
    holdToTalkEnabled: Boolean = false,
    voiceState: VoiceInputState = VoiceInputState(),
    voiceLevel: () -> Float = { 0f },
    voiceSourceLabel: String = "",
    voiceCancelling: Boolean = false,
    voiceTapMode: Boolean = false,
    onVoiceHoldStart: () -> Boolean = { false },
    onVoiceHoldFinish: (Boolean) -> Unit = {},
    onVoiceSlide: (Boolean) -> Unit = {},
    onVoiceTap: () -> Unit = {},
    modifier: Modifier = Modifier,
    imageMode: Boolean = false,
    sendAllowed: Boolean = true,
    imageControls: @Composable (androidx.compose.foundation.layout.RowScope.() -> Unit)? = null,
) {
    val shape = ContainerShape
    val text = fieldState.text
    val density = LocalDensity.current
    var textLayout by remember(fieldState) { mutableStateOf<TextLayoutResult?>(null) }
    val visualLines = textLayout?.lineCount ?: 0
    val showExpand = !voiceState.active &&
        (expanded || (text.isNotEmpty() && visualLines >= ExpandAtVisualLines))
    val expansion = animateFloatAsState(
        targetValue = if (expanded) 1f else 0f,
        animationSpec = tween(FoldAnimMs, easing = MotionEasing),
        label = "composerExpansion",
    )
    val compactMax = textLayout?.takeIf { it.lineCount >= 6 }?.let {
        with(density) { ceil(it.getLineBottom(5)).toDp().coerceIn(26.dp, 160.dp) }
    } ?: 160.dp
    val compactHeight = textLayout?.let {
        with(density) { it.size.height.toDp().coerceIn(26.dp, compactMax) }
    } ?: 26.dp
    val focusRequester = remember { FocusRequester() }
    var fieldFocused by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current

    /**
     * 「空框 + 有语音能力」才挂长按热区。
     *
     * 有字时长按要让位给**选词** —— 那是文本框自己的默认语义，抢掉它编辑会变得很难用。
     */
    val holdToTalkActive = holdToTalkEnabled && text.isEmpty() && !expanded && !fieldFocused

    val focusOnTap by rememberUpdatedState<() -> Unit> {
        if (!voiceState.active) {
            focusRequester.requestFocus()
            keyboard?.show()
        }
    }
    val startVoice by rememberUpdatedState(onVoiceHoldStart)
    val finishVoice by rememberUpdatedState(onVoiceHoldFinish)
    val slideVoice by rememberUpdatedState(onVoiceSlide)
    val composerBase = floatingPanelSurface(InterfaceControl.INPUT)
    val background by animateColorAsState(
        targetValue = when {
            voiceCancelling && voiceState.holding -> MaterialTheme.colorScheme.error
            voiceState.active -> MaterialTheme.colorScheme.primary
            else -> composerBase
        },
        animationSpec = tween(if (voiceState.active) 140 else FoldAnimMs, easing = MotionEasing),
        label = "composerColor",
    )
    // Let the solid voice color start receding before the dark text and controls reappear.
    val composerReveal by animateFloatAsState(
        targetValue = if (voiceState.active) 0f else 1f,
        animationSpec = if (voiceState.active) snap() else tween(
            durationMillis = FoldAnimMs - ComposerRevealDelayMs,
            delayMillis = ComposerRevealDelayMs,
            easing = MotionEasing,
        ),
        label = "composerReveal",
    )

    // 面板展开 → 投影淡出；收起 → 淡入。否则接缝处会同时压着两条阴影。
    val shadowElevation by animateDpAsState(
        targetValue = if (panelExpanded) 0.dp else AppShadow.PanelElevation,
        animationSpec = tween(durationMillis = 180),
        label = "inputBarShadow",
    )

    /*
     * 进入编辑态时自动聚焦并**拉起输入法**。
     *
     * 只调 requestFocus() 是不够的 —— 它只给焦点，系统输入法不会自己出来，
     * 表现为「点了消息、光标在闪、但键盘没弹」。必须显式 show()，
     * 且要等一帧让焦点真正落到输入框上，否则 show() 会被忽略。
     */
    LaunchedEffect(editingLabel) {
        if (editingLabel == null) return@LaunchedEffect
        runCatching {
            focusRequester.requestFocus()
            withFrameNanos { }
            keyboard?.show()
        }
    }

    GlassForeground(InterfaceControl.INPUT, background) {
        val colors = LocalChatColors.current
        Box(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    // 与菜单同一个投影族：先 shadow 再 clip，投影才不会被自家圆角裁掉。
                    // 双层（宽 + 贴边）—— 单层只有一种扩散，大面板的边缘会发虚。
                    .layeredShadow(
                        shape = ContainerShape,
                        elevation = shadowElevation,
                        ambient = AppShadow.PanelAmbient,
                        spot = AppShadow.PanelSpot,
                        contactElevation = shadowElevation / 4,
                        contactColor = AppShadow.PanelContactColor,
                        opacity = background.alpha,
                    )
                    .clip(shape)
                    // 语音态是实心主色 / 错误色，不参与玻璃
                    .then(
                        if (voiceState.active) Modifier.background(background)
                        else Modifier.floatingGlassBackground(colors.card, InterfaceControl.INPUT, background),
                    )
                    .border(
                        width = 1.dp,
                        color = if (voiceState.active) Color.Transparent else colors.outline.copy(
                            alpha = colors.outline.alpha * composerReveal,
                        ),
                        shape = shape,
                    )
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                // ── 「修改输入」标题行 ─────────────────────────
                if (editingLabel != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(editingLabel, modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.labelMedium, color = colors.placeholder)
                        IconCircleButton(
                            icon = AppIcons.Close,
                            contentDescription = stringResource(if (imageMode) R.string.image_exit_mode else R.string.input_cancel_edit),
                            onClick = onCancelEdit,
                            size = 26.dp,
                            iconSize = 14.dp,
                            tint = colors.processText,
                        )
                    }
                }

                Box(Modifier.fillMaxWidth()) {
                    if (voiceState.active) {
                        VoiceComposer(voiceState, voiceLevel, voiceCancelling, voiceTapMode, onVoiceTap)
                    } else {
                        val startLabel = stringResource(R.string.voice_tap_to_start)
                        BasicTextField(
                            state = fieldState,
                            onTextLayout = { textLayout = it() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .layout { measurable, constraints ->
                                    // Measure the viewport without recomposing the editor or toolbar each frame.
                                    val available = (constraints.maxHeight - 44.dp.roundToPx()).coerceAtLeast(0)
                                    val collapsedMax = compactMax.roundToPx().coerceAtMost(available)
                                    val collapsedMin = 26.dp.roundToPx().coerceAtMost(collapsedMax)
                                    val expandedHeight = maxOf(compactHeight.roundToPx(), available - 20.dp.roundToPx())
                                        .coerceAtMost(available)
                                    val fraction = expansion.value
                                    val placeable = measurable.measure(constraints.copy(
                                        minHeight = lerp(collapsedMin, expandedHeight, fraction),
                                        maxHeight = lerp(collapsedMax, expandedHeight, fraction),
                                    ))
                                    layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
                                }
                                .graphicsLayer { alpha = composerReveal }
                                .focusRequester(focusRequester)
                                .onFocusChanged {
                                    fieldFocused = it.isFocused
                                    if (it.isFocused) onTextFieldFocused()
                                }
                                .then(if (holdToTalkActive) Modifier.semantics {
                                    customActions = listOf(CustomAccessibilityAction("$startLabel · $voiceSourceLabel") {
                                        onVoiceTap()
                                        true
                                    })
                                } else Modifier),
                            textStyle = LocalTextStyle.current.merge(
                                MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            decorator = { inner ->
                                Box(Modifier.fillMaxWidth(), propagateMinConstraints = true) {
                                    if (text.isEmpty()) Text(
                                        text = stringResource(when {
                                            imageMode -> R.string.image_prompt_label
                                            holdToTalkActive -> R.string.input_hint_hold_to_talk
                                            else -> R.string.input_hint
                                        }),
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = colors.placeholder,
                                    )
                                    inner()
                                }
                            },
                        )
                    }

                    // Overlay above the text. This control never changes the wrapping width or
                    // takes keyboard focus away from the text field.
                    if (showExpand && onToggleExpanded != null) {
                        IconCircleButton(
                            icon = if (expanded) AppIcons.CollapseComposer else AppIcons.ExpandComposer,
                            contentDescription = stringResource(if (expanded) R.string.composer_collapse else R.string.composer_expand),
                            onClick = onToggleExpanded,
                            modifier = Modifier.align(Alignment.TopEnd)
                                .focusProperties { canFocus = false }
                                .graphicsLayer { alpha = composerReveal },
                            size = 28.dp,
                            iconSize = 16.dp,
                            background = colors.card.copy(alpha = 0.8f),
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
                        )
                    }

                    // Keep this touch target above both states so an empty-field hold never
                    // reaches the text field's selection or paste gesture detector.
                    if (holdToTalkActive && !voiceTapMode) {
                        Box(
                            Modifier.matchParentSize().holdToSpeak(
                                onTap = { focusOnTap() },
                                start = { startVoice() },
                                finish = { finishVoice(it) },
                                onSlide = { slideVoice(it) },
                            ),
                        )
                    }
                }

                if (voiceState.active) return@Column

                Spacer(Modifier.size(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth().graphicsLayer {
                        alpha = composerReveal
                        translationY = 4.dp.toPx() * (1f - composerReveal)
                    },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (imageMode) {
                        Row(
                            Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) { imageControls?.invoke(this) }
                    } else {
                        // 第二行全部按钮**同高**；纯图标一律正圆，只有「图标 + 箭头」用胶囊。
                        // 之前 ⚙ 是 padding 撑出来的椭圆，和 ⊕ / ↑ 的圆并排就很毛糙。

                        // ── 对话设置 ─────────────────────────────
                        // 与右侧思考入口同一套样式：灰图标 + 描边；展开时换品牌色底
                        IconCircleButton(
                            icon = AppIcons.Settings,
                            contentDescription = stringResource(R.string.input_settings),
                            onClick = onOpenSettingsPanel,
                            size = RowButtonSize,
                            iconSize = 18.dp,
                            background = if (settingsPanelOpen) colors.capsuleSelected else null,
                            tint = if (settingsPanelOpen) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                // 未激活也画**黑**（原来是灰的）—— 与 ⊕ / 发送键统一，对齐官方
                                MaterialTheme.colorScheme.onSurface
                            },
                            /*
                             * 描边**常驻**，激活时换成品牌色。
                             *
                             * 官方那套激活样式是「淡底 + 同色描边 + 同色内容」；
                             * 原来激活态把描边去掉只铺底，在并排的几个按钮里会显得"矮一层"。
                             */
                            outlined = true,
                            outlineColor = if (settingsPanelOpen) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                null
                            },
                        )

                        // ── 思考档位 + 模型 ───────────────────────
                        ThinkingToggle(
                            enabled = thinkingEnabled || webSearchEnabled,
                            expanded = thinkingPanelOpen,
                            onClick = onOpenThinkingPanel,
                        )
                        // ── 上下文占用 ────────────────────────────
                        ContextMeterPill(
                            stats = contextStats,
                            expanded = statsPanelOpen,
                            onClick = onOpenStatsPanel,
                        )

                    }

                    if (!imageMode) Spacer(Modifier.weight(1f))

                    /*
                     * 附件入口。图标是**圆圈加号**（⊕），与顶栏的「新建对话」区分开 ——
                     * 后者是气泡加号。同一个符号不要承担两种动作。
                     *
                     * 展开时整体转 45°：加号变成叉，"再点一下是收起"就不用另外说明。
                     * 转的是**整个按钮** —— 圆形是对称的，看起来就是里头的加号在转，
                     * 比"两帧之间换一个图标"顺得多。
                     */
                    if (canAttach) {
                        val spin by animateFloatAsState(
                            targetValue = if (attachPanelOpen) 45f else 0f,
                            animationSpec = tween(200, easing = MotionEasing),
                            label = "attachSpin",
                        )
                        IconCircleButton(
                            icon = AppIcons.PlusCircle,
                            contentDescription = if (attachPanelOpen) {
                                stringResource(R.string.input_attach_collapse)
                            } else {
                                stringResource(R.string.input_attach_add)
                            },
                            onClick = onAttachClick,
                            size = RowButtonSize,
                            iconSize = 24.dp,
                            modifier = Modifier.graphicsLayer { rotationZ = spin },
                        )
                    }

                    SendButton(
                        streaming = streaming,
                        enabled = streaming || (sendAllowed && (text.isNotBlank() || pendingCount > 0)),
                        imageMode = imageMode,
                        onClick = { if (streaming) onStop() else onSend() },
                    )
                }
            }
        }
    }
}

private const val ComposerRevealDelayMs = 40
private const val ExpandAtVisualLines = 4

/** 输入栏第二行的统一控件高度。 */
private val RowButtonSize = 32.dp

/**
 * 思考档位入口。
 *
 * 只留**图标 + 箭头**，不带文字 —— 输入栏第二行本来就窄，
 * 图标（Bubble/Brain）已足够表意，箭头单独承担"可展开"的提示。
 *
 * [enabled] 表示思考已开启；[expanded] 表示面板正在展开 ——
 * 两者独立，因为「面板打开」和「思考已开启」是两件事。
 */
@Composable
private fun ThinkingToggle(
    enabled: Boolean,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalChatColors.current
    // 未开启也画**黑**（原来是灰的），与 ⊕ / 发送键统一
    val content = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface

    Row(
        modifier = Modifier
            .height(RowButtonSize)
            .clip(PillShape)
            .background(if (enabled) colors.capsuleSelected else Color.Transparent)
            /*
             * 开启态**保留描边**，只换成品牌色 —— 官方那套激活样式是
             * 「淡底 + 同色描边 + 同色内容」。原来激活时把描边设成透明，
             * 并排看会比旁边几个"矮一层"。
             */
            .border(
                width = 1.dp,
                color = if (enabled) MaterialTheme.colorScheme.primary else colors.outline,
                shape = PillShape,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Icon(
            imageVector = AppIcons.Sparkles,
            contentDescription = stringResource(R.string.input_thinking),
            tint = content,
            modifier = Modifier.size(17.dp),
        )
        Icon(
            imageVector = AppIcons.ChevronDown,
            contentDescription = null,
            tint = content,
            // 展开时箭头翻转，给出明确的方向反馈
            modifier = Modifier
                .size(14.dp)
                .rotate(if (expanded) 180f else 0f),
        )
    }
}

/**
 * 智能搜索入口。
 *
 * 与 [ThinkingToggle] 同一套视觉（同高、同圆角、同激活样式），
 * 但**没有箭头**：它是个开关，不是面板入口 —— 点了就开/关，没有下一层。
 *
 * 开启态用「淡底 + 品牌色描边 + 品牌色图标」，与旁边的思考档位一致；
 * 关闭态用描边 + 黑色图标，与 ⊕ / 发送键一致。
 */
@Composable
private fun WebSearchToggle(enabled: Boolean, onClick: () -> Unit) {
    val colors = LocalChatColors.current
    val content = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
    val label = stringResource(R.string.web_search)

    Row(
        modifier = Modifier
            .height(RowButtonSize)
            .clip(PillShape)
            .background(if (enabled) colors.capsuleSelected else Color.Transparent)
            .border(
                width = 1.dp,
                color = if (enabled) MaterialTheme.colorScheme.primary else colors.outline,
                shape = PillShape,
            )
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = label
                role = Role.Button
                stateDescription = if (enabled) {
                    Res.get(R.string.web_search_on)
                } else {
                    Res.get(R.string.web_search_off)
                }
            }
            .padding(horizontal = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = AppIcons.Globe,
            contentDescription = null,
            tint = content,
            modifier = Modifier.size(17.dp),
        )
    }
}

/**
 * 上下文占用入口：圆环 + 长度（如 `12k`）。
 *
 * 颜色按占用分三档，让"该压缩了"在**不点开面板**的情况下也看得见：
 * 常规 / 接近阈值（≥70%）/ 逼近上限（≥90%）。
 *
 * 压缩生效时环上会出现一个品牌色的小标，提示"发出去的和本地看到的不一样"。
 */
@Composable
private fun ContextMeterPill(
    stats: ContextStats,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalChatColors.current
    val accent = when {
        stats.ratio >= 0.9f -> colors.danger
        stats.ratio >= 0.7f -> colors.warning
        else -> MaterialTheme.colorScheme.primary
    }
    val content = when {
        expanded -> MaterialTheme.colorScheme.primary
        // 接近阈值 / 逼近上限：数字跟着变色。这个信号只留在环上太容易被忽略
        stats.ratio >= 0.7f -> accent
        // 常规态也画**黑**（原来是灰的），与旁边几个按钮统一
        else -> MaterialTheme.colorScheme.onSurface
    }
    val contextUsageLabel = stringResource(R.string.stats_context_usage)

    Row(
        modifier = Modifier
            .height(RowButtonSize)
            .clip(PillShape)
            .background(if (expanded) colors.capsuleSelected else Color.Transparent)
            // 展开时描边换成品牌色（与 ⚙ / ✧ 同一套激活样式）
            .border(
                width = 1.dp,
                color = if (expanded) MaterialTheme.colorScheme.primary else colors.outline,
                shape = PillShape,
            )
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = contextUsageLabel
                role = Role.Button
            }
            .padding(horizontal = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        ProgressRing(
            progress = stats.ratio,
            color = accent,
            trackColor = colors.outline,
            size = 15.dp,
            strokeWidth = 2.dp,
        )
        Text(
            text = TokenEstimate.format(stats.usedTokens),
            style = MaterialTheme.typography.labelMedium,
            color = content,
        )
        if (stats.hasCompaction) {
            // 一个极小的小圆点：说明"上下文已被压缩过"
            Box(
                modifier = Modifier
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}

/**
 * 发送按钮。三态：
 * - 空闲且无内容：浅灰，不可点
 * - 有内容：品牌蓝实心，向上箭头
 * - 流式中：品牌蓝实心，停止方块
 */
@Composable
private fun SendButton(
    streaming: Boolean,
    imageMode: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalChatColors.current
    val background = if (enabled) MaterialTheme.colorScheme.primary else colors.fieldBackground
    val content = if (enabled) Color.White else colors.placeholder

    Box(
        modifier = Modifier
            .size(RowButtonSize)
            .clip(CircleShape)
            .background(background)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (streaming) AppIcons.Stop else AppIcons.ArrowUp,
            contentDescription = stringResource(when {
                streaming -> R.string.input_stop
                imageMode -> R.string.image_generate_action
                else -> R.string.input_send
            }),
            tint = content,
            modifier = Modifier.size(if (streaming) 16.dp else 19.dp),
        )
    }
}

/** 无背景的圆形图标按钮，48dp 触控目标。等价于 [IconCircleButton] 的大尺寸预设。 */
@Composable
fun IconAction(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    tint: Color = MaterialTheme.colorScheme.onBackground,
    modifier: Modifier = Modifier,
) {
    IconCircleButton(
        icon = icon,
        contentDescription = contentDescription,
        onClick = onClick,
        modifier = modifier,
        size = 48.dp,
        iconSize = 24.dp,
        tint = tint,
    )
}
