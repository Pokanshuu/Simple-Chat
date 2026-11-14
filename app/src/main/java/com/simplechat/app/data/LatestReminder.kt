package com.simplechat.app.data

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * 「时间地点提醒」的正文 —— 模型每轮都该知道的五件事：
 * **日期、星期、地点、平台、语言**。
 *
 * 官方 Chat Demo 把这个字符串以 `latest_reminder` 角色插在本轮提问之前，
 * 服务端据此让「今天几号」「我这几点」这类问题有正确答案。格式逐项对齐官方：
 *
 * ```
 * 2026-11-14,星期六,杭州,Android,中文
 * ```
 *
 * ### 为什么不用时间戳而用"人话"
 * 这个串是**给模型读的上下文**，不是给程序解析的。ISO 时间戳还要模型自己换算时区，
 * 换算错一次，之后整段对话的"今天是几号"都是错的。
 *
 * ### 为什么日期要显式格式化
 * 用 `LocalDate.toString()` 也能得到 `2026-11-14`，但那依赖"恰好是 ISO 格式"。
 * 显式写死格式，将来谁把类型换成别的也不会悄悄变形。
 *
 * 纯函数，有单测（`LatestReminderTest`）。
 */
fun createLatestReminder(
    now: LocalDateTime,
    /** 界面语言是否为中文 —— 决定星期与语言名的写法。 */
    cjk: Boolean,
    /** 地区，自由文本；留空则该位为空串（官方在拿不到位置时也是空串）。 */
    region: String,
    /** 平台名，Android 端固定 `Android`（官方 Web 端是 `Web`）。 */
    platform: String = "Android",
    /** 语言名，随界面语言（`prompt_language_name`）。 */
    languageLabel: String,
): String = listOf(
    now.toLocalDate().format(DATE),
    now.dayOfWeek.getDisplayName(TextStyle.FULL, if (cjk) Locale.SIMPLIFIED_CHINESE else Locale.ENGLISH),
    region.trim(),
    platform,
    languageLabel,
).joinToString(",")

/** `yyyy-MM-dd`。刻意用 [LocalDate] 单独格式化，不复用 `toString()`。 */
private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT)
