package com.simplechat.app.net

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
