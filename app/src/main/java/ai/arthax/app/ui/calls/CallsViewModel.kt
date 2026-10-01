package ai.arthax.app.ui.calls

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ai.arthax.app.call.CallTracker
import ai.arthax.app.data.local.prefs.AppSettings
import ai.arthax.app.data.remote.api.ApiResult
import ai.arthax.app.data.repository.CallHistoryRepository
import ai.arthax.app.domain.model.CallRecord
import ai.arthax.app.domain.model.Lead
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The rep's call history, as the CRM holds it.
 *
 * Read-only against the server. The one thing it *writes* is a click-to-call intent, when
 * the rep dials a lead back from a row — and that goes through [CallTracker], the same
 * entry point the leads list uses, so a call started here is attributed exactly like one
 * started there.
 */
@HiltViewModel
class CallsViewModel @Inject constructor(
    private val repository: CallHistoryRepository,
    private val callTracker: CallTracker,
    private val settings: AppSettings,
) : ViewModel() {

    /**
     * The outcome chips across the top. Null is "everything".
     *
     * Only the two outcomes the phone can actually produce. `follow_up` and the rest of the
     * server's outcome vocabulary are dispositions a rep sets in the CRM, not something this
     * app ever writes — so a chip for one filtered a list that was always empty.
     */
    enum class Filter(val label: String, val api: String?) {
        ALL("All", null),
        CONNECTED("Connected", "connected"),
        NOT_PICKED("Not picked up", "not_picked"),
    }

    data class UiState(
        val calls: List<CallRecord> = emptyList(),
        val query: String = "",
        val filter: Filter = Filter.ALL,
        val isLoading: Boolean = true,
        val isRefreshing: Boolean = false,
        val isLoadingMore: Boolean = false,
        val hasMore: Boolean = false,
        val total: Int = 0,
        val error: String? = null,
        val errorDetail: String? = null,
    ) {
        val hasLoadedEverything: Boolean
            get() = !hasMore && !isLoading && !isLoadingMore && calls.isNotEmpty()
    }

    /** The call whose detail sheet is open, and whatever has been loaded for it so far. */
    data class DetailState(
        val call: CallRecord,
        val isLoadingFull: Boolean = false,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _detail = MutableStateFlow<DetailState?>(null)
    val detail: StateFlow<DetailState?> = _detail.asStateFlow()

    /** One-shot dial intents. A Channel, not state, so a rotation cannot re-dial. */
    private val _callIntents = Channel<Intent>(Channel.BUFFERED)
    val callIntents: Flow<Intent> = _callIntents.receiveAsFlow()

    private val query = MutableStateFlow("")

    private var nextSkip = 0

    /**
     * The in-flight page. Held so a filter or search change can cancel a page that is about
     * to append rows belonging to the previous query.
     */
    private var loadJob: Job? = null

    private var detailJob: Job? = null

    private var lastLoadedAt = 0L

    init {
        observeQuery()
        load(reset = true, showSpinner = true)
    }

    @OptIn(FlowPreview::class)
    private fun observeQuery() {
        viewModelScope.launch {
            query
                .drop(1) // the initial empty value is covered by the first load
                .debounce(SEARCH_DEBOUNCE_MILLIS)
                .distinctUntilChanged()
                .collect { load(reset = true, showSpinner = true) }
        }
    }

    fun onQueryChanged(value: String) {
        _state.update { it.copy(query = value) }
        query.value = value
    }

    fun setFilter(filter: Filter) {
        if (_state.value.filter == filter) return
        _state.update { it.copy(filter = filter) }
        load(reset = true, showSpinner = true)
    }

    fun refresh() = load(reset = true, showSpinner = false)

    /**
     * Quietly brings the list up to date when the rep comes back to the tab.
     *
     * Calls land here minutes after they happen — the phone has to post them first, and the
     * AI assessment arrives later still — so a list left on screen goes stale in a way the
     * rep will notice. Only reloads when the last load is old enough to be worth a request.
     */
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

    private fun load(reset: Boolean, showSpinner: Boolean) {
        loadJob?.cancel()

        if (reset) nextSkip = 0
        val skip = nextSkip
        val searchAtRequestTime = query.value
        val filterAtRequestTime = _state.value.filter

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
            val result = repository.fetchCalls(
                skip = skip,
                search = searchAtRequestTime,
                outcome = filterAtRequestTime.api,
            )

            when (result) {
                is ApiResult.Success -> {
                    // The query may have moved on while this page was in flight. Dropping a
                    // stale response is safer than letting it append rows that no longer
                    // match what the rep is looking at.
                    if (searchAtRequestTime != query.value ||
                        filterAtRequestTime != _state.value.filter
                    ) {
                        return@launch
                    }

                    val page = result.data
                    nextSkip = page.nextSkip
                    lastLoadedAt = System.currentTimeMillis()

                    _state.update { previous ->
                        // De-duplicate on id: the server can return an overlapping row if a
                        // call lands while the rep is paging.
                        val combined = if (reset) {
                            page.calls
                        } else {
                            val seen = previous.calls.mapTo(mutableSetOf()) { it.id }
                            previous.calls + page.calls.filterNot { it.id in seen }
                        }

                        previous.copy(
                            calls = combined,
                            total = page.total,
                            hasMore = page.hasMore,
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

    /**
     * Opens the detail sheet.
     *
     * The row already on screen is shown immediately, then replaced by the full record once
     * it arrives — the list is fetched without transcripts, so the sheet has to ask again to
     * show one. Failing that second fetch is not fatal: the sheet keeps the summary it had.
     */
    fun openDetail(call: CallRecord) {
        detailJob?.cancel()
        _detail.value = DetailState(call = call, isLoadingFull = true)

        detailJob = viewModelScope.launch {
            when (val result = repository.fetchCall(call.id)) {
                is ApiResult.Success -> _detail.update { current ->
                    // The rep may have closed the sheet, or opened a different call, while
                    // this was in flight.
                    if (current?.call?.id != call.id) current
                    else current.copy(call = result.data, isLoadingFull = false)
                }

                is ApiResult.Failure -> _detail.update { current ->
                    if (current?.call?.id != call.id) current
                    else current.copy(isLoadingFull = false, error = result.message)
                }
            }
        }
    }

    fun closeDetail() {
        detailJob?.cancel()
        _detail.value = null
    }

    /**
     * Dials the lead this call was with.
     *
     * Goes through [CallTracker] exactly as the leads list does, so the tap is remembered
     * and the call that follows is filed against this lead with `match_source=click_to_call`
     * rather than being looked up by number all over again.
     */
    fun onCallBack(call: CallRecord) {
        val phone = call.leadPhone
        val leadId = call.leadId
        if (phone.isNullOrBlank() || leadId.isNullOrBlank()) {
            _state.update {
                it.copy(
                    error = "This call has no lead to dial",
                    errorDetail = "The CRM record does not carry a phone number for it.",
                )
            }
            return
        }

        viewModelScope.launch {
            val mode = settings.snapshot.first().callMode
            val lead = Lead(id = leadId, name = call.leadName, phoneNumber = phone)
            val launch = callTracker.beginCall(lead, mode)
            _callIntents.send(launch.intent)
        }
    }

    /** The dial intent could not be started — undo the tap so it cannot claim a later call. */
    fun onCallLaunchFailed(reason: String) {
        viewModelScope.launch { callTracker.abandonCall(reason) }
        _state.update { it.copy(error = "Could not open the phone dialler", errorDetail = reason) }
    }

    fun dismissError() = _state.update { it.copy(error = null, errorDetail = null) }

    private companion object {
        const val SEARCH_DEBOUNCE_MILLIS = 350L
        const val STALE_AFTER_MILLIS = 15_000L
    }
}
