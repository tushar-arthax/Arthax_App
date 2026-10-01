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
import ai.arthax.app.data.remote.dto.LeadCreateRequest
import ai.arthax.app.domain.model.LeadOption
import ai.arthax.app.data.repository.CallSyncRepository
import ai.arthax.app.data.repository.LeadsRepository
import ai.arthax.app.domain.model.CallMode
import ai.arthax.app.domain.model.Lead
import ai.arthax.app.push.FollowUpReminders
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
    private val refreshSignal: LeadsRefreshSignal,
    private val followUps: FollowUpReminders,
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
        /** The lead a notification pointed at; drawn with an outline until the rep moves on. */
        val highlightedLeadId: String? = null,
        /** Built from the org's own statuses once they arrive. */
        val filters: List<LeadFilter> = emptyList(),
        /** True until `GET /api/leads/statuses` answers, so the chip row can say so. */
        val isLoadingFilters: Boolean = true,
        /** Set when the statuses could not be loaded, so the row says why instead of being bare. */
        val filterError: String? = null,
        /**
         * The server's or the parser's own words about that failure.
         *
         * Shown rather than kept for the log, because the generic message on its own sent us
         * hunting the wrong end of this once already: the request was answering 200 and the
         * real reason — a field the response did not carry — was readable the whole time.
         */
        val filterErrorDetail: String? = null,
        val filter: LeadFilter = LeadFilter.All,
        /**
         * A search has been typed but its results are not on screen yet.
         *
         * Covers the debounce as well as the request, because from the rep's side those are
         * one wait: the rows under the field are the previous search's until both are done.
         */
        val isSearching: Boolean = false,
        /** Set while a lead detail screen is open on top of the list. */
        val openLeadId: String? = null,
        val message: String? = null,

        /** Non-null while the add-lead sheet is open. */
        val addLead: AddLeadState? = null,
    ) {
        /** True once every page the server has for this filter is on screen. */
        val hasLoadedEverything: Boolean
            get() = !hasMore && !isLoading && !isLoadingMore && leads.isNotEmpty()
    }

    /** The add-lead sheet: the org's sources, plus whatever the last attempt said. */
    data class AddLeadState(
        val sources: List<LeadOption> = emptyList(),
        val isSaving: Boolean = false,
        val error: String? = null,
    )

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
        observeRefreshRequests()
        loadFilters()
        load(reset = true, showSpinner = true)
    }

    /**
     * Builds the chip row from the organisation's own statuses.
     *
     * Starts as All + Junk, which are real filters that work with no server round trip, and
     * gains a chip per status once they arrive. Nothing is invented: a hardcoded status list
     * would show chips that filter to nothing on an org that does not use those words.
     */
    fun loadFilters() {
        _state.update {
            it.copy(
                filters = LeadFilter.build(emptyList()),
                isLoadingFilters = true,
                filterError = null,
                filterErrorDetail = null,
            )
        }

        viewModelScope.launch {
            when (val result = leadsRepository.fetchStatuses()) {
                is ApiResult.Success -> _state.update {
                    it.copy(
                        filters = LeadFilter.build(result.data),
                        isLoadingFilters = false,
                        filterError = null,
                        filterErrorDetail = null,
                    )
                }

                is ApiResult.Failure -> _state.update {
                    it.copy(
                        isLoadingFilters = false,
                        filterError = result.message,
                        filterErrorDetail = result.detail,
                    )
                }
            }
        }
    }

    fun setFilter(filter: LeadFilter) {
        if (_state.value.filter == filter) return
        _state.update { it.copy(filter = filter) }
        load(reset = true, showSpinner = true)
    }

    fun openLead(leadId: String) = _state.update { it.copy(openLeadId = leadId) }

    /**
     * Closes the detail screen.
     *
     * [changed] is true when the rep edited, junked or deleted the lead, in which case the
     * list behind is reloaded — the row they just changed is sitting there stale otherwise.
     */
    fun closeLead(changed: Boolean) {
        _state.update { it.copy(openLeadId = null) }
        if (changed) refresh()
    }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    /**
     * Opens the add-lead sheet.
     *
     * The sources are fetched when the sheet opens rather than with the list, because most
     * sessions never add a lead and the chip row is the only thing that needs them.
     */
    fun openAddLead() {
        _state.update { it.copy(addLead = AddLeadState()) }

        viewModelScope.launch {
            val sources = (leadsRepository.fetchSources() as? ApiResult.Success)?.data.orEmpty()
            _state.update { current ->
                current.addLead?.let { current.copy(addLead = it.copy(sources = sources)) } ?: current
            }
        }
    }

    fun closeAddLead() = _state.update { it.copy(addLead = null) }

    fun createLead(
        name: String,
        phone: String,
        email: String,
        company: String,
        location: String,
        occupation: String,
        source: String?,
        notes: String,
    ) {
        val sheet = _state.value.addLead ?: return
        if (sheet.isSaving) return

        _state.update { it.copy(addLead = sheet.copy(isSaving = true, error = null)) }

        val request = LeadCreateRequest(
            name = name.trim(),
            phone = phone.trim(),
            email = email.trim().takeIf { it.isNotEmpty() },
            company = company.trim().takeIf { it.isNotEmpty() },
            location = location.trim().takeIf { it.isNotEmpty() },
            occupation = occupation.trim().takeIf { it.isNotEmpty() },
            source = source,
            notes = notes.trim().takeIf { it.isNotEmpty() },
        )

        viewModelScope.launch {
            when (val result = leadsRepository.createLead(request)) {
                is ApiResult.Success -> {
                    _state.update {
                        it.copy(addLead = null, message = "Added ${result.data.name}")
                    }
                    // Reloaded rather than spliced in: the server assigns the lead, sets a
                    // default status and may flag it a duplicate, none of which is known here.
                    refresh()
                }

                is ApiResult.Failure -> _state.update { current ->
                    current.addLead?.let {
                        current.copy(addLead = it.copy(isSaving = false, error = result.message))
                    } ?: current
                }
            }
        }
    }

    /** A push said the list changed — a lead was assigned. Reload quietly. */
    private fun observeRefreshRequests() {
        viewModelScope.launch {
            refreshSignal.events.collect { refresh() }
        }
    }

    /**
     * A notification tap that names a lead. There is no per-lead screen, so the surest way
     * to put that lead in front of the rep is to search for its number; the id outlines it.
     */
    fun focusLead(focus: LeadFocus) {
        _state.update { it.copy(highlightedLeadId = focus.leadId) }
        if (focus.search != null) {
            onQueryChanged(focus.search)
        } else {
            refresh()
        }
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
        _state.update { it.copy(query = value, isSearching = value.trim() != it.query.trim()) }
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
        // isRefreshing matters as much as the other two: a pull-to-refresh is a reset that
        // has already put nextSkip back to zero, so appending on top of it would request
        // page one again and show every row twice.
        if (current.isLoadingMore || current.isLoading || current.isRefreshing) return
        if (!current.hasMore) return
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
            val result = leadsRepository.fetchLeads(
                skip = skip,
                search = searchAtRequestTime,
                status = filterAtRequestTime.statusApi,
                // The dialling list never offers a junk lead, whatever status is selected.
                isJunk = false,
                // The browser keeps leads with no number; a lead missing its phone is
                // exactly the one a rep needs to open and fix.
                includeUncallable = true,
            )

            when (result) {
                is ApiResult.Success -> {
                    val page = result.data

                    // The filter may have changed while this page was in flight. Dropping a
                    // stale response is cheaper and safer than letting it append rows that
                    // do not match what the rep is now looking at.
                    if (searchAtRequestTime != query.value ||
                        filterAtRequestTime != _state.value.filter
                    ) {
                        return@launch
                    }

                    nextSkip = page.nextSkip
                    lastLoadedAt = System.currentTimeMillis()

                    // A fresh list is the moment a rep most expects a lead they just added to
                    // pick up the call they made to it earlier. Cheap when nothing is
                    // waiting, and it runs off this screen's own scope so a slow server
                    // cannot hold the list up.
                    if (reset) viewModelScope.launch { runCatching { reconciler.retryUnmatched("leads list refreshed") } }

                    // Only an unfiltered load is the whole list; arming timers from a search
                    // or a status filter would cancel the reminders of every lead it left
                    // out — and a junk page would arm reminders for leads nobody will call.
                    if (searchAtRequestTime.isBlank() && filterAtRequestTime == LeadFilter.All) {
                        val known = if (reset) page.leads else _state.value.leads + page.leads
                        viewModelScope.launch { runCatching { followUps.replan(known) } }
                    }

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
                            isSearching = false,
                        )
                    }
                }

                is ApiResult.Failure -> _state.update {
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        isLoadingMore = false,
                        // Or the field would spin for ever on a search that failed.
                        isSearching = false,
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
