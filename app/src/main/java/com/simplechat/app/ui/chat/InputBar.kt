package com.simplechat.app.ui.chat

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.simplechat.app.R
import com.simplechat.app.ui.common.ContainerShape
import com.simplechat.app.ui.common.AppIcons
import com.simplechat.app.ui.common.AppShadow
import com.simplechat.app.ui.common.ContainerShape
import com.simplechat.app.data.TokenEstimate
import com.simplechat.app.ui.common.IconCircleButton
import com.simplechat.app.ui.common.MotionEasing
import com.simplechat.app.ui.common.PillShape
import com.simplechat.app.ui.common.ProgressRing
import com.simplechat.app.ui.theme.LocalChatColors

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
fun InputBar(
    text: String,
    onTextChange: (String) -> Unit,
    thinkingEnabled: Boolean,
    thinkingPanelOpen: Boolean,
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
    modifier: Modifier = Modifier,
) {
    val colors = LocalChatColors.current
    val shape = ContainerShape
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

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

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // 与菜单同一个投影族：先 shadow 再 clip，投影才不会被自家圆角裁掉
                .shadow(
                    elevation = shadowElevation,
                    shape = ContainerShape,
                    clip = false,
                    ambientColor = AppShadow.PanelAmbient,
                    spotColor = AppShadow.PanelSpot,
                )
                .clip(shape)
                .background(colors.card)
                .border(1.dp, colors.outline, shape)
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
                    Text(
                        text = editingLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.placeholder,
                        modifier = Modifier.weight(1f),
                    )
                    IconCircleButton(
                        icon = AppIcons.Close,
                        contentDescription = stringResource(R.string.input_cancel_edit),
                        onClick = onCancelEdit,
                        size = 26.dp,
                        iconSize = 14.dp,
                        tint = colors.processText,
                    )
                }
            }

            /*
             * 光标：**外部换文本**（恢复草稿 / 切会话）时放到末尾 —— 那是"接着往下写"；
             * 用户自己打字时光标不动。
             *
             * `BasicTextField` 的 `String` 重载不保证换文本后光标落在哪，这里自己钉住。
             */
            var fieldValue by remember { mutableStateOf(TextFieldValue(text = text)) }
            LaunchedEffect(text) {
                if (fieldValue.text != text) {
                    fieldValue = TextFieldValue(text = text, selection = TextRange(text.length))
                }
            }
            BasicTextField(
                value = fieldValue,
                onValueChange = {
                    fieldValue = it
                    if (it.text != text) onTextChange(it.text)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 26.dp, max = 160.dp)
                    .focusRequester(focusRequester)
                    // 用户点回输入框时收起面板，避免键盘与面板同时占屏
                    .onFocusChanged { if (it.isFocused) onTextFieldFocused() },
                textStyle = LocalTextStyle.current.merge(
                    MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                maxLines = 6,
                decorationBox = { inner ->
                    Box {
                        if (text.isEmpty()) {
                            Text(
                                text = stringResource(R.string.input_hint),
                                style = MaterialTheme.typography.bodyLarge,
                                color = colors.placeholder,
                            )
                        }
                        inner()
                    }
                },
            )

            Spacer(Modifier.size(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
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
                    enabled = thinkingEnabled,
                    expanded = thinkingPanelOpen,
                    onClick = onOpenThinkingPanel,
                )

                // ── 上下文占用 ────────────────────────────
                ContextMeterPill(
                    stats = contextStats,
                    expanded = statsPanelOpen,
                    onClick = onOpenStatsPanel,
                )

                Spacer(Modifier.weight(1f))

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
                    enabled = streaming || text.isNotBlank() || pendingCount > 0,
                    onClick = { if (streaming) onStop() else onSend() },
                )
            }
        }
    }
}

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
            contentDescription = if (streaming) stringResource(R.string.input_stop) else stringResource(R.string.input_send),
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
