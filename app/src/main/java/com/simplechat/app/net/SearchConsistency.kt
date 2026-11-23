package com.simplechat.app.net

private val staleSearchOffClaim = Regex(
    "(?:联网|联网搜索|网络搜索|智能搜索|搜索)(?:功能|开关)?[^\\n。！？]{0,16}(?:已关闭|被关闭|是关闭|仍关闭|处于关闭|没有打开|未开启)|" +
        "(?:关闭了|关掉了|未开启|没有打开)[^\\n。！？]{0,10}(?:联网搜索|智能搜索|网络搜索|搜索功能)|" +
        "\\b(?:web search|search|browsing)(?: feature)? (?:is|remains|has been) (?:still )?(?:off|disabled|turned off)\\b",
    RegexOption.IGNORE_CASE,
)

internal fun claimsSearchIsOff(text: String): Boolean = staleSearchOffClaim.containsMatchIn(text)

private val searchCapabilityDenial = Regex(
    "(?:没有|無|未提供|未接入|不具备|不具備).{0,16}(?:搜索|搜尋|联网|網絡|網路|上网|网页).{0,10}(?:工具|能力|功能)|" +
        "(?:不能|无法|無法|沒法|没法|搜不了|不能够|不能夠).{0,10}(?:搜索|搜尋|联网|網絡|網路|上网|浏览网页)|" +
        "\\b(?:I (?:do not|don't|cannot|can't) (?:have|access)|I (?:cannot|can't))\\b.{0,40}\\b(?:web|internet|online|search)\\b|" +
        "\\b(?:no|without) (?:web|internet|online|search) (?:search )?(?:tool|access|capability)\\b",
    RegexOption.IGNORE_CASE,
)

/** Detect an explicit capability denial, not an ordinary statement that a query found nothing. */
internal fun deniesSearchCapability(text: String): Boolean = searchCapabilityDenial.containsMatchIn(text)

/** Only treat non-empty successful search results as proof that this turn had web access. */
internal fun hasSuccessfulWebSearch(blocks: List<ReplyBlock>): Boolean = blocks.any {
    it is ReplyBlock.SearchResult && !it.failed && it.results.isNotEmpty()
}
