package ai.arthax.app.ui.meetings

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ai.arthax.app.call.CallTracker
import ai.arthax.app.data.local.prefs.AppSettings
import ai.arthax.app.data.remote.api.ApiResult
import ai.arthax.app.data.repository.LeadsRepository
import ai.arthax.app.data.repository.MeetingsRepository
import ai.arthax.app.domain.model.Lead
import ai.arthax.app.domain.model.Meeting
import ai.arthax.app.domain.model.MeetingStatus
import ai.arthax.app.domain.model.MeetingType
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

@HiltViewModel
class MeetingsViewModel @Inject constructor(
    private val repository: MeetingsRepository,
    private val leadsRepository: LeadsRepository,
    private val callTracker: CallTracker,
    private val settings: AppSettings,
) : ViewModel() {

    /**
     * The tabs across the top.
     *
     * [UPCOMING] is not a server status — it is the two open statuses asked for together,
     * because a rescheduled meeting is still a meeting the rep has to turn up to. The
     * server filter takes one status at a time, so this one is applied on the client.
     */
    enum class Filter(val label: String, val status: MeetingStatus?) {
        UPCOMING("Upcoming", null),
        COMPLETED("Completed", MeetingStatus.COMPLETED),
        CANCELLED("Cancelled", MeetingStatus.CANCELLED),
        ALL("All", null),
    }

    data class UiState(
        val meetings: List<Meeting> = emptyList(),
        val query: String = "",
        val filter: Filter = Filter.UPCOMING,
        val isLoading: Boolean = true,
        val isRefreshing: Boolean = false,
        val isLoadingMore: Boolean = false,
        val hasMore: Boolean = false,
        val total: Int = 0,
        val error: String? = null,
        val errorDetail: String? = null,
        /** The meeting whose status is being changed, so its row can show a spinner. */
        val updatingId: String? = null,
        val message: String? = null,
    ) {
        val hasLoadedEverything: Boolean
            get() = !hasMore && !isLoading && !isLoadingMore && meetings.isNotEmpty()
    }

    /** State of the "book a meeting" sheet. Null when it is closed. */
    data class BookingState(
        val leads: List<Lead> = emptyList(),
        val isLoadingLeads: Boolean = true,
        val selectedLead: Lead? = null,
        val type: MeetingType = MeetingType.DEMO,
        val scheduledAtMillis: Long,
        val durationMinutes: Int = 30,
        val notes: String = "",
        val meetingLink: String = "",
        val isSaving: Boolean = false,
        val error: String? = null,
    ) {
        val canSave: Boolean get() = selectedLead != null && !isSaving
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _booking = MutableStateFlow<BookingState?>(null)
    val booking: StateFlow<BookingState?> = _booking.asStateFlow()

    private val _callIntents = Channel<Intent>(Channel.BUFFERED)
    val callIntents: Flow<Intent> = _callIntents.receiveAsFlow()

    private val query = MutableStateFlow("")

    private var nextSkip = 0
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

    fun setFilter(filter: Filter) {
        if (_state.value.filter == filter) return
        _state.update { it.copy(filter = filter) }
        load(reset = true, showSpinner = true)
    }

    fun refresh() = load(reset = true, showSpinner = false)

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
            val result = repository.fetchMeetings(
                skip = skip,
                status = filterAtRequestTime.status,
                search = searchAtRequestTime,
            )

            when (result) {
                is ApiResult.Success -> {
                    if (searchAtRequestTime != query.value ||
                        filterAtRequestTime != _state.value.filter
                    ) {
                        return@launch
                    }

                    val page = result.data
                    nextSkip = page.nextSkip
                    lastLoadedAt = System.currentTimeMillis()

                    _state.update { previous ->
                        val combined = if (reset) {
                            page.meetings
                        } else {
                            val seen = previous.meetings.mapTo(mutableSetOf()) { it.id }
                            previous.meetings + page.meetings.filterNot { it.id in seen }
                        }

                        previous.copy(
                            meetings = combined.applyClientFilter(filterAtRequestTime),
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
     * The one filter the server cannot express.
     *
     * "Upcoming" means scheduled *or* rescheduled, and the endpoint takes a single status,
     * so the rows come back unfiltered and are narrowed here. Sorted soonest-first, which
     * is the order a diary is useful in — the server returns newest-created first.
     */
    private fun List<Meeting>.applyClientFilter(filter: MeetingsViewModel.Filter): List<Meeting> =
        when (filter) {
            Filter.UPCOMING -> filter { it.isOpen }.sortedBy { it.scheduledAtMillis ?: Long.MAX_VALUE }
            Filter.ALL -> sortedByDescending { it.scheduledAtMillis ?: 0 }
            else -> this
        }

    /**
     * Moves a meeting to a new status.
     *
     * The row is replaced with the server's own answer rather than patched locally, so the
     * `completed_at` the backend sets is what ends up on screen.
     */
    fun setStatus(meeting: Meeting, status: MeetingStatus) {
        if (meeting.status == status) return

        _state.update { it.copy(updatingId = meeting.id, error = null, errorDetail = null) }

        viewModelScope.launch {
            when (val result = repository.setStatus(meeting.id, status)) {
                is ApiResult.Success -> {
                    val updated = result.data
                    _state.update { previous ->
                        val replaced = previous.meetings.map { if (it.id == updated.id) updated else it }
                        previous.copy(
                            // A meeting that no longer matches the open filter drops off the
                            // list, which is what a rep marking something done expects.
                            meetings = replaced.applyClientFilter(previous.filter),
                            updatingId = null,
                            message = "Marked ${status.label.lowercase()}",
                        )
                    }
                }

                is ApiResult.Failure -> _state.update {
                    it.copy(
                        updatingId = null,
                        error = result.message,
                        errorDetail = result.detail,
                    )
                }
            }
        }
    }

    // ---- Booking -------------------------------------------------------------------

    /** Opens the booking sheet, defaulting to tomorrow morning. */
    fun openBooking(preselectedLead: Lead? = null) {
        _booking.value = BookingState(
            scheduledAtMillis = defaultSlot(),
            selectedLead = preselectedLead,
        )
        loadLeadsForBooking()
    }

    private fun loadLeadsForBooking() {
        viewModelScope.launch {
            when (val result = leadsRepository.fetchLeads(skip = 0)) {
                is ApiResult.Success -> _booking.update {
                    it?.copy(leads = result.data.leads, isLoadingLeads = false)
                }

                is ApiResult.Failure -> _booking.update {
                    it?.copy(
                        isLoadingLeads = false,
                        error = "Could not load your leads: ${result.message}",
                    )
                }
            }
        }
    }

    fun closeBooking() {
        _booking.value = null
    }

    fun onBookingLeadSelected(lead: Lead) = _booking.update { it?.copy(selectedLead = lead, error = null) }

    fun onBookingTypeSelected(type: MeetingType) = _booking.update { it?.copy(type = type) }

    fun onBookingTimeSelected(millis: Long) = _booking.update { it?.copy(scheduledAtMillis = millis) }

    fun onBookingDurationSelected(minutes: Int) = _booking.update { it?.copy(durationMinutes = minutes) }

    fun onBookingNotesChanged(notes: String) = _booking.update { it?.copy(notes = notes) }

    fun onBookingLinkChanged(link: String) = _booking.update { it?.copy(meetingLink = link) }

    fun saveBooking() {
        val current = _booking.value ?: return
        val lead = current.selectedLead ?: return
        if (current.isSaving) return

        _booking.update { it?.copy(isSaving = true, error = null) }

        viewModelScope.launch {
            val result = repository.createMeeting(
                leadId = lead.id,
                type = current.type,
                scheduledAtMillis = current.scheduledAtMillis,
                durationMinutes = current.durationMinutes,
                notes = current.notes,
                meetingLink = current.meetingLink,
            )

            when (result) {
                is ApiResult.Success -> {
                    _booking.value = null
                    _state.update { it.copy(message = "Meeting booked with ${lead.name}") }
                    // Reload rather than splicing the new row in: the server fills the title
                    // and the product/service itself, and the list should show what it holds.
                    refresh()
                }

                is ApiResult.Failure -> _booking.update {
                    it?.copy(isSaving = false, error = result.message)
                }
            }
        }
    }

    /**
     * Dials the lead a meeting is with.
     *
     * Through [CallTracker] like everywhere else, so the call is attributed to this lead.
     */
    fun onCall(meeting: Meeting) {
        val phone = meeting.leadPhone
        val leadId = meeting.leadId
        if (phone.isNullOrBlank() || leadId.isNullOrBlank()) return

        viewModelScope.launch {
            val mode = settings.snapshot.first().callMode
            val lead = Lead(id = leadId, name = meeting.leadName, phoneNumber = phone)
            _callIntents.send(callTracker.beginCall(lead, mode).intent)
        }
    }

    fun onCallLaunchFailed(reason: String) {
        viewModelScope.launch { callTracker.abandonCall(reason) }
        _state.update { it.copy(error = "Could not open the phone dialler", errorDetail = reason) }
    }

    fun dismissError() = _state.update { it.copy(error = null, errorDetail = null) }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    private companion object {
        const val SEARCH_DEBOUNCE_MILLIS = 350L
        const val STALE_AFTER_MILLIS = 15_000L

        /** Tomorrow at 10:00 — a sane default that is never in the past. */
        fun defaultSlot(): Long {
            val calendar = java.util.Calendar.getInstance().apply {
                add(java.util.Calendar.DAY_OF_YEAR, 1)
                set(java.util.Calendar.HOUR_OF_DAY, 10)
                set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }
            return calendar.timeInMillis
        }
    }
}
