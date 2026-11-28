package com.simplechat.app.data

import java.time.DateTimeException
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * 时间上下文（docs/43 §53.5）。
 *
 * 每轮请求时由客户端现算的一小段文本，告诉模型"现在是什么时候、距离上一条消息多久"。
 * **不是工具调用、不落库、不写进系统提示词**：它作为尾部注记紧邻最新 user 消息，
 * 这样系统提示词的前缀缓存逐字节稳定，失效点最多落在最后一段对话上。
 *
 * 纯 Kotlin（不碰 Android / 资源），有单测（`TimeContextTest`）。
 */
data class TimeFacts(
    /** 本轮时间基准：**开始流式时**捕获一次，同一轮内的多次请求复用。 */
    val nowMillis: Long,
    /** 本机 IANA 时区。 */
    val zone: ZoneId,
    /** 界面语言是否中文 —— 决定星期与句式。 */
    val cjk: Boolean,
    /** 上一条已完成消息的时间；null / 不可信时省略相对间隔句。 */
    val lastMessageAtMillis: Long? = null,
    /** 本会话创建时间；null / 不可信时省略会话跨度句。 */
    val conversationStartMillis: Long? = null,
)

object TimeContext {

    private const val MINUTE = 60_000L
    private const val HOUR = 60 * MINUTE
    private const val DAY = 24 * HOUR
    private const val MONTH = 30 * DAY
    private const val YEAR = 365 * DAY

    /** 允许的时钟误差：时间戳最多比"现在"晚 5 分钟，再晚就当设备时钟被改过。 */
    internal const val CLOCK_TOLERANCE = 5 * MINUTE

    /** 会话跨度至少 2 天才值得单独说一句，否则每轮都多一句噪声。 */
    internal const val MIN_CONVERSATION_SPAN = 2 * DAY

    private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT)

    /**
     * 时间戳是否可信（§53.9.3）：非 0、不晚于现在（含 5 分钟容差）。
     *
     * 不可信时**整句省略**，而不是写"时间未知" —— 后者会被模型复述出来。
     */
    fun isTrustworthy(millis: Long?, nowMillis: Long): Boolean =
        millis != null && millis > 0L && millis <= nowMillis + CLOCK_TOLERANCE

    /**
     * 相对跨度文案（§53.9.1），返回"约 3 分钟 / 约 2 小时 / 约 8 天"这类片段。
     *
     * 边界：<60s 刚刚；<55min 分钟；<24h 小时；<48h 固定 1 天；<30d 天；
     * <365d 月（30 天折算）；其余年（365 天折算）。负数/0 返回 null。
     */
    fun spanLabel(deltaMillis: Long, cjk: Boolean): String? {
        if (deltaMillis < 0L) return null
        if (deltaMillis < MINUTE) return if (cjk) "刚刚" else "just now"
        fun value(unit: Long): Long = (deltaMillis + unit / 2) / unit
        return when {
            deltaMillis < 55 * MINUTE -> span(value(MINUTE), "分钟", "minute", cjk)
            deltaMillis < DAY -> span(value(HOUR), "小时", "hour", cjk)
            deltaMillis < 2 * DAY -> if (cjk) "约 1 天" else "about 1 day"
            deltaMillis < MONTH -> span(value(DAY), "天", "day", cjk)
            deltaMillis < YEAR -> span(value(MONTH), "个月", "month", cjk)
            else -> span(value(YEAR), "年", "year", cjk)
        }
    }

    private fun span(n: Long, zhUnit: String, enUnit: String, cjk: Boolean): String = if (cjk) {
        "约 $n $zhUnit"
    } else {
        "about $n $enUnit${if (n == 1L) "" else "s"}"
    }

    /**
     * 生成尾部注记。
     *
     * @param includeDate    是否包含日期（官方 DeepSeek 通道的 `latest_reminder` 已带日期，这里关掉避免两个来源）
     * @param includeWeekday 同上，是否包含星期
     */
    fun buildNote(
        facts: TimeFacts,
        includeDate: Boolean = true,
        includeWeekday: Boolean = true,
    ): String {
        val now = nowAt(facts) ?: return ""
        val time = "%02d:%02d".format(Locale.ROOT, now.hour, now.minute)
        val zone = facts.zone.id
        val offset = offsetLabel(now)

        val head = buildString {
            if (facts.cjk) {
                append("[时间上下文] ")
                if (includeDate) append(now.toLocalDate().format(DATE)).append(' ')
                else append("现在 ")
                if (includeWeekday) append(weekday(now, cjk = true)).append(' ')
                append(time).append("（").append(zone).append("，").append(offset).append("）")
            } else {
                append("[Time context] ")
                if (includeDate) append(now.toLocalDate().format(DATE)).append(' ')
                else append("now ")
                if (includeWeekday) append(weekday(now, cjk = false)).append(' ')
                append(time).append(" (").append(zone).append(", ").append(offset).append(')')
            }
        }

        val clauses = mutableListOf<String>()
        val last = facts.lastMessageAtMillis
        if (isTrustworthy(last, facts.nowMillis) && last != null) {
            val span = spanLabel(facts.nowMillis - last, facts.cjk) ?: ""
            if (span.isNotEmpty()) clauses += if (facts.cjk) {
                if (span == "刚刚") "距上一条消息不到 1 分钟" else "距上一条消息$span"
            } else {
                if (span == "just now") "less than a minute since the previous message"
                else "$span since the previous message"
            }
        }
        val start = facts.conversationStartMillis
        if (isTrustworthy(start, facts.nowMillis) && start != null) {
            val span = (facts.nowMillis - start)
            if (span >= MIN_CONVERSATION_SPAN) {
                val startDate = Instant.ofEpochMilli(start).atZone(facts.zone).toLocalDate().format(DATE)
                val spanText = spanLabel(span, facts.cjk).orEmpty()
                clauses += if (facts.cjk) {
                    "本会话开始于 $startDate（${spanText}前）"
                } else {
                    "this conversation started on $startDate ($spanText ago)"
                }
            }
        }

        val body = if (clauses.isEmpty()) {
            head
        } else {
            val joined = clauses.joinToString(if (facts.cjk) "；" else "; ")
            if (facts.cjk) "$head；\n$joined。" else "$head;\n$joined."
        }
        val authority = if (facts.cjk) {
            "以上时间由设备时钟生成，优先于对话中其他时间说法。"
        } else {
            "Generated from the device clock; it takes precedence over other times mentioned in the conversation."
        }
        return "$body\n$authority"
    }

    private fun nowAt(facts: TimeFacts): ZonedDateTime? = try {
        Instant.ofEpochMilli(facts.nowMillis).atZone(facts.zone)
    } catch (_: DateTimeException) {
        null
    }

    private fun offsetLabel(now: ZonedDateTime): String {
        val id = now.offset.id // "+08:00" / "Z"
        return if (id == "Z") "UTC+00:00" else "UTC$id"
    }

    private fun weekday(now: ZonedDateTime, cjk: Boolean): String = now.dayOfWeek.getDisplayName(
        TextStyle.FULL,
        if (cjk) Locale.SIMPLIFIED_CHINESE else Locale.ENGLISH,
    )
}
