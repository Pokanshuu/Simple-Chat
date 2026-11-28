package com.simplechat.app.ui.favorites

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.simplechat.app.data.ChatRepository
import com.simplechat.app.data.FavoriteGroupMode
import com.simplechat.app.data.SettingsStore
import com.simplechat.app.db.FavoriteEntity
import com.simplechat.app.db.FavoriteFolderEntity
import com.simplechat.app.db.FavoriteMembershipEntity
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class FavoritesViewModel(
    private val repository: ChatRepository,
    private val settingsStore: SettingsStore,
) : ViewModel() {
    val favorites: StateFlow<List<FavoriteEntity>> = repository.observeFavorites()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val folders: StateFlow<List<FavoriteFolderEntity>> = repository.observeFavoriteFolders()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val memberships: StateFlow<List<FavoriteMembershipEntity>> = repository.observeFavoriteMemberships()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val groupMode: StateFlow<FavoriteGroupMode> = settingsStore.flow
        .map { FavoriteGroupMode.fromId(it.favoritesGroupModeId) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FavoriteGroupMode.CONVERSATION)

    var query by mutableStateOf("")
        private set
    var results by mutableStateOf<List<FavoriteEntity>?>(null)
        private set
    var folderFilter by mutableStateOf<String?>(null)
        private set
    private var searchJob: Job? = null

    fun ensureDefault(name: String) {
        viewModelScope.launch { repository.ensureDefaultFavoriteFolder(name) }
    }

    fun updateQuery(value: String) {
        query = value
        refreshSearch()
    }

    /** 搜索结果只是快照：收藏被增删改之后必须重查，否则删掉的行还留在结果里。 */
    private fun refreshSearch() {
        val value = query
        searchJob?.cancel()
        if (value.isBlank()) {
            results = null
            return
        }
        searchJob = viewModelScope.launch { results = repository.searchFavorites(value) }
    }

    fun selectFolder(id: String?) { folderFilter = id }

    fun setGroupMode(mode: FavoriteGroupMode) {
        viewModelScope.launch { settingsStore.setFavoritesGroupMode(mode.id) }
    }

    fun createFolder(name: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch { onResult(repository.createFavoriteFolder(name) != null) }
    }

    fun renameFolder(id: String, name: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch { onResult(repository.renameFavoriteFolder(id, name)) }
    }

    fun deleteFolder(id: String) {
        viewModelScope.launch {
            repository.deleteFavoriteFolder(id)
            // 正在按这个夹筛选时，夹没了就把筛选落回「全部」，别留一个高亮不了的悬空状态。
            if (folderFilter == id) folderFilter = null
            refreshSearch()
        }
    }

    fun deleteFavorite(id: String) {
        viewModelScope.launch {
            repository.removeFavorite(id)
            refreshSearch()
        }
    }

    fun deleteFavorites(ids: Collection<String>) {
        viewModelScope.launch {
            ids.forEach { repository.removeFavorite(it) }
            refreshSearch()
        }
    }

    /** 批量把几个收藏加入所选夹，保留已有归属（批量操作只加不减）。 */
    fun addFoldersToFavorites(favoriteIds: Collection<String>, folderIds: Collection<String>) {
        viewModelScope.launch {
            favoriteIds.forEach { favoriteId ->
                // 归属由仓库在事务内合并，不用调用方手里的旧快照（并发/首帧会丢归属）。
                repository.addFavoriteFolders(favoriteId, folderIds.toList())
            }
        }
    }

    /** 面板保存归属；空集合 = 取消收藏（仓库内处理）。 */
    fun setFolders(favoriteId: String, folderIds: List<String>) {
        viewModelScope.launch {
            repository.setFavoriteFolders(favoriteId, folderIds)
            refreshSearch()
        }
    }

    fun updateNote(favoriteId: String, note: String?) {
        viewModelScope.launch {
            repository.updateFavorite(favoriteId, note?.trim()?.takeIf { it.isNotEmpty() })
            refreshSearch()
        }
    }

    fun folderIdsOf(favoriteId: String): List<String> =
        memberships.value.filter { it.favoriteId == favoriteId }.map { it.folderId }

    companion object {
        fun factory(repository: ChatRepository, settingsStore: SettingsStore): ViewModelProvider.Factory =
            viewModelFactory {
                initializer { FavoritesViewModel(repository, settingsStore) }
            }
    }
}
