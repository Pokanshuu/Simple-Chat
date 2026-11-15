package com.simplechat.app.data

import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「时间地点提醒」的格式。
 *
 * 这个串是**给模型读的上下文**，格式错一项（比如星期写成英文、日期少了补零），
 * 模型对"今天几号"的回答就会跑偏，而界面上完全看不出来 —— 所以逐项钉死。
 */
class LatestReminderTest {

    private val noon = LocalDateTime.of(2026, 11, 14, 12, 30)

    @Test
    fun `中文界面输出中文星期`() {
        assertEquals(
            "2026-11-14,星期六,杭州,App,中文",
            createLatestReminder(noon, cjk = true, region = "杭州", languageLabel = "中文"),
        )
    }

    @Test
    fun `英文界面输出英文星期与平台`() {
        assertEquals(
            "2026-11-14,Saturday,Hangzhou,App,English",
            createLatestReminder(
                noon,
                cjk = false,
                region = "Hangzhou",
                languageLabel = "English",
            ),
        )
    }

    @Test
    fun `月份和日期补零`() {
        assertEquals(
            "2026-01-05,Monday,,App,English",
            createLatestReminder(
                LocalDateTime.of(2026, 1, 5, 8, 0),
                cjk = false,
                region = "",
                languageLabel = "English",
            ),
        )
    }

    @Test
    fun `地区留空时该位为空串而不是省略该项`() {
        val reminder = createLatestReminder(noon, cjk = true, region = "  ", languageLabel = "中文")
        assertEquals("2026-11-14,星期六,,App,中文", reminder)
        // 五项齐全：服务端按位置取值，少一项就整体错位
        assertEquals(5, reminder.split(",").size)
    }

    @Test
    fun `地区首尾空白被裁掉`() {
        assertEquals(
            "2026-11-14,星期六,杭州,App,中文",
            createLatestReminder(noon, cjk = true, region = "  杭州 ", languageLabel = "中文"),
        )
    }

    @Test
    fun `平台名可替换`() {
        assertEquals(
            "2026-11-14,星期六,杭州,Web,中文",
            createLatestReminder(
                noon,
                cjk = true,
                region = "杭州",
                platform = "Web",
                languageLabel = "中文",
            ),
        )
    }

    // ── 地区清洗 ──────────────────────────────────────────

    @Test
    fun `地区里的逗号被换成空格，不会把五项挤成七项`() {
        val reminder = createLatestReminder(
            noon,
            cjk = true,
            region = "杭州, 浙江",
            languageLabel = "中文",
        )
        assertEquals("2026-11-14,星期六,杭州 浙江,App,中文", reminder)
        assertEquals(5, reminder.split(",").size)
    }

    @Test
    fun `中文逗号与顿号同样处理`() {
        assertEquals("杭州 浙江", normalizeRegion("杭州，浙江"))
        assertEquals("杭州 浙江", normalizeRegion("杭州、浙江"))
    }

    @Test
    fun `换行与制表符被压成单个空格`() {
        assertEquals("杭州 浙江", normalizeRegion("杭州\n\t浙江"))
        assertEquals("杭州 浙江", normalizeRegion("杭州  浙江"))
    }

    @Test
    fun `地区超长时截断`() {
        assertEquals(RegionMaxChars, normalizeRegion("杭".repeat(50)).length)
    }

    @Test
    fun `纯空白地区归一为空串`() {
        assertEquals("", normalizeRegion("   \n  "))
        assertEquals("", normalizeRegion(""))
    }
}
