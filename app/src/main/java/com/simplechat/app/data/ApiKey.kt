package com.simplechat.app.data

/**
 * API Key 的**输入清洗**与合法性判断。
 *
 * ### 为什么需要它
 *
 * HTTP 头只接受可见 ASCII（0x21–0x7E）。而 Key 是**粘贴**进来的，常见的三种脏数据
 * 都会在这一层炸掉，且报错完全看不懂：
 *
 * ```text
 * Unexpected char 0x4f55 at 8 in Authorization value
 * ```
 *
 * - 粘贴时带上了换行 / 空格（真机最常见）；
 * - 粘贴成了带中文的占位符（`sk-你的密钥`），`0x4f55` 就是「何」；
 * - 从网页复制带进了全角标点或零宽字符。
 *
 * OkHttp 不会说"你的 Key 里有中文"，只会把第一个非法字符的码点丢出来 ——
 * 所以在这一层先**清洗掉空白**，再把剩下的非法字符变成一句人话
 * （见 `R.string.error_api_key_chars`）。
 *
 * 纯对象，有单测（`ApiKeyTest`）。
 */
object ApiKey {

    /**
     * 去掉**所有**空白字符（含换行、全角空格）。
     *
     * 只去空白：Key 本身不会有空格，而"悄悄丢掉别的字符"可能把合法 Key 改坏 ——
     * 非 ASCII 一律留给 [isHeaderSafe] 判，让用户自己重新粘贴。
     */
    fun normalize(raw: String): String = raw.filterNot { it.isWhitespace() }

    /** 是否只含可见 ASCII —— 也就是能不能安全放进 `Authorization` 头。 */
    fun isHeaderSafe(key: String): Boolean = key.all { it.code in 0x21..0x7E }

    /** 第一个非法字符；全都合法时为 null（错误提示里报它，用户一眼能定位）。 */
    fun firstIllegalChar(key: String): Char? = key.firstOrNull { it.code !in 0x21..0x7E }
}
