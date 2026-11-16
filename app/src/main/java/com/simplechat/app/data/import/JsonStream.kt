package com.simplechat.app.data.import

import java.io.Reader

/**
 * **流式** JSON 扫描器：从 [Reader] 逐字符读，**永不把整份文档留在内存里**。
 *
 * ### 为什么需要它
 *
 * DeepSeek 导出的单个会话 JSON 可以很大：长篇角色扮演 + 反复重新生成，几十 MB 是常态。
 * 早先的写法是 `readBounded(...).decodeToString()` —— 先把整份文本读成一个 `String`，
 * 再切分。问题不只是"多占一份内存"：
 *
 * - 一个 40MB 的 UTF-8 会话，读成 `String` 就是 80MB（UTF-16），再乘上文法切片
 *   与逐条解析的临时对象，很容易把堆吃穿；
 * - 因此旧实现给单会话设了 24MB 上限，超了就**整条跳过** —— 用户看到的是
 *   "我最长的几个对话导入不了"（真实反馈）。上限只是把内存问题转嫁成了数据丢失。
 *
 * 换成流式之后，峰值内存只与**当前这一条消息**有关，与会话总大小无关，
 * 于是上限可以彻底取消。
 *
 * ### 为什么是"拉"而不是"回调"
 *
 * 回调式（`forEachElement { ... }`）里无法挂起，而写库是 `suspend` 的 ——
 * 要么把整条会话攒进内存再写（回到老问题），要么引入线程/队列。
 * 拉式的 `while (hasNextArrayElement()) { captureElement() ... }` 可以写在
 * `suspend` 函数里：读一条、写一条，内存天然有界。
 *
 * ### 只认 JSON 的骨架
 *
 * 这里只负责**结构**（对象字段、数组元素、字符串/数字的边界），值本身原样切出来交给
 * kotlinx 解析。所以它不会误解正文里的 `{}`、引号、逗号 —— 那些都在字符串里。
 */
internal class JsonStream(private val source: Reader) {

    private val buffer = CharArray(BufferChars)
    private var position = 0
    private var filled = 0

    /**
     * 每一层容器"下一个值前面要不要先吃逗号"。
     *
     * 用栈而不是一个布尔：数组可以嵌套（`messages[].fragments[]`），
     * 一个全局标志会被内层改坏，表现是"读到第二条消息就错位"。
     */
    private val firstInContainer = ArrayDeque<Boolean>()

    /** 捕获当前值用的复用缓冲。**不要**跨调用持有它的内容。 */
    private var capture = StringBuilder()

    fun valueKind(): Char? {
        skipWhitespace()
        return peek().takeIf { it >= 0 }?.toChar()
    }

    // ── 容器 ──────────────────────────────────────────────

    fun beginObject() {
        skipWhitespace()
        expect('{')
        firstInContainer.addLast(true)
    }

    fun beginArray() {
        skipWhitespace()
        expect('[')
        firstInContainer.addLast(true)
    }

    /** 还有下一个字段吗？到 `}` 时返回 false 并吃掉它（退出这一层）。 */
    fun hasNextField(): Boolean = nextInContainer('}')

    /** 还有下一个数组元素吗？到 `]` 时返回 false 并吃掉它（退出这一层）。 */
    fun hasNextArrayElement(): Boolean = nextInContainer(']')

    private fun nextInContainer(close: Char): Boolean {
        skipWhitespace()
        if (peek() == close.code) {
            next()
            firstInContainer.removeLast()
            return false
        }
        if (firstInContainer.isEmpty()) fail("容器没有正确开始")
        val first = firstInContainer.removeLast()
        firstInContainer.addLast(false)
        if (!first) {
            expect(',')
            skipWhitespace()
        }
        return true
    }

    /** 读字段名（读到冒号为止），值本身留给调用方接着读。 */
    fun fieldName(): String {
        skipWhitespace()
        val name = readDecodedString(Int.MAX_VALUE)
        skipWhitespace()
        expect(':')
        skipWhitespace()
        return name
    }

    // ── 值 ────────────────────────────────────────────────

    /**
     * 把当前值的**原文**整段切出来（含字符串引号与转义原样保留）。
     *
     * "原文"很关键：切出来的片段要能直接交给 kotlinx 再解析，
     * 所以这里**不能**把 `\n` 反转义成换行 —— 那会变成非法 JSON。
     */
    fun captureValue(): String {
        // A previous large value must not pin its backing array for the entire session.
        if (capture.capacity() > 64 * 1024) capture = StringBuilder() else capture.setLength(0)
        scanValue(capture)
        return capture.toString()
    }

    /** 跳过当前值（不建对象、不复制正文）。 */
    fun skipValue() {
        scanValue(null)
    }

    fun copyValueTo(output: Appendable) { scanValue(output) }

    /**
     * 读一个标量：字符串 → 反转义后的内容；`null` → null；数字/布尔 → 原文。
     *
     * @param maxChars 字符串最多保留多少字符（其余**照读不误**，只是丢弃）——
     *   导入时只要标题候选的"开头几个字"，没必要把整段正文搬进内存。
     */
    fun textValue(maxChars: Int = Int.MAX_VALUE): String? {
        skipWhitespace()
        if (peek() == '"'.code) return readDecodedString(maxChars)
        val raw = captureValue()
        return if (raw == "null") null else raw
    }

