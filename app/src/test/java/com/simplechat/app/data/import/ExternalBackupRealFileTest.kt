package com.simplechat.app.data.import

import com.simplechat.app.R
import java.io.File
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * 用**真实的** Chatbox 备份跑一遍解析。
 *
 * ### 为什么单开一个测试类
 *
 * [ExternalBackupTest] 用的是手写 JSON —— 它锁的是"我们打算认哪些字段"。
 * 但那份 JSON 是**按我对格式的理解写出来的**，理解错了它照样全绿。
 * 真实文件才能暴露"我理解错了"：
 *
 * 这份备份第一次跑就抓出四处偏差 ——
 * 会话在 `sessions/<id>/session.json`（带一层子目录）、消息不写 `content`、
 * **系统提示词是一条 system 消息而不是 `session.systemPrompt`**、
 * 图片存在 ZIP 的 resources 里要靠 `originalStorageKeys` 找回。
 *
 * ### 没有 fixture 时会跳过
 *
 * 备份文件属于用户数据，不适合塞进仓库。文件不在就 `assumeTrue` 跳过，
 * 而不是失败 —— 否则别人 clone 下来会看到一个莫名其妙的红。
 */
class ExternalBackupRealFileTest {

    /**
     * 找本机的测试备份。
     *
     * **按模式找、不写死文件名** —— 具体那份备份叫什么、是哪天的，属于用户数据，
     * 不该出现在仓库里。约定：`测试文件/` 下放一个 `chatbox-backup*.zip` 即可。
     */
    private fun backupFile(): File {
        val dirs = listOf(File("../测试文件"), File("测试文件"))
        val found = dirs.firstNotNullOfOrNull { dir ->
            dir.listFiles { f -> f.isFile && f.name.startsWith("chatbox-backup") && f.name.endsWith(".zip") }
                ?.maxByOrNull { it.name }
        }
        return found ?: File(dirs.first(), "chatbox-backup.zip")
    }

    private fun parseReal(): ExternalBackup.Parsed {
        val file = backupFile()
        assumeTrue("测试备份不在，跳过：${file.absolutePath}", file.exists())
        // 命名文案自己塞进来：纯 JVM 单测不碰 Android Resources（界面那边随语言取）
        return ExternalBackup.parse(
            file.readBytes(),
            naming = ExternalBackup.ImportNaming(
                imageName = { w, h -> "图片 ${w}×${h}" },
                imageFallbackName = { "导入的图片" },
                conversationFallbackTitle = { "导入的对话" },
            ),
        )
    }

    @Test
    fun `parses a real chatbox backup`() {
        val parsed = parseReal()

        assertEquals(ExternalBackup.Source.CHATBOX, parsed.source)
        // 会话条目数**自己从 ZIP 里数**，不写死数字（备份是用户数据，规模也是）。
        // 个别条目没有可用消息会被跳过，所以允许少一些；但绝不该漏掉大半 ——
        // 那说明 sessions 路径匹配写错了
        val entries = ZipFile(backupFile()).use { zip ->
            zip.entries().asSequence()
                .count { !it.isDirectory && it.name.startsWith("sessions/") && it.name.endsWith(".json") }
        }
        assertTrue(
            "会话条目 $entries 个，只解析出 ${parsed.file.conversations.size} 个",
            parsed.file.conversations.size >= entries / 2,
        )
    }

    @Test
    fun `reads the session title from the name field`() {
        val parsed = parseReal()

        val titles = parsed.file.conversations.map { it.title }
        assertTrue("标题不该是空的", titles.all { it.isNotBlank() })
        // 断言**格式**而不是具体名字：备份里的会话名是用户的真实数据，不该写进仓库。
        // 读错时最典型的表现是拿会话 id（36 位 UUID）当标题。
        assertTrue(
            "标题读成了会话 id，说明 name 字段没取对",
            titles.none { it.length == 36 && it.count { c -> c == '-' } == 4 },
        )
    }

