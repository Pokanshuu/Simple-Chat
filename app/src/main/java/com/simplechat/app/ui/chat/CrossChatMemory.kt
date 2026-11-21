package com.simplechat.app.ui.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.exp
import kotlin.math.ln

/**
 * 跨对话记忆：从**其它会话**里检索与当前提问相关的片段，拼成一段可注入的参考文本。
 *
 * 设计参考 DeepChat（`services/chat/memory.ts`），只保留能解释行为的部分：
 *
 * - **检索而不是总结**：不调用模型，不产生额外费用；每次发消息时在本地按词元相关性
 *   找几轮最像的历史对话。
 * - **注入到用户消息**而不是 system：这段每轮都会变，塞进 system 会让整段前缀的
 *   KV 缓存失效（DeepChat 也踩过这个坑）。
 * - **克制的触发条件**：只有明确带回忆意图（「之前 / 上次 / 记得」…）或实词足够多
 *   才兜底注入近期对话。否则每句「你好」都会拖一段历史，既费 token 又让人困惑。
 */
object CrossChatMemory {

    private const val MAX_MEMORY_CHARS = 2400
    private const val USER_SNIPPET_MAX = 220
    private const val ASSISTANT_SNIPPET_MAX = 360
    private const val MAX_ROUNDS_PER_SESSION = 2
    private const val RECENT_FALLBACK_LIMIT = 3
    internal const val MAX_SCAN_SESSIONS = 50
    internal const val MAX_DEPTH = 4096
    internal const val MAX_MESSAGES = 80
    internal const val MAX_BODY_CHARS = 1200
    internal const val MAX_TITLE_CHARS = 120
    private const val MAX_QUERY_CHARS = 1024
    private const val MAX_QUERY_TOKENS = 96
    private const val MAX_SCAN_MS = 220L
    private const val MAX_SESSION_LOAD_MS = 750L

    private val LATIN_TOKEN = Regex("[a-z0-9_.-]{2,}")
    private val HAN_RUN = Regex("[\\u4e00-\\u9fff]+")
    private val WHITESPACE = Regex("\\s+")

    private val STOP_WORDS = setOf(
        "the", "and", "for", "with", "that", "this", "from", "what", "when", "where",
        "which", "have", "has", "are", "was", "were", "you", "your", "about", "into",
        "can", "how", "why", "it", "is", "of", "to", "in", "on", "at", "by", "or",
        "什么", "怎么", "为什么", "可以", "请问", "一下", "我们", "你们", "他们",
        "这个", "那个", "这些", "那些", "刚才", "之前", "以前", "昨天", "今天",
        "现在", "的", "了", "吗", "呢", "吧", "啊", "我", "你", "他", "她", "它",
        "是", "在", "有", "和", "就", "都", "还", "要", "会", "能", "说", "把", "被",
    )

    private val RECALL_INTENT = Regex(
        "之前|以前|上次|昨天|前几天|过往|历史|聊过|提过|说过|记得|回忆|" +
            "remember|last time|previous|earlier",
        RegexOption.IGNORE_CASE,
    )

    /** 一个候选会话的引用信息。 */
    data class SessionRef(val id: String, val title: String, val updatedAt: Long)

    data class Message(val role: String, val content: String)

    private data class Round(val user: String, val assistant: String, val tokens: Set<String>)

    private data class Doc(
        val ref: SessionRef,
        val titleTokens: Set<String>,
        val rounds: List<Round>,
        val allTokens: Set<String>,
    )

    /** 一次检索的统计；[text] 为空表示没有注入。 */
    data class Result(
        val text: String,
        val queryTokens: Int = 0,
        val candidates: Int = 0,
        val picked: Int = 0,
        val fallback: Boolean = false,
        /** 没注入时的原因：ok / disabled / empty-query / no-candidates / no-history / no-match / truncated。 */
        val reason: String = "ok",
    ) {
        val injected: Boolean get() = text.isNotEmpty()
    }

