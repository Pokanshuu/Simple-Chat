package com.simplechat.app.net

import okhttp3.ResponseBody
import okio.Buffer

/** Gateways may return HTML or echo prompts; bound memory and keep the body out of logcat. */
internal fun ResponseBody.readErrorText(limit: Long = 65_536): String {
    require(limit >= 0)
    val buffer = Buffer()
    val input = source()
    while (buffer.size < limit) {
        if (input.read(buffer, minOf(8192L, limit - buffer.size)) < 0) break
    }
    return buffer.readUtf8()
}
