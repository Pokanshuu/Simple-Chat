package com.simplechat.app.ui.history

import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.simplechat.app.R
import com.simplechat.app.data.ChatRepository
import com.simplechat.app.data.Res
import com.simplechat.app.db.ConversationEntity
import com.simplechat.app.db.ConversationSearchHit
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 会话列表（侧拉抽屉）的状态与管理操作。
 *
 * 列表数据直接来自 Room 的 `Flow`，因此重命名 / 置顶 / 删除会自动反映到 UI，
 * 不需要手动刷新。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModel(private val repository: ChatRepository) : ViewModel() {

    private val queryFlow = MutableStateFlow("")

    /** 搜索关键词。空串即全量列表。 */
    var query by mutableStateOf("")
        private set

    /**
     * 抽屉列表：**全量**，不受搜索框影响。
     *
     * 搜索框只是个入口按钮（点了进搜索页），抽屉本身没有过滤态。
     */
    val conversations: StateFlow<List<ConversationEntity>> = repository.observeConversations()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * 搜索页结果：**标题或消息正文**命中，附命中的那条消息原文。
     *
     * 关键词为空时直接给空列表、不查库 —— 搜索页在空关键词下本来就只显示一句提示。
     */
    val searchHits: StateFlow<List<ConversationSearchHit>> = queryFlow
        .flatMapLatest { keyword ->
            if (keyword.isBlank()) {
                flowOf(emptyList())
            } else {
                repository.searchConversations(keyword)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 多选模式。 */
    var selectionMode by mutableStateOf(false)
        private set

    val selectedIds = mutableStateListOf<String>()

    fun updateQuery(value: String) {
        query = value
        queryFlow.value = value
    }

    // ── 多选 ──────────────────────────────────────────────

    fun enterSelection(initialId: String) {
        selectionMode = true
        if (initialId !in selectedIds) selectedIds.add(initialId)
    }

    fun toggleSelection(id: String) {
        if (id in selectedIds) selectedIds.remove(id) else selectedIds.add(id)
    }

    fun exitSelection() {
        selectionMode = false
        selectedIds.clear()
    }

    // ── 单条操作 ──────────────────────────────────────────

    fun rename(id: String, title: String) {
        viewModelScope.launch { repository.renameConversation(id, title) }
    }

    fun togglePin(conversation: ConversationEntity) {
        viewModelScope.launch { repository.setPinned(conversation.id, !conversation.pinned) }
    }

    fun delete(id: String) {
        viewModelScope.launch { repository.deleteConversations(listOf(id)) }
    }

    // ── 批量操作 ──────────────────────────────────────────

    fun pinSelected(pinned: Boolean) {
        val ids = selectedIds.toList()
        viewModelScope.launch {
            ids.forEach { repository.setPinned(it, pinned) }
            exitSelection()
        }
    }

    fun deleteSelected() {
        val ids = selectedIds.toList()
        viewModelScope.launch {
            repository.deleteConversations(ids)
            exitSelection()
        }
    }

    /** 首次启动播种内置预设。 */
    fun seedPresets() {
        viewModelScope.launch { repository.reconcileBuiltInPresets() }
    }

    companion object {
        fun factory(repository: ChatRepository): ViewModelProvider.Factory = viewModelFactory {
            initializer { HistoryViewModel(repository) }
        }
    }
}

/** 列表分组：置顶 / 今天 / 昨天 / 7 天内 / 更早。文案随界面语言取（`group_*`）。 */
enum class HistoryGroup(@StringRes private val labelRes: Int) {
    PINNED(R.string.group_pinned),
    TODAY(R.string.group_today),
    YESTERDAY(R.string.group_yesterday),
    LAST_7_DAYS(R.string.group_week),
    EARLIER(R.string.group_older),
    ;

    val label: String get() = Res.get(labelRes)
}

fun groupOf(conversation: ConversationEntity, now: Long): HistoryGroup {
    if (conversation.pinned) return HistoryGroup.PINNED
    val day = 24 * 60 * 60 * 1000L
    val delta = now - conversation.updatedAt
    return when {
        delta < day -> HistoryGroup.TODAY
        delta < 2 * day -> HistoryGroup.YESTERDAY
        delta < 7 * day -> HistoryGroup.LAST_7_DAYS
        else -> HistoryGroup.EARLIER
    }
}
