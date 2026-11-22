package com.simplechat.app.ui.theme

import androidx.compose.ui.graphics.Color

// ── 品牌色 ────────────────────────────────────────────────
/** DeepSeek 蓝，浅色模式主色。 */
val BrandBlue = Color(0xFF4D6BFE)

/** 深色模式下略微提亮的品牌蓝，保证对比度。 */
val BrandBlueDark = Color(0xFF6B84FF)

// ── 主题色候选（设置页「个性化 → 主题色」）──────────────────
/*
 * 每个色相两档：浅色一档、深色一档。
 *
 * 深色那一档**不是**把浅色随手调亮 —— 同一个色相在近黑底上要够亮才顶得住
 * 对比度（原来的 BrandBlue → BrandBlueDark 就是这么来的，这里沿用同一套处理）。
 *
 * 蓝色两个档刻意就取原来的品牌色：**默认观感一个像素都不变**。
 */
val AccentGreenLight = Color(0xFF12A150)
val AccentGreenDark = Color(0xFF3DBF74)
val AccentRedLight = Color(0xFFB01E28)
val AccentRedDark = Color(0xFFE05C63)
val AccentPinkLight = Color(0xFFE35183)
val AccentPinkDark = Color(0xFFFF77A9)
val AccentPurpleLight = Color(0xFF7C4DFF)
val AccentPurpleDark = Color(0xFF9E7BFF)
val AccentBrownLight = Color(0xFF9A6B4F)
val AccentBrownDark = Color(0xFFB98A6B)
val AccentGrayLight = Color(0xFF6B7280)
val AccentGrayDark = Color(0xFF9AA1AC)

// ── 用户气泡 ──────────────────────────────────────────────
val UserBubbleLight = Color(0xFFDCE6FF)
val UserBubbleDark = Color(0xFF2A3556)

// ── 前景色（docs/32 §32.4.5）──────────────────────────────
// 文字、图标、线条的层级一律用「正文色 × alpha」，不写死灰色：
// 不透明灰只对纯白 / 近黑底成立，底下换成灰色或壁纸就是灰字压灰底、看不见。
// alpha 按原灰色值反推，纯色底上观感不变（次级文字有意加深）。
val TextPrimaryLight = Color(0xFF1A1A1A)
val TextPrimaryDark = Color(0xFFE8E8EA)

// ── 过程面板（思考区）────────────────────────────────────
val ProcessTextLight = TextPrimaryLight.copy(alpha = 0.58f)   // 原 #8A8F99
val ProcessTextDark = TextPrimaryDark.copy(alpha = 0.66f)     // 原 #9BA1AC
val ProcessBarLight = TextPrimaryLight.copy(alpha = 0.16f)    // 原 #D8DADE
val ProcessBarDark = TextPrimaryDark.copy(alpha = 0.21f)      // 原 #3A3D42

// ── 文本 ─────────────────────────────────────────────────
val TextSecondaryLight = TextPrimaryLight.copy(alpha = 0.56f) // 原 #9AA0A6（有意加深到 ~4.5:1）
val TextSecondaryDark = TextPrimaryDark.copy(alpha = 0.60f)   // 原 #8B9099
val PlaceholderLight = TextPrimaryLight.copy(alpha = 0.38f)   // 原 #B8BCC4
val PlaceholderDark = TextPrimaryDark.copy(alpha = 0.45f)     // 原 #6B7079

// ── 表面 ─────────────────────────────────────────────────
val PageBgLight = Color(0xFFFFFFFF)
val PageBgDark = Color(0xFF0F0F10)
val SettingsBgLight = Color(0xFFF5F6F8)
val SettingsBgDark = Color(0xFF151517)
val CardLight = Color(0xFFFFFFFF)
val CardDark = Color(0xFF1A1A1C)
val OutlineLight = TextPrimaryLight.copy(alpha = 0.09f)       // 原 #EAEBEF
val OutlineDark = TextPrimaryDark.copy(alpha = 0.15f)         // 原 #2E2F33
val DividerLight = TextPrimaryLight.copy(alpha = 0.06f)       // 原 #F0F0F2
val DividerDark = TextPrimaryDark.copy(alpha = 0.11f)         // 原 #26272A

// ── 浮层（长按菜单等）────────────────────────────────────
/**
 * 菜单浮层比卡片再"抬高"一档：
 * 深色下必须比 CardDark 亮，否则菜单和背景糊在一起（见参考图）。
 */
val MenuBgLight = Color(0xFFFFFFFF)
val MenuBgDark = Color(0xFF2B2C30)

// ── 功能色 ───────────────────────────────────────────────
val DangerRed = Color(0xFFF5222D)
val DangerRedDark = Color(0xFFFF5A5F)
val CapsuleSelectedLight = Color(0xFFE8EDFF)
val CapsuleSelectedDark = Color(0xFF243056)

// ── 状态色 ───────────────────────────────────────────────
/** 上下文接近阈值时的提醒色。不用红色 —— 那是"出错了"，这里是"注意一下"。 */
val WarningAmber = Color(0xFFD98A1F)
val WarningAmberDark = Color(0xFFE8A33D)
val FieldBgLight = TextPrimaryLight.copy(alpha = 0.05f)            // 原 #F2F3F5
val FieldBgDark = TextPrimaryDark.copy(alpha = 0.10f)              // 原 #232427

// ── Markdown（对齐官方 Web 端的配色 token）────────────────
//
// 官方 `markdown.css` 用了一组独立的格子里：代码块 `/neutral-bluish-50`、
// 行内代码 `-100`、引用竖线 `label-caption(400)`、表格横线 `border-l2`。
// 我们的 `fieldBackground` 是给输入框/胶囊用的，比官方的代码块底色深一档 ——
// 直接拿它当代码块底色，整段代码会显得"发灰、压页面"。所以单独一组。
val MarkdownCodeBgLight = TextPrimaryLight.copy(alpha = 0.025f)     // 原 #F9FAFB
val MarkdownCodeBgDark = TextPrimaryDark.copy(alpha = 0.055f)       // 原 #1B1B1C
val MarkdownInlineCodeBgLight = TextPrimaryLight.copy(alpha = 0.08f) // 原 #EBEEF2
val MarkdownInlineCodeBgDark = TextPrimaryDark.copy(alpha = 0.13f)   // 原 #2A2B2F
val MarkdownQuoteBarLight = TextPrimaryLight.copy(alpha = 0.34f)     // 原 #ADB2B8
val MarkdownQuoteBarDark = TextPrimaryDark.copy(alpha = 0.54f)       // 原 #81858C
val MarkdownRuleLight = Color(0x1A000000)
val MarkdownRuleDark = Color(0x1FFFFFFF)
