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
            "2026-11-14,星期六,杭州,Android,中文",
            createLatestReminder(noon, cjk = true, region = "杭州", languageLabel = "中文"),
        )
    }

    @Test
    fun `英文界面输出英文星期与平台`() {
        assertEquals(
            "2026-11-14,Saturday,Hangzhou,Android,English",
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
            "2026-01-05,Monday,,Android,English",
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
        assertEquals("2026-11-14,星期六,,Android,中文", reminder)
        // 五项齐全：服务端按位置取值，少一项就整体错位
        assertEquals(5, reminder.split(",").size)
    }

    @Test
    fun `地区首尾空白被裁掉`() {
        assertEquals(
            "2026-11-14,星期六,杭州,Android,中文",
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
}
