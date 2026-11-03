package ai.arthax.app.ui.notifications

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ai.arthax.app.data.remote.api.ApiResult
import ai.arthax.app.data.repository.NotificationsRepository
import ai.arthax.app.data.repository.NotificationsRepository.ReadStatus
import ai.arthax.app.domain.model.AppNotification
import ai.arthax.app.push.NotificationTarget
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The Notifications screen: the CRM's notifications for this user, filtered by category,
 * read state, date and text, loaded a page at a time as the list scrolls.
 */
@HiltViewModel
class NotificationsViewModel @Inject constructor(
    private val repository: NotificationsRepository,
) : ViewModel() {

    data class UiState(
        val items: List<AppNotification> = emptyList(),
        val status: ReadStatus = ReadStatus.ALL,
        /** Null for every category. */
        val category: String? = null,
        val range: NotificationDateRange = NotificationDateRange.ANY,
        val query: String = "",
        /** category -> (total, unread) within [range]; empty until the first answer. */
        val counts: Map<String, Pair<Int, Int>> = emptyMap(),
        val unreadInRange: Int = 0,
        val total: Int = 0,
        val isLoading: Boolean = true,
        val isRefreshing: Boolean = false,
        val isLoadingMore: Boolean = false,
        val hasMore: Boolean = false,
        val isMarkingAll: Boolean = false,
        val error: String? = null,
        val errorDetail: String? = null,
        val message: String? = null,
        /** Rows whose full text is showing. */
        val expanded: Set<String> = emptySet(),
    ) {
        val hasFilters: Boolean
            get() = status != ReadStatus.ALL || category != null || range != NotificationDateRange.ANY || query.isNotBlank()

        /** Unread in what is being looked at: the chosen category, or all of them. */
        val unreadInView: Int
            get() = category?.let { counts[it]?.second } ?: unreadInRange

        val hasLoadedEverything: Boolean
            get() = !hasMore && !isLoading && !isLoadingMore && items.isNotEmpty()
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val query = MutableStateFlow("")
    private var nextPage = 1
    private var loadJob: Job? = null
    private var lastLoadedAt = 0L

    init {
        observeQuery()
        load(reset = true, showSpinner = true)
    }

    @OptIn(FlowPreview::class)
    private fun observeQuery() {
        viewModelScope.launch {
            query
                .drop(1)
                .debounce(SEARCH_DEBOUNCE_MILLIS)
                .distinctUntilChanged()
                .collect { load(reset = true, showSpinner = true) }
        }
    }

    fun onQueryChanged(value: String) {
        _state.update { it.copy(query = value) }
        query.value = value
    }

    fun setStatus(status: ReadStatus) {
        if (_state.value.status == status) return
        _state.update { it.copy(status = status) }
        load(reset = true, showSpinner = true)
    }

    /** Tapping the selected category again clears it. */
    fun setCategory(category: String?) {
        val next = if (_state.value.category == category) null else category
        _state.update { it.copy(category = next) }
        load(reset = true, showSpinner = true)
    }

    fun setRange(range: NotificationDateRange) {
        if (_state.value.range == range) return
        _state.update { it.copy(range = range) }
        load(reset = true, showSpinner = true)
    }

    fun clearFilters() {
        _state.update {
            it.copy(status = ReadStatus.ALL, category = null, range = NotificationDateRange.ANY, query = "")
        }
        query.value = ""
        load(reset = true, showSpinner = true)
    }

    fun refresh() = load(reset = true, showSpinner = false)

    /** Back on screen after a while: something new has very likely arrived. */
    fun refreshOnReturn() {
        val current = _state.value
        if (current.isLoading || current.isRefreshing) return
        if (System.currentTimeMillis() - lastLoadedAt < STALE_AFTER_MILLIS) return
        refresh()
    }

    fun loadMore() {
        val current = _state.value
        if (current.isLoadingMore || current.isLoading || !current.hasMore) return
        load(reset = false, showSpinner = false)
    }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    /**
     * A row was tapped: it is read now, as on the web. Returns where it points, or null when
     * it points nowhere outside this screen — then the row just shows its full text.
     */
    fun onOpen(item: AppNotification): NotificationTarget? {
        if (!item.isRead) markReadLocally(item)
        val target = item.target
        if (target == NotificationTarget.Inbox) {
            _state.update { s ->
                s.copy(expanded = if (item.id in s.expanded) s.expanded - item.id else s.expanded + item.id)
            }
            return null
        }
        return target
    }

    fun markRead(item: AppNotification) {
        if (!item.isRead) markReadLocally(item)
    }

    /** Optimistic: the row and the counts change at once; the server catches up. */
    private fun markReadLocally(item: AppNotification) {
        _state.update { s ->
            val counts = s.counts[item.category]?.let { (total, unread) ->
                s.counts + (item.category to (total to (unread - 1).coerceAtLeast(0)))
            } ?: s.counts
            s.copy(
                items = s.items.map { if (it.id == item.id) it.copy(isRead = true) else it }
                    // Under "Unread", a row that has just been read leaves the list.
                    .filterNot { s.status == ReadStatus.UNREAD && it.id == item.id },
                counts = counts,
                unreadInRange = (s.unreadInRange - 1).coerceAtLeast(0),
            )
        }
        viewModelScope.launch { repository.markRead(item.id) }
    }

    /** Marks everything unread in the current category and date range. */
    fun markAllRead() {
        val current = _state.value
        if (current.isMarkingAll || current.unreadInView == 0) return
        _state.update { it.copy(isMarkingAll = true) }
        viewModelScope.launch {
            when (val result = repository.markAllRead(filters(current))) {
                is ApiResult.Success -> {
                    val marked = result.data
                    _state.update {
                        it.copy(
                            isMarkingAll = false,
                            message = if (marked > 0) "Marked $marked as read" else "Nothing left unread",
                        )
                    }
                    load(reset = true, showSpinner = false)
                }

                is ApiResult.Failure -> _state.update {
                    it.copy(isMarkingAll = false, message = "Could not mark them read: ${result.message}")
                }
            }
        }
    }

    private fun filters(s: UiState): NotificationsRepository.Filters {
        val (from, to) = s.range.bounds()
        return NotificationsRepository.Filters(
            status = s.status,
            category = s.category,
            dateFrom = from,
            dateTo = to,
            query = query.value,
        )
    }

    private fun load(reset: Boolean, showSpinner: Boolean) {
        loadJob?.cancel()
        if (reset) nextPage = 1
        val page = nextPage
        val requested = filters(_state.value)

        _state.update {
            it.copy(
                isLoading = reset && showSpinner,
                isRefreshing = reset && !showSpinner,
                isLoadingMore = !reset,
                error = null,
                errorDetail = null,
            )
        }

        loadJob = viewModelScope.launch {
            // Chip counts follow the date range, so they are fetched with the first page.
            if (reset) {
                val summary = repository.fetchSummary(requested.dateFrom, requested.dateTo)
                if (summary is ApiResult.Success) {
                    _state.update { it.copy(counts = summary.data.byCategory, unreadInRange = summary.data.unread) }
                }
            }

            when (val result = repository.fetchPage(page, requested)) {
                is ApiResult.Success -> {
                    // A filter changed while this was in flight: its own load replaces it.
                    if (requested != filters(_state.value)) return@launch

                    val data = result.data
                    nextPage = data.page + 1
                    lastLoadedAt = System.currentTimeMillis()
                    _state.update { previous ->
                        val combined = if (reset) {
                            data.items
                        } else {
                            val seen = previous.items.mapTo(mutableSetOf()) { it.id }
                            previous.items + data.items.filterNot { it.id in seen }
                        }
                        previous.copy(
                            items = combined,
                            total = data.total,
                            hasMore = data.hasMore,
                            isLoading = false,
                            isRefreshing = false,
                            isLoadingMore = false,
                        )
                    }
                }

                is ApiResult.Failure -> _state.update {
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        isLoadingMore = false,
                        error = result.message,
                        errorDetail = result.detail,
                    )
                }
            }
        }
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MILLIS = 400L
        const val STALE_AFTER_MILLIS = 60_000L
    }
}

/** The bottom bar's badge: unread CRM notifications. */
@HiltViewModel
class NotificationBadgeViewModel @Inject constructor(
    private val repository: NotificationsRepository,
) : ViewModel() {

    val unread: StateFlow<Int> = repository.unread

    fun refresh() {
        viewModelScope.launch { repository.refreshUnread() }
    }
}
