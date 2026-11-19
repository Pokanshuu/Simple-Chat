package com.simplechat.app.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.DrawerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.simplechat.app.R
import com.simplechat.app.db.ConversationEntity
import com.simplechat.app.ui.history.ConversationSearchPage
import com.simplechat.app.ui.history.HistoryViewModel
import com.simplechat.app.ui.history.SearchPage
import com.simplechat.app.ui.history.searchResultKey

@Composable
internal fun SearchContent(
    searchPage: SearchPage,
    listState: LazyListState,
    autoFocus: Boolean,
    selectedSearchResultKey: String?,
    historyViewModel: HistoryViewModel,
    onBack: () -> Unit,
    onOpenSearchResult: (com.simplechat.app.db.ConversationSearchHit, String) -> Unit,
    onRename: (ConversationEntity) -> Unit,
    onTogglePin: (ConversationEntity) -> Unit,
    onDelete: (ConversationEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    ConversationSearchPage(
        hits = searchPage.hits,
        isLoading = searchPage.isLoading,
        hasMore = searchPage.hasMore,
        onLoadMore = historyViewModel::loadMoreSearchResults,
        listState = listState,
        autoFocus = autoFocus,
        selectedResultKey = selectedSearchResultKey,
        query = searchPage.query,
        onQueryChange = { historyViewModel.updateQuery(it) },
        onBack = onBack,
        onClick = { hit ->
            if (searchPage.query == historyViewModel.query) onOpenSearchResult(hit, searchPage.query.trim())
        },
        onRename = onRename,
        onTogglePin = onTogglePin,
        onDelete = onDelete,
        modifier = modifier.fillMaxSize(),
    )
}
