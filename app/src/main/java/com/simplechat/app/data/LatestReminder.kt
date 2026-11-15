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
 * 服务端据此让「今天几号」「我这几点」这类问题有正确答案。格式由服务端校验，
 * 报错原文（实测 2026-11-15）写明了每一项的要求：
 *
 * ```
 * Invalid 'messages.0.content': unknown platform 'Android'.
 * Expected '<date>,<weekday>,<location>,<platform>,<language>'
 *   (e.g. '2026-11-09,Monday,Singapore,Web,English'):
 *   date in YYYY-MM-DD, weekday of that date in the reminder language,
 *   location may be empty, platform 'App' or 'Web',
 *   language a supported native language name.
 * ```
 *
 * 也就是说：**平台只有 `App` 和 `Web` 两个合法值**（不是 `Android`），
 * 星期要跟"语言"那一项用同一种语言，地区可以为空。
 *
 * ```
 * 2026-11-14,星期六,杭州,App,中文
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
 * ### 为什么地区要"洗"一遍
 * 这个串是**用逗号拼的**，而地区是用户自由输入的。用户一旦写「杭州, 浙江」或
 * 「Hangzhou, China」，就会多出两段 —— 模型看到的第 4/5 项全错位，
 * 而且从界面上完全看不出来（提醒本身不展示）。所以 [normalizeRegion] 把
 * 逗号、换行这些分隔符一律换成空格，并限长。
 *
 * 纯函数，有单测（`LatestReminderTest`）。
 */
fun createLatestReminder(
    now: LocalDateTime,
    /** 界面语言是否为中文 —— 决定星期与语言名的写法。 */
    cjk: Boolean,
    /** 地区，自由文本；留空则该位为空串（官方在拿不到位置时也是空串）。 */
    region: String,
    /**
     * 平台名。**只接受 `App` / `Web`** —— 服务端会校验，传 `Android` 直接 400：
     * `unknown platform 'Android'. ... platform 'App' or 'Web'`。
     */
    platform: String = LatestReminderPlatform,
    /** 语言名，随界面语言（`prompt_language_name`）。 */
    languageLabel: String,
): String = listOf(
    now.toLocalDate().format(DATE),
    now.dayOfWeek.getDisplayName(TextStyle.FULL, if (cjk) Locale.SIMPLIFIED_CHINESE else Locale.ENGLISH),
    normalizeRegion(region),
    platform,
    languageLabel,
).joinToString(",")

/** 地区名的长度上限。再长就不像"地点"、更像一段自我介绍了。 */
const val RegionMaxChars = 20

/**
 * 本应用的平台标识。
 *
 * 服务端只认两个值：`App`（原生客户端）与 `Web`（浏览器）。
 * 早先这里写的是 `Android` —— 照官方 Web 端推的，被服务端当场 400 打回。
 */
const val LatestReminderPlatform = "App"

/**
 * 把用户输入的地区洗成**可以安全拼进逗号分隔串**的一段。
 *
 * 规则只有两条，但都必要：
 * 1. **分隔符换成空格**：`,` `，` `、` 与换行都会把五项挤成六项。
 * 2. **限长 + 收空白**：`杭州 / 浙江` 这类写法保留语义（两个词），但不会撑爆那一格。
 *
 * 返回空串表示"不填这一项"（与官方拿不到定位时的行为一致）。
 *
 * 纯函数，有单测。设置页存储时洗一遍，拼串时再洗一遍 ——
 * 前者防手滑，后者防"历史数据里已经存了带逗号的旧值"。
 */
fun normalizeRegion(raw: String): String = raw
    .replace(Regex("[,，、\\n\\r\\t]+"), " ")
    .replace(Regex("\\s{2,}"), " ")
    .trim()
    .take(RegionMaxChars)
    .trim()

/** `yyyy-MM-dd`。刻意用 [LocalDate] 单独格式化，不复用 `toString()`。 */
private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT)