    /**
     * 生成要注入的记忆文本；没有可用记忆时返回空 [Result]。
     *
     * [loadMessages] 负责按会话 id 取消息（调用方传入，避免本对象依赖仓库层）。
     */
    suspend fun build(
        query: String,
        currentConversationId: String,
        limit: Int,
        sessions: List<SessionRef>,
        loadMessages: suspend (String) -> List<Message>,
        scanMillis: Long = MAX_SCAN_MS,
    ): Result {
        if (limit <= 0) return Result("", reason = "disabled")
        val boundedQuery = query.take(MAX_QUERY_CHARS)
        if (boundedQuery.isBlank()) return Result("", reason = "empty-query")
        val queryTokens = tokenize(boundedQuery).asSequence().filter { it.length >= 2 }
            .take(MAX_QUERY_TOKENS).toSet()

        /*
         * 短问候不召回：只有「你 / 好」这种单字命中时，相关性再高也只是巧合 ——
         * 历史里每句助手开场白都含「你好」。要求至少两个实词（双字 / 英文词），
         * 或者用户明确表达了回忆意图。
         */
        val contentTokens = queryTokens.count { it.length >= 2 }
        if (contentTokens < 2 && !RECALL_INTENT.containsMatchIn(boundedQuery)) {
            return Result("", queryTokens.size, 0, reason = "short-query")
        }

        val candidates = sessions
            .asSequence()
            .filter { it.id != currentConversationId }
            .take(MAX_SCAN_SESSIONS)
            .toList()
        if (candidates.isEmpty()) {
            return Result("", queryTokens.size, 0, reason = "no-candidates")
        }

        val started = System.nanoTime()
        val docs = mutableListOf<Doc>()
        var budgetHit = false
        for (ref in candidates) {
            currentCoroutineContext().ensureActive()
            val remaining = scanMillis - (System.nanoTime() - started) / 1_000_000
            if (remaining <= 0) {
                budgetHit = true
                break
            }
            val doc = try {
                // A single bounded batch may finish after the soft limit. Keep its hits.
                withTimeoutOrNull(MAX_SESSION_LOAD_MS) {
                    index(ref.copy(title = ref.title.take(MAX_TITLE_CHARS)), loadMessages(ref.id), queryTokens)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                continue
            }
            if (doc == null) {
                budgetHit = true
                break
            }
            if (doc.rounds.isNotEmpty()) docs += doc
        }
        if (docs.isEmpty()) {
            return Result("", queryTokens.size, candidates.size, reason = if (budgetHit) "scan-budget" else "no-history")
        }

        // 词元文档频率 → IDF。标题命中算双倍。
        val docFreq = HashMap<String, Int>()
        for (token in queryTokens) {
            docFreq[token] = docs.count { it.allTokens.contains(token) }
        }
        val idf = docFreq.mapValues { (_, df) -> ln((docs.size + 1.0) / (df + 0.5)) + 0.5 }

        /*
         * 相关性只用**实词**（长度 ≥ 2：中文双字 / 英文词）打分。
         *
         * 单字命中在这个语料上几乎全是噪声 —— 「帮我写一段 Python 脚本」里的「本」「写」
         * 会命中任何含「本」「写」的旧对话，把 limit 填满。这与短问候那条门槛是同一类问题，
         * 只是发生在打分这一层。
         */
        val contentQueryTokens = queryTokens.filter { it.length >= 2 }.toSet()

        val now = System.currentTimeMillis()
        val ranked = docs.map { doc ->
            val titleScore = score(contentQueryTokens, doc.titleTokens, idf) * 2
            val bodyScore = score(contentQueryTokens, doc.allTokens, idf)
            val relevance = titleScore + bodyScore
            val ageDays = ((now - doc.ref.updatedAt).coerceAtLeast(0L)) / 86_400_000.0
            val recency = exp(-ageDays / 7)
            // (doc, 排序分, 是否有实词命中)
            Triple(doc, relevance + if (relevance > 0) recency * 0.5 else 0.0, relevance > 0)
        }.sortedWith(compareByDescending<Triple<Doc, Double, Boolean>> { it.second }
            .thenByDescending { it.first.ref.updatedAt })

        val selected = mutableListOf<Doc>()
        val seen = mutableSetOf<String>()
        for ((doc, _, contentHit) in ranked) {
            if (selected.size >= limit || !contentHit) break
            selected += doc
            seen += doc.ref.id
        }

        /*
         * 兜底：只在用户明确表达回忆意图时，补几个最近的会话。
         *
         * 刻意**不看查询长度**：普通的技术问题（「帮我写一段 Python 脚本」）也可能很长，
         * 长度不等于想回忆；照长度兜底等于把无关历史硬塞进上下文。
         */
        val allowRecentFallback = !budgetHit && RECALL_INTENT.containsMatchIn(boundedQuery)
        val recentLimit = if (allowRecentFallback) minOf(limit, RECENT_FALLBACK_LIMIT) else 0
        var fallbackUsed = false
        if (recentLimit > 0) {
            val before = selected.size
            for (doc in docs.sortedByDescending { it.ref.updatedAt }) {
                if (selected.size >= recentLimit) break
                if (seen.add(doc.ref.id)) selected += doc
            }
            fallbackUsed = selected.size > before
        }

        if (selected.isEmpty()) {
            return Result("", queryTokens.size, candidates.size, reason = if (budgetHit) "scan-budget" else "no-match")
        }

        val header = "以下是用户过往其他对话中的记忆记录（仅在确实与当前问题相关时参考；" +
            "若用户询问过往聊过的内容可直接引用，不相关则忽略）："
        val blocks = mutableListOf(header)
        var totalChars = header.length

        var included = 0
        selected.forEachIndexed { index, doc ->
            val scoredRounds = doc.rounds.mapIndexed { roundIndex, round ->
                Triple(round, roundIndex, score(queryTokens, round.tokens, idf))
            }
            val picked = scoredRounds
                .sortedWith(compareByDescending<Triple<Round, Int, Double>> { it.third }
                    .thenBy { it.second })
                .take(MAX_ROUNDS_PER_SESSION)
                .sortedBy { it.second }

            val lines = mutableListOf(
                "[${index + 1}] 对话《${doc.ref.title.ifBlank { "未命名对话" }}》" +
                    "（${formatTime(doc.ref.updatedAt)}）",
            )
            for ((round, _, _) in picked) {
                if (round.user.isNotBlank()) {
                    lines += "- 用户：${round.user}"
                }
                if (round.assistant.isNotBlank()) {
                    lines += "- 助手：${round.assistant}"
                }
            }
            val block = lines.joinToString("\n")
            if (totalChars + block.length + 2 > MAX_MEMORY_CHARS) return@forEachIndexed
            included++
            blocks += block
            totalChars += block.length + 2
        }

        val text = if (blocks.size > 1) blocks.joinToString("\n\n") else ""
        return Result(
            text = text,
            queryTokens = queryTokens.size,
            candidates = candidates.size,
            picked = included,
            fallback = fallbackUsed,
            reason = if (budgetHit) "scan-budget" else if (text.isEmpty()) "truncated" else "ok",
        )
    }

    private suspend fun index(ref: SessionRef, messages: List<Message>, queryTokens: Set<String>): Doc {
        val context = currentCoroutineContext()
        val titleTokens = tokenize(ref.title, queryTokens)
        val allTokens = HashSet(titleTokens)
        val rounds = mutableListOf<Round>()

        var pendingUser: String? = null
        for (message in messages.takeLast(MAX_MESSAGES)) {
            context.ensureActive()
            when (message.role) {
                "user" -> pendingUser = message.content.take(MAX_BODY_CHARS)
                "assistant" -> {
                    val user = pendingUser.orEmpty()
                    val assistant = message.content.take(MAX_BODY_CHARS)
                    pendingUser = null
                    if (user.isBlank() && assistant.isBlank()) continue
                    val tokens = tokenize(user, queryTokens)
                    tokens += tokenize(assistant, queryTokens)
                    allTokens += tokens
                    rounds += Round(
                        clip(compact(user), USER_SNIPPET_MAX, tokens),
                        clip(compact(assistant), ASSISTANT_SNIPPET_MAX, tokens),
                        tokens,
                    )
                }
            }
        }
        return Doc(ref, titleTokens, rounds, allTokens)
    }

    private fun score(queryTokens: Set<String>, target: Set<String>, idf: Map<String, Double>): Double {
        var sum = 0.0
        for (token in queryTokens) {
            if (target.contains(token)) {
                val weight = (idf[token] ?: 1.0) * if (token.length >= 2) 1.5 else 0.7
                sum += weight
            }
        }
        return sum
    }

    /** 中英混合分词：英文/数字按词，中文按单字 + 相邻双字，过滤停用词。 */
    internal fun tokenize(text: String, filter: Set<String>? = null): MutableSet<String> {
        val tokens = LinkedHashSet<String>()
        if (filter?.isEmpty() == true) return tokens
        val lower = text.lowercase(Locale.ROOT)
        LATIN_TOKEN.findAll(lower).forEach { match ->
            val word = match.value
            if (filter?.contains(word) ?: (word !in STOP_WORDS)) tokens += word
        }
        HAN_RUN.findAll(lower).forEach { match ->
            val run = match.value
            for (i in run.indices) {
                if (filter == null) {
                    val ch = run[i].toString()
                    if (ch !in STOP_WORDS) tokens += ch
                }
                if (i + 1 < run.length) {
                    val bigram = run.substring(i, i + 2)
                    if (filter?.contains(bigram) ?: (bigram !in STOP_WORDS)) tokens += bigram
                }
            }
        }
        return tokens
    }

    private fun compact(text: String): String = text.replace(WHITESPACE, " ").trim()

    /** 按查询词在文中的位置截取片段，让引用的部分尽量留在窗口里。 */
    private fun clip(text: String, maxLength: Int, queryTokens: Set<String>): String {
        if (text.length <= maxLength) return text
        val lower = text.lowercase(Locale.ROOT)
        var bestPos = -1
        for (token in queryTokens) {
            if (token.length < 2) continue
            val idx = lower.indexOf(token)
            if (idx != -1 && (bestPos == -1 || idx < bestPos)) bestPos = idx
        }
        if (bestPos <= (maxLength * 0.4).toInt()) return text.take(maxLength - 1) + "…"
        val start = (bestPos - (maxLength * 0.35).toInt()).coerceAtLeast(0)
        val end = (start + maxLength - 2).coerceAtMost(text.length)
        val prefix = if (start > 0) "…" else ""
        val suffix = if (end < text.length) "…" else ""
        return prefix + text.substring(start, end) + suffix
    }

    private fun formatTime(timestamp: Long): String =
        SimpleDateFormat("yyyy/M/d HH:mm", Locale.ROOT).format(Date(timestamp))
}