    /**
     * 本轮最重要的一条。
     *
     * 真实格式里系统提示词是**一条 system 消息**，而不是 `session.systemPrompt`。
     * 只按文档写的话，所有会话的人设会全部丢失 —— 而对一个写作应用来说，
     * 系统提示词就是会话的全部价值。
     */
    @Test
    fun `turns the system message into the conversation system prompt`() {
        val parsed = parseReal()

        val withPrompt = parsed.file.conversations.filter { !it.systemPrompt.isNullOrBlank() }
        assertEquals(
            "应该有绝大多数会话带上了系统提示词",
            parsed.file.conversations.size,
            withPrompt.size,
        )

        // 角色扮演类会话的人设开头会自报姓名（= 会话标题）；拿它当"取到的是正文
        // 而不是半截"的判据。**不写具体名字** —— 同上，那是用户的真实数据。
        val named = withPrompt.firstOrNull { it.systemPrompt.orEmpty().contains(it.title) }
        assertNotNull("没有会话的人设里出现过自己的标题", named)
        assertTrue("系统提示词太短，可能只取到了一部分", named!!.systemPrompt!!.length > 200)
    }

    @Test
    fun `does not leak system messages into the chat stream`() {
        val parsed = parseReal()

        val roles = parsed.file.conversations.flatMap { it.messages }.map { it.role }.toSet()
        assertEquals("对话流里只该有 user / assistant", setOf("user", "assistant"), roles)
    }

    @Test
    fun `reads message text out of contentParts`() {
        val parsed = parseReal()

        val messages = parsed.file.conversations.flatMap { it.messages }
        assertTrue("一条消息都没读到", messages.size > 100)
        assertTrue("有正文的消息太少", messages.count { it.content.isNotBlank() } > 100)
    }

    @Test
    fun `rebuilds an unbroken parent chain`() {
        val parsed = parseReal()

        for (conversation in parsed.file.conversations) {
            val ids = conversation.messages.map { it.id }.toSet()
            assertEquals("首条消息该挂在根上", "", conversation.messages.first().parentId)
            for (message in conversation.messages.drop(1)) {
                assertTrue(
                    "会话「${conversation.title}」里有断链：${message.parentId} 不在本会话中",
                    message.parentId in ids,
                )
            }
        }
    }

    @Test
    fun `extracts image attachments with real dimensions`() {
        val parsed = parseReal()

        val images = parsed.file.conversations
            .flatMap { it.messages }
            .flatMap { it.attachments }
            .filter { it.isImage }

        assertTrue("没有取到任何图片附件", images.isNotEmpty())
        for (image in images) {
            assertTrue("图片 base64 太短，多半没读到字节", image.data.length > 1000)
            // 尺寸为 0 会让 UI 把图当正方形（ratio 退化成 1f），横图会被压扁
            assertTrue("图片没解析出尺寸", image.width > 0 && image.height > 0)
        }
    }

    @Test
    fun `does not import session avatars as attachments`() {
        val parsed = parseReal()

        // 7 个资源里 6 个是 kind=avatar 的会话头像，混进来会变成一堆莫名的图片气泡
        val images = parsed.file.conversations.flatMap { it.messages }.flatMap { it.attachments }
        assertTrue("头像被当成消息图片导入了（共 ${images.size} 张）", images.size <= 2)
    }

    @Test
    fun `reports its losses instead of pretending nothing was lost`() {
        val parsed = parseReal()

        // 警告是 AppText（文案随界面语言），断言的是"报了哪一条"而不是句子本身
        assertTrue(
            "没告诉用户丢掉了分支",
            parsed.warnings.any { it.resId == R.string.warn_single_branch },
        )
    }

    @Test
    fun `a session with no copilot export yields no presets`() {
        val parsed = parseReal()

        // 这份备份的 exportItems 只有 conversations，data 是空的
        assertEquals(emptyList<Any>(), parsed.file.presets)
    }
}
