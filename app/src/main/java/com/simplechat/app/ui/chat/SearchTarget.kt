package com.simplechat.app.ui.chat

data class SearchTarget(val messageId: String, val query: String, val request: Long, val ready: Boolean = false)
