package ai.arthax.app.ui.leads

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ai.arthax.app.call.CallLogReconciler
import ai.arthax.app.call.CallTracker
import ai.arthax.app.data.local.prefs.AppSettings
import ai.arthax.app.data.remote.api.ApiResult
import ai.arthax.app.data.repository.CallSyncRepository
import ai.arthax.app.data.repository.LeadsRepository
import ai.arthax.app.domain.model.CallMode
import ai.arthax.app.domain.model.Lead
import ai.arthax.app.recording.RecordingFinder
import ai.arthax.app.ui.common.AppPermissions
import ai.arthax.app.ui.common.DeviceSetup
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
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

@HiltViewModel
class LeadsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val leadsRepository: LeadsRepository,
    private val syncRepository: CallSyncRepository,
    private val callTracker: CallTracker,
    private val reconciler: CallLogReconciler,
    private val settings: AppSettings,
    private val finder: RecordingFinder,
) : ViewModel() {

    /**
     * A blocking problem with the capture setup, surfaced on the dashboard rather than
     * discovered later in the log. Each of these means calls will be placed but recordings
     * will not be captured — the worst failure mode this app has, because it is silent.
     */
    data class Warning(val message: String, val action: Action) {
        enum class Action { PICK_FOLDER, GRANT_PERMISSIONS, FIX_BATTERY }
    }

    data class UiState(
        val leads: List<Lead> = emptyList(),
        val query: String = "",
        /** First page loading, or a filter change reloading from scratch. */
        val isLoading: Boolean = true,
        /** Pull-to-refresh style reload of page one, with the current list still on screen. */
        val isRefreshing: Boolean = false,
        /** Appending the next page at the bottom of the list. */
        val isLoadingMore: Boolean = false,
        val hasMore: Boolean = false,
        val total: Int = 0,
        val error: String? = null,
        val errorDetail: String? = null,
        val callMode: CallMode = CallMode.DIRECT,
        val warnings: List<Warning> = emptyList(),
        val pendingCalls: Int = 0,
    ) {
        /** True once every page the server has for this filter is on screen. */
        val hasLoadedEverything: Boolean
            get() = !hasMore && !isLoading && !isLoadingMore && leads.isNotEmpty()
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** One-shot dial intents. A Channel, not state, so a config change cannot re-dial. */
    private val _callIntents = Channel<Intent>(Channel.BUFFERED)
    val callIntents: Flow<Intent> = _callIntents.receiveAsFlow()

    private val query = MutableStateFlow("")

    /** Server offset for the next page. Reset to zero whenever the filter changes. */
    private var nextSkip = 0

    /**
     * The in-flight page request. Held so a filter change can cancel a page that is about to
     * append rows belonging to the *previous* search — the classic way a filtered list ends
     * up showing results that do not match it.
     */
    private var loadJob: Job? = null

    /** When the list last came back from the server, for the staleness check on resume. */
    private var lastLoadedAt = 0L

    init {
        observePendingCalls()
        observeQuery()
        load(reset = true, showSpinner = true)
    }

    private fun observePendingCalls() {
        viewModelScope.launch {
            syncRepository.queue.collect { queue ->
                _state.update { it.copy(pendingCalls = queue.count { call -> call.isOutstanding }) }
            }
        }
    }

    /**
     * Search runs on the server so it covers the rep's whole assigned list, not just the
     * pages already loaded. Debounced so typing does not fire a request per keystroke.
     */
    @OptIn(FlowPreview::class)
    private fun observeQuery() {
        viewModelScope.launch {
            query
                .drop(1) // the initial empty value is already covered by the first load
                .debounce(SEARCH_DEBOUNCE_MILLIS)
                .distinctUntilChanged()
                .collect { load(reset = true, showSpinner = true) }
        }
    }

    fun onQueryChanged(value: String) {
        _state.update { it.copy(query = value) }
        query.value = value
    }

    /** Reloads page one, keeping the current rows visible while it runs. */
    fun refresh() = load(reset = true, showSpinner = false)

    /**
     * Brings the list up to date when the rep comes back to the screen.
     *
     * Leads are added, edited and deleted in the CRM by other people all day, so a list left
     * sitting on screen goes stale the moment the phone is put down. This reloads quietly —
     * the current rows stay visible — and only when the last load is old enough to be worth
     * a request, so flicking between tabs does not fire one every time.
     */
    fun refreshOnReturn() {
        if (_state.value.isLoading || _state.value.isRefreshing) return
        if (System.currentTimeMillis() - lastLoadedAt < STALE_AFTER_MILLIS) return
        refresh()
    }

    /**
     * Called as the list approaches its end. Safe to call repeatedly — it is a no-op while a
     * page is already in flight or once everything has been loaded.
     */
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
            when (val result = leadsRepository.fetchLeads(skip = skip, search = searchAtRequestTime)) {
                is ApiResult.Success -> {
                    val page = result.data

                    // The filter may have changed while this page was in flight. Dropping a
                    // stale response is cheaper and safer than letting it append rows that
                    // do not match what the rep is now looking at.
                    if (searchAtRequestTime != query.value) return@launch

                    nextSkip = page.nextSkip
                    lastLoadedAt = System.currentTimeMillis()

                    // A fresh list is the moment a rep most expects a lead they just added to
                    // pick up the call they made to it earlier. Cheap when nothing is
                    // waiting, and it runs off this screen's own scope so a slow server
                    // cannot hold the list up.
                    if (reset) viewModelScope.launch { runCatching { reconciler.retryUnmatched("leads list refreshed") } }

                    _state.update { previous ->
                        // De-duplicate on id: the server can return an overlapping row if a
                        // lead is created while the rep is paging.
                        val combined = if (reset) {
                            page.leads
                        } else {
                            val seen = previous.leads.mapTo(mutableSetOf()) { it.id }
                            previous.leads + page.leads.filterNot { it.id in seen }
                        }

                        previous.copy(
                            leads = combined,
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

    /** Re-checks everything the OS can change behind our back. Called on every resume. */
    fun refreshEnvironment() {
        viewModelScope.launch {
            val snapshot = settings.snapshot.first()
            val warnings = buildList {
                if (AppPermissions.missingRequired(context).isNotEmpty()) {
                    add(
                        Warning(
                            "Call permissions are missing. Calls cannot be tracked until they are granted.",
                            Warning.Action.GRANT_PERMISSIONS,
                        ),
                    )
                }

                val treeUri = snapshot.recordingsTreeUri
                when {
                    treeUri.isNullOrBlank() -> add(
                        Warning(
                            "No recordings folder selected. Recordings will not be uploaded.",
                            Warning.Action.PICK_FOLDER,
                        ),
                    )

                    !finder.hasValidGrant(Uri.parse(treeUri)) -> add(
                        Warning(
                            "Access to your recordings folder was lost. Select it again.",
                            Warning.Action.PICK_FOLDER,
                        ),
                    )
                }

                if (DeviceSetup.isBatteryOptimised(context)) {
                    add(
                        Warning(
                            "Battery optimisation is on for Arthax. Recordings may be missed.",
                            Warning.Action.FIX_BATTERY,
                        ),
                    )
                }
            }

            _state.update { it.copy(callMode = snapshot.callMode, warnings = warnings) }
        }
    }

    fun onCallClicked(lead: Lead) {
        viewModelScope.launch {
            val mode = settings.snapshot.first().callMode
            val launch = callTracker.beginCall(lead, mode)
            _callIntents.send(launch.intent)
        }
    }

    /** The dial intent could not be started — undo the session so it cannot claim a later call. */
    fun onCallLaunchFailed(reason: String) {
        viewModelScope.launch { callTracker.abandonCall(reason) }
        _state.update { it.copy(error = "Could not open the phone dialler", errorDetail = reason) }
    }

    fun dismissError() = _state.update { it.copy(error = null, errorDetail = null) }

    private companion object {
        const val SEARCH_DEBOUNCE_MILLIS = 350L

        /** Short enough to feel live, long enough that tab-flicking is not a request each time. */
        const val STALE_AFTER_MILLIS = 15_000L
    }
}
