package com.simplechat.app.data

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 时间上下文（docs/43 §53.5 / §53.9）。
 *
 * 这里钉死"模型看到的时间"：分档边界、时区偏移（含 DST）、不可信时间戳的省略、
 * 官方通道只补缺项、以及关闭时完全不产生注入文本。
 */
class TimeContextTest {

    private val hongKong: ZoneId = ZoneId.of("Asia/Hong_Kong")
    private val now: Long = LocalDateTime.of(2026, 11, 29, 14, 32).atZone(hongKong).toInstant().toEpochMilli()

    private fun facts(
        zone: ZoneId = hongKong,
        cjk: Boolean = true,
        lastMessageAtMillis: Long? = null,
        conversationStartMillis: Long? = null,
        nowMillis: Long = now,
    ) = TimeFacts(
        nowMillis = nowMillis,
        zone = zone,
        cjk = cjk,
        lastMessageAtMillis = lastMessageAtMillis,
        conversationStartMillis = conversationStartMillis,
    )

    // ── 相对分档 ─────────────────────────────────────────

    @Test
    fun `相对分档覆盖全部边界`() {
        fun label(ms: Long) = TimeContext.spanLabel(ms, cjk = true)
        assertEquals("刚刚", label(30_000))
        assertEquals("约 1 分钟", label(60_000))
        assertEquals("约 2 分钟", label(90_000))
        assertEquals("约 54 分钟", label(54 * 60_000L))
        // 55 分钟提前进位到小时，避免"约 60 分钟"这种怪值
        assertEquals("约 1 小时", label(55 * 60_000L))
        assertEquals("约 23 小时", label(23 * 3_600_000L))
        assertEquals("约 1 天", label(24 * 3_600_000L))
        assertEquals("约 1 天", label(47 * 3_600_000L))
        assertEquals("约 2 天", label(48 * 3_600_000L))
        assertEquals("约 29 天", label(29 * 86_400_000L))
        assertEquals("约 1 个月", label(30 * 86_400_000L))
        assertEquals("约 12 个月", label(364 * 86_400_000L))
        assertEquals("约 1 年", label(365 * 86_400_000L))
    }

    @Test
    fun `负数与英文分档`() {
        assertEquals(null, TimeContext.spanLabel(-1, cjk = true))
        assertEquals("just now", TimeContext.spanLabel(10_000, cjk = false))
        assertEquals("about 3 minutes", TimeContext.spanLabel(3 * 60_000L, cjk = false))
        assertEquals("about 1 hour", TimeContext.spanLabel(60 * 60_000L, cjk = false))
        assertEquals("about 2 days", TimeContext.spanLabel(2 * 86_400_000L, cjk = false))
    }

    // ── 可信性 ───────────────────────────────────────────

    @Test
    fun `时间戳可信性`() {
        assertTrue(TimeContext.isTrustworthy(now - 1_000, now))
        assertTrue(TimeContext.isTrustworthy(now + 4 * 60_000L, now))
        assertFalse(TimeContext.isTrustworthy(now + 6 * 60_000L, now))
        assertFalse(TimeContext.isTrustworthy(0L, now))
        assertFalse(TimeContext.isTrustworthy(null, now))
    }

    // ── 完整格式 ─────────────────────────────────────────

    @Test
    fun `中文完整版包含日期星期时刻时区偏移与权威声明`() {
        val note = TimeContext.buildNote(facts())
        assertTrue(note.contains("[时间上下文]"))
        assertTrue(note.contains("2026-11-29"))
        assertTrue(note.contains("14:32"))
        assertTrue(note.contains("Asia/Hong_Kong"))
        assertTrue(note.contains("UTC+08:00"))
        assertTrue(note.contains("以上时间由设备时钟生成"))
    }

    @Test
    fun `相对间隔与会话跨度按档位写入`() {
        val note = TimeContext.buildNote(
            facts(
                lastMessageAtMillis = now - 3 * 60_000L,
                conversationStartMillis = now - 8 * 86_400_000L,
            ),
        )
        assertTrue(note.contains("距上一条消息约 3 分钟"))
        assertTrue(note.contains("本会话开始于 2026-11-21"))
        assertTrue(note.contains("约 8 天前"))
    }

    @Test
    fun `不足一分钟的间隔说不到一分钟`() {
        val note = TimeContext.buildNote(facts(lastMessageAtMillis = now - 20_000))
        assertTrue(note.contains("距上一条消息不到 1 分钟"))
    }

    @Test
    fun `不可信时间戳整句省略而不是写未知`() {
        val note = TimeContext.buildNote(
            facts(lastMessageAtMillis = 0L, conversationStartMillis = 0L),
        )
        assertFalse(note.contains("距上一条消息"))
        assertFalse(note.contains("本会话开始"))
        assertFalse(note.contains("未知"))
    }

    @Test
    fun `会话跨度不足两天时不占用一行`() {
        val note = TimeContext.buildNote(facts(conversationStartMillis = now - 86_400_000L))
        assertFalse(note.contains("本会话开始"))
    }

    @Test
    fun `官方通道只补缺项不重复日期`() {
        val note = TimeContext.buildNote(
            facts(lastMessageAtMillis = now - 60_000L),
            includeDate = false,
            includeWeekday = false,
        )
        assertFalse(note.contains("2026-11-29"))
        assertTrue(note.contains("现在 14:32"))
        assertTrue(note.contains("距上一条消息约 1 分钟"))
    }

    @Test
    fun `英文版格式`() {
        val note = TimeContext.buildNote(
            facts(
                cjk = false,
                lastMessageAtMillis = now - 3 * 60_000L,
                conversationStartMillis = now - 8 * 86_400_000L,
            ),
        )
        assertTrue(note.contains("[Time context] 2026-11-29 Sunday 14:32"))
        assertTrue(note.contains("(Asia/Hong_Kong, UTC+08:00)"))
        assertTrue(note.contains("about 3 minutes since the previous message"))
        assertTrue(note.contains("this conversation started on 2026-11-21"))
        assertTrue(note.contains("takes precedence over other times"))
    }

    @Test
    fun `UTC 偏移显示为 UTC+00-00`() {
        val note = TimeContext.buildNote(facts(zone = ZoneOffset.UTC))
        assertTrue(note.contains("UTC+00:00"))
    }

    @Test
    fun `夏令时偏移由系统时区现算`() {
        val berlin = ZoneId.of("Europe/Berlin")
        val summer = LocalDateTime.of(2026, 7, 1, 12, 0).atZone(berlin).toInstant().toEpochMilli()
        val winter = LocalDateTime.of(2026, 1, 1, 12, 0).atZone(berlin).toInstant().toEpochMilli()
        assertTrue(TimeContext.buildNote(facts(zone = berlin, nowMillis = summer)).contains("UTC+02:00"))
        assertTrue(TimeContext.buildNote(facts(zone = berlin, nowMillis = winter)).contains("UTC+01:00"))
    }

    @Test
    fun `没有相对信息时也至少给出绝对时间`() {
        val note = TimeContext.buildNote(facts())
        assertTrue(note.contains("14:32"))
        assertTrue(note.contains("2026-11-29"))
    }
}