    /** Decode one JSON string directly to a file/writer, without building its full value. */
    fun copyStringTo(output: Appendable) { readDecodedString(0, output) }

    private fun scanValue(out: Appendable?) {
        skipWhitespace()
        when (peek()) {
            -1 -> fail("文档意外结束")
            '"'.code -> scanStringRaw(out)
            '{'.code, '['.code -> scanContainer(out)
            else -> scanLiteral(out)
        }
    }

    /** 原样抄一个字符串（转义序列一起抄），只负责找对结尾引号。 */
    private fun scanStringRaw(out: Appendable?) {
        out?.append('"')
        next()
        while (true) {
            val char = next()
            if (char < 0) fail("字符串没有闭合")
            out?.append(char.toChar())
            when (char) {
                '"'.code -> return
                '\\'.code -> {
                    val escaped = next()
                    if (escaped < 0) fail("转义没有闭合")
                    out?.append(escaped.toChar())
                }
            }
        }
    }

    /** 跳过/抄下一个完整的对象或数组；字符串里的括号不算结构。 */
    private fun scanContainer(out: Appendable?) {
        var depth = 0
        var inString = false
        var escaped = false
        while (true) {
            val char = next()
            if (char < 0) fail("括号没有闭合")
            out?.append(char.toChar())
            if (inString) {
                when {
                    escaped -> escaped = false
                    char == '\\'.code -> escaped = true
                    char == '"'.code -> inString = false
                }
                continue
            }
            when (char) {
                '"'.code -> inString = true
                '{'.code, '['.code -> depth++
                '}'.code, ']'.code -> {
                    depth--
                    if (depth == 0) return
                }
            }
        }
    }

    /** 数字 / true / false / null：一直读到分隔符为止。 */
    private fun scanLiteral(out: Appendable?) {
        while (true) {
            val char = peek()
            if (char < 0) return
            val isDelimiter = char == ','.code || char == '}'.code || char == ']'.code ||
                char == ' '.code || char == '\n'.code || char == '\r'.code || char == '\t'.code
            if (isDelimiter) return
            val read = next()
            out?.append(read.toChar())
        }
    }

    /**
     * 读一个 JSON 字符串并**反转义**（当前字符必须是开头的 `"`）。
     *
     * @param maxChars 超过这个长度的字符照读不误、但不再保留 —— 用于"只要开头一段"的场景。
     */
    private fun readDecodedString(maxChars: Int, destination: Appendable? = null): String {
        skipWhitespace()
        if (next() != '"'.code) fail("期望字符串")
        val out = if (destination == null) StringBuilder() else null
        while (true) {
            val char = next()
            if (char < 0) fail("字符串没有闭合")
            when (char) {
                '"'.code -> return out?.toString().orEmpty()
                '\\'.code -> {
                    val escaped = next()
                    if (escaped < 0) fail("转义没有闭合")
                    when (escaped.toChar()) {
                        'u' -> {
                            var code = 0
                            repeat(4) {
                                val digit = next()
                                if (digit < 0) fail("\\u 转义没有闭合")
                                code = (code shl 4) or hexValue(digit)
                            }
                            if (destination != null) destination.append(code.toChar())
                            else if (out != null && out.length < maxChars) out.append(code.toChar())
                        }

                        else -> {
                            val decoded = when (escaped.toChar()) {
                                '"' -> '"'
                                '\\' -> '\\'
                                '/' -> '/'
                                'b' -> '\b'
                                'f' -> '\u000C'
                                'n' -> '\n'
                                'r' -> '\r'
                                't' -> '\t'
                                else -> fail("不认识的转义 \\${escaped.toChar()}")
                            }
                            if (destination != null) destination.append(decoded)
                            else if (out != null && out.length < maxChars) out.append(decoded)
                        }
                    }
                }

                else -> if (destination != null) destination.append(char.toChar())
                    else if (out != null && out.length < maxChars) out.append(char.toChar())
            }
        }
    }

    private fun hexValue(char: Int): Int = when (val code = char.toChar()) {
        in '0'..'9' -> code - '0'
        in 'a'..'f' -> code - 'a' + 10
        in 'A'..'F' -> code - 'A' + 10
        else -> fail("\\u 后面不是十六进制：$code")
    }

    // ── 字符流 ────────────────────────────────────────────

    private fun ensure(): Boolean {
        if (position < filled) return true
        filled = source.read(buffer)
        position = 0
        return filled > 0
    }

    private fun peek(): Int = if (ensure()) buffer[position].code else -1

    private fun next(): Int = if (ensure()) buffer[position++].code else -1

    private fun expect(expected: Char) {
        skipWhitespace()
        val char = next()
        if (char != expected.code) fail("期望 '$expected'，实际是 ${describe(char)}")
    }

    private fun skipWhitespace() {
        while (true) {
            when (peek()) {
                ' '.code, '\n'.code, '\r'.code, '\t'.code -> next()
                else -> return
            }
        }
    }

    private fun describe(char: Int): String = if (char < 0) "文件结束" else "'${char.toChar()}'"

    private fun fail(message: String): Nothing =
        throw IllegalArgumentException("会话 JSON 结构异常：$message")

    private companion object {
        /** 16K 字符：一次读一块，又不至于自己变成一次大分配。 */
        const val BufferChars = 16 * 1024
    }
}
