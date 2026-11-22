package com.simplechat.app.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchConsistencyTest {
    @Test fun detectsChineseAndEnglishCapabilityDenials() {
        listOf(
            "我在这个对话里没有搜索或联网工具，所以搜不了。",
            "当前模型不能联网搜索。",
            "I don't have access to web search in this chat.",
            "There is no web search tool available.",
        ).forEach { assertTrue(it, deniesSearchCapability(it)) }
    }

    @Test fun doesNotTreatEmptySearchResultsOrNormalLimitationsAsCapabilityDenials() {
        listOf(
            "这次没有搜索到相关结果。",
            "搜索结果没有提供下周天气的准确数据。",
            "我无法确认这个预测是否准确。",
            "联网搜索结果如下。",
        ).forEach { assertFalse(it, deniesSearchCapability(it)) }
    }

    @Test fun onlyNonEmptySuccessfulSearchResultsProveSearchWasUsed() {
        val success = ReplyBlock.SearchResult("tool", "search", listOf(WebSource("https://example.org")))
        assertTrue(hasSuccessfulWebSearch(listOf(success)))
        assertFalse(hasSuccessfulWebSearch(listOf(success.copy(results = emptyList()))))
        assertFalse(hasSuccessfulWebSearch(listOf(success.copy(failed = true))))
    }
}
