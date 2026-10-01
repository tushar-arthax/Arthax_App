package ai.arthax.app.ui.leads

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ai.arthax.app.call.CallTracker
import ai.arthax.app.data.local.prefs.AppSettings
import ai.arthax.app.data.remote.api.ApiResult
import ai.arthax.app.ui.common.humanMessage
import ai.arthax.app.core.ApiConfig
import ai.arthax.app.data.remote.dto.LeadUpdateRequest
import ai.arthax.app.data.repository.CallHistoryRepository
import ai.arthax.app.data.repository.LeadsRepository
import ai.arthax.app.domain.model.CallRecord
import ai.arthax.app.domain.model.JunkCategory
import ai.arthax.app.domain.model.Lead
import ai.arthax.app.domain.model.LeadCustomField
import ai.arthax.app.domain.model.LeadOption
import ai.arthax.app.domain.model.LeadTimelineEvent
import ai.arthax.app.domain.model.LeadTemperature
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * One lead, everything about it, and everything a rep can do to it.
 *
 * The call history comes from `GET /api/calls/?lead_id=…` rather than from the
 * `calls_history` array on the lead detail response: that array is documented only as an
 * empty list, so its shape is a guess, while the calls endpoint is already parsed and
 * covered by tests.
 */
@HiltViewModel
class LeadDetailViewModel @Inject constructor(
    private val leadsRepository: LeadsRepository,
    private val callHistoryRepository: CallHistoryRepository,
    private val callTracker: CallTracker,
    private val settings: AppSettings,
) : ViewModel() {

    data class UiState(
        val lead: Lead? = null,
        val calls: List<CallRecord> = emptyList(),
        val isLoading: Boolean = true,
        val isLoadingCalls: Boolean = true,
        val isWorking: Boolean = false,
        val error: String? = null,
        val errorDetail: String? = null,
        val message: String? = null,
        /** Statuses the org has defined, for the edit sheet's picker. */
        val statuses: List<LeadOption> = emptyList(),
        val sources: List<LeadOption> = emptyList(),
        val customFields: List<LeadCustomField> = emptyList(),

        val timeline: List<LeadTimelineEvent> = emptyList(),
        val isLoadingTimeline: Boolean = true,
        /** True while a wider timeline page is in flight. */
        val isLoadingMoreTimeline: Boolean = false,
        /** False once the server returns fewer events than asked for, or the cap is hit. */
        val hasMoreTimeline: Boolean = false,
        /** Why the journey could not be loaded, so the rail says so instead of vanishing. */
        val timelineError: String? = null,
        val timelineErrorDetail: String? = null,

        /** Paging for the lead's own call history. */
        val hasMoreCalls: Boolean = false,
        val isLoadingMoreCalls: Boolean = false,
        /** Set once the lead has been deleted, so the screen can close itself. */
        val deleted: Boolean = false,
        /** True if anything was changed, so the list behind knows to reload. */
        val changed: Boolean = false,
    )

    /** Which sheet is open on top of the detail screen. */
    enum class Sheet { NONE, EDIT, JUNK, FOLLOW_UP }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _sheet = MutableStateFlow(Sheet.NONE)
    val sheet: StateFlow<Sheet> = _sheet.asStateFlow()

    private val _callIntents = Channel<Intent>(Channel.BUFFERED)
    val callIntents: Flow<Intent> = _callIntents.receiveAsFlow()

    private var leadId: String? = null

    /** Cursor into the next, older page of the journey. Null before the first page. */
    private var timelineBefore: String? = null
    private var timelineBeforeId: String? = null

    /**
     * The journey request in flight, if any.
     *
     * Held so a second one replaces it rather than racing it. Without this a burst of taps
     * on Retry — or a reopen while the first page is still coming — puts several identical
     * requests on the wire, and whichever happens to land last wins, which may be the
     * oldest. The screen would then be showing a response to a question it no longer asked.
     */
    private var timelineJob: Job? = null

    /** Server offset for the next page of this lead's calls. */
    private var nextCallSkip = 0

    /**
     * Called once the screen knows which lead it is showing.
     *
     * The lead and its calls are fetched together rather than in sequence: neither depends
     * on the other, and a rep opening a lead should not wait for two round trips end to end.
     */
    fun load(id: String) {
        if (leadId == id && _state.value.lead != null) return
        leadId = id

        timelineBefore = null
        timelineBeforeId = null
        nextCallSkip = 0
        _state.update {
            it.copy(
                isLoading = true,
                isLoadingCalls = true,
                isLoadingTimeline = true,
                timelineError = null,
                timelineErrorDetail = null,
                error = null,
            )
        }

        viewModelScope.launch {
            val leadDeferred = async { leadsRepository.fetchLead(id) }
            val callsDeferred = async {
                callHistoryRepository.fetchCalls(
                    leadId = id,
                    limit = CALL_HISTORY_LIMIT,
                    // A handful of calls, read inline — worth pulling the transcripts with
                    // them rather than a request per call when the rep expands one.
                    includeTranscript = true,
                )
            }

            when (val result = leadDeferred.await()) {
                is ApiResult.Success -> _state.update {
                    it.copy(lead = result.data, isLoading = false)
                }

                is ApiResult.Failure -> _state.update {
                    it.copy(isLoading = false, error = result.message, errorDetail = result.detail)
                }
            }

            when (val result = callsDeferred.await()) {
                is ApiResult.Success -> {
                    nextCallSkip = result.data.nextSkip
                    _state.update {
                        it.copy(
                            calls = result.data.calls,
                            isLoadingCalls = false,
                            hasMoreCalls = result.data.hasMore,
                        )
                    }
                }

                // A history that would not load is not worth an error banner over the whole
                // screen — the lead itself is the point, and the section says so in place.
                is ApiResult.Failure -> _state.update { it.copy(isLoadingCalls = false) }
            }
        }

        loadTimeline(replacing = true)

        // The organisation's pick lists and extra columns. All three fail soft and return
        // empty, so none of them can keep the lead itself off the screen.
        viewModelScope.launch {
            val statuses = async { leadsRepository.fetchStatuses() }
            val sources = async { leadsRepository.fetchSources() }
            val fields = async { runCatching { leadsRepository.fetchCustomFields() }.getOrDefault(emptyList()) }

            _state.update {
                it.copy(
                    statuses = (statuses.await() as? ApiResult.Success)?.data.orEmpty(),
                    sources = (sources.await() as? ApiResult.Success)?.data.orEmpty(),
                    customFields = fields.await(),
                )
            }
        }
    }

    private fun loadTimeline(replacing: Boolean) {
        val id = leadId ?: return

        timelineJob?.cancel()

        _state.update {
            if (replacing) it.copy(isLoadingTimeline = true) else it.copy(isLoadingMoreTimeline = true)
        }

        timelineJob = viewModelScope.launch {
            val result = leadsRepository.fetchTimeline(
                leadId = id,
                limit = ApiConfig.TIMELINE_PAGE_SIZE,
                // A replacing load starts at the top of the journey; a widening one carries
                // on from where the last page ended.
                before = if (replacing) null else timelineBefore,
                beforeId = if (replacing) null else timelineBeforeId,
            )

            when (result) {
                is ApiResult.Success -> {
                    val page = result.data
                    timelineBefore = page.nextBefore
                    timelineBeforeId = page.nextBeforeId

                    _state.update {
                        // Appended, not replaced, when paging on — and de-duplicated on id,
                        // because a cursor page can overlap if an event lands mid-scroll.
                        val combined = if (replacing) {
                            page.events
                        } else {
                            val seen = it.timeline.mapTo(mutableSetOf()) { event -> event.id }
                            it.timeline + page.events.filterNot { event -> event.id in seen }
                        }

                        it.copy(
                            timeline = combined,
                            isLoadingTimeline = false,
                            isLoadingMoreTimeline = false,
                            timelineError = null,
                            timelineErrorDetail = null,
                            // The server's own cursor, capped so a lead with years of
                            // history cannot be pulled down in one sitting.
                            hasMoreTimeline = page.hasMore &&
                                combined.size < ApiConfig.TIMELINE_MAX,
                        )
                    }
                }

                // The rows already on screen are kept on a failed *widening*: the rep asked
                // for more history and losing what they were reading would be a worse
                // answer than not growing the rail.
                is ApiResult.Failure -> _state.update {
                    it.copy(
                        isLoadingTimeline = false,
                        isLoadingMoreTimeline = false,
                        // The documented meaning of the status, not the server's wording:
                        // a reassigned lead and a deleted one both used to surface as
                        // something no rep could act on.
                        timelineError = result.humanMessage(),
                        timelineErrorDetail = null,
                    )
                }
            }
        }
    }

    /** Retry after a failed journey load. */
    fun retryTimeline() = loadTimeline(replacing = true)

    /** Fetches the next, older page of the journey from the server's cursor. */
    fun loadMoreTimeline() {
        val current = _state.value
        if (current.isLoadingMoreTimeline || !current.hasMoreTimeline) return
        loadTimeline(replacing = false)
    }

    /** Appends the next page of this lead's calls. */
    fun loadMoreCalls() {
        val id = leadId ?: return
        val current = _state.value
        if (current.isLoadingMoreCalls || !current.hasMoreCalls) return

        _state.update { it.copy(isLoadingMoreCalls = true) }

        viewModelScope.launch {
            val result = callHistoryRepository.fetchCalls(
                leadId = id,
                skip = nextCallSkip,
                limit = CALL_HISTORY_LIMIT,
                includeTranscript = true,
            )

            when (result) {
                is ApiResult.Success -> {
                    nextCallSkip = result.data.nextSkip
                    _state.update { previous ->
                        val seen = previous.calls.mapTo(mutableSetOf()) { it.id }
                        previous.copy(
                            calls = previous.calls + result.data.calls.filterNot { it.id in seen },
                            isLoadingMoreCalls = false,
                            hasMoreCalls = result.data.hasMore,
                        )
                    }
                }

                is ApiResult.Failure -> _state.update { it.copy(isLoadingMoreCalls = false) }
            }
        }
    }

    fun refresh() {
        val id = leadId ?: return
        leadId = null
        load(id)
    }

    fun openSheet(sheet: Sheet) {
        _sheet.value = sheet
    }

    fun closeSheet() {
        _sheet.value = Sheet.NONE
    }

    fun dismissError() = _state.update { it.copy(error = null, errorDetail = null) }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    /**
     * Dials the lead.
     *
     * Through [CallTracker], the same entry point the leads list uses, so the call that
     * follows is filed against this lead with `match_source=click_to_call` rather than
     * being looked up by number all over again.
     */
    fun onCall() {
        val lead = _state.value.lead ?: return
        if (!lead.isCallable) {
            _state.update { it.copy(error = "This lead has no phone number") }
            return
        }

        viewModelScope.launch {
            val mode = settings.snapshot.first().callMode
            _callIntents.send(callTracker.beginCall(lead, mode).intent)
        }
    }

    fun onCallLaunchFailed(reason: String) {
        viewModelScope.launch { callTracker.abandonCall(reason) }
        _state.update { it.copy(error = "Could not open the phone dialler", errorDetail = reason) }
    }

    fun saveEdit(
        name: String,
        phone: String,
        email: String,
        company: String,
        location: String,
        notes: String,
        status: String?,
        temperature: LeadTemperature,
        rating: Int?,
        customValues: Map<String, String>,
    ) {
        val id = leadId ?: return
        _state.update { it.copy(isWorking = true, error = null) }

        val update = LeadUpdateRequest(
            name = name.trim().takeIf { it.isNotEmpty() },
            phone = phone.trim().takeIf { it.isNotEmpty() },
            email = email.trim().takeIf { it.isNotEmpty() },
            company = company.trim().takeIf { it.isNotEmpty() },
            location = location.trim().takeIf { it.isNotEmpty() },
            notes = notes.trim().takeIf { it.isNotEmpty() },
            status = status,
            temperature = temperature.name.lowercase(),
            rating = rating,
            // Omitted entirely when nothing changed, so a PATCH that only moves the status
            // does not also rewrite every org-defined column.
            customFields = customValues.takeIf { it.isNotEmpty() }?.mapValues { it.value.trim() },
        )

        viewModelScope.launch {
            when (val result = leadsRepository.updateLead(id, update)) {
                is ApiResult.Success -> {
                    _sheet.value = Sheet.NONE
                    _state.update {
                        it.copy(
                            lead = result.data,
                            isWorking = false,
                            changed = true,
                            message = "Lead updated",
                        )
                    }
                }

                is ApiResult.Failure -> _state.update {
                    it.copy(isWorking = false, error = result.message, errorDetail = result.detail)
                }
            }
        }
    }

    fun markJunk(category: JunkCategory, reason: String) {
        val lead = _state.value.lead ?: return
        _state.update { it.copy(isWorking = true, error = null) }

        viewModelScope.launch {
            when (val result = leadsRepository.markJunk(lead.id, lead.name, category, reason)) {
                is ApiResult.Success -> {
                    _sheet.value = Sheet.NONE
                    _state.update { it.copy(isWorking = false, changed = true, message = "Marked as junk") }
                    // Re-read rather than patching locally: marking junk can move the status
                    // and the classification server-side as well as the flag.
                    refresh()
                }

                is ApiResult.Failure -> _state.update {
                    it.copy(isWorking = false, error = result.message, errorDetail = result.detail)
                }
            }
        }
    }

    fun setFollowUp(scheduledAtMillis: Long, note: String) {
        val lead = _state.value.lead ?: return
        _state.update { it.copy(isWorking = true, error = null) }

        viewModelScope.launch {
            when (val result = leadsRepository.setFollowUp(lead.id, lead.name, scheduledAtMillis, note)) {
                is ApiResult.Success -> {
                    _sheet.value = Sheet.NONE
                    _state.update { it.copy(isWorking = false, changed = true, message = "Follow-up set") }
                    refresh()
                }

                is ApiResult.Failure -> _state.update {
                    it.copy(isWorking = false, error = result.message, errorDetail = result.detail)
                }
            }
        }
    }

    fun completeFollowUp(outcome: String, note: String) {
        val lead = _state.value.lead ?: return
        _state.update { it.copy(isWorking = true, error = null) }

        viewModelScope.launch {
            when (val result = leadsRepository.completeFollowUp(lead.id, lead.name, outcome, note)) {
                is ApiResult.Success -> {
                    _state.update {
                        it.copy(isWorking = false, changed = true, message = "Follow-up completed")
                    }
                    refresh()
                }

                is ApiResult.Failure -> _state.update {
                    it.copy(isWorking = false, error = result.message, errorDetail = result.detail)
                }
            }
        }
    }

    fun delete() {
        val lead = _state.value.lead ?: return
        _state.update { it.copy(isWorking = true, error = null) }

        viewModelScope.launch {
            when (val result = leadsRepository.deleteLead(lead.id, lead.name)) {
                is ApiResult.Success -> _state.update {
                    it.copy(isWorking = false, deleted = true, changed = true)
                }

                is ApiResult.Failure -> _state.update {
                    it.copy(isWorking = false, error = result.message, errorDetail = result.detail)
                }
            }
        }
    }

    private companion object {
        /** One page of this lead's calls; the section pages for the rest. */
        const val CALL_HISTORY_LIMIT = 20
    }
}
