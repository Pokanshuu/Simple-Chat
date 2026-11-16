package com.simplechat.app.net

import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class ErrorBodyTest {
    @Test fun `large gateway errors are bounded while short errors are preserved`() {
        assertEquals("short error", "short error".toResponseBody().readErrorText())
        assertEquals(65_536, "x".repeat(1_000_000).toResponseBody().readErrorText().length)
    }
}
