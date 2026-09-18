package ai.arthax.app.ui.logs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ai.arthax.app.data.local.store.LogEntry
import ai.arthax.app.data.local.store.PendingCall
import ai.arthax.app.data.local.store.UnmatchedCallStore
import ai.arthax.app.data.repository.CallSyncRepository
import ai.arthax.app.data.repository.EventLogger
import ai.arthax.app.domain.model.LogLevel
import ai.arthax.app.domain.model.LogStage
import ai.arthax.app.work.WorkScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LogsViewModel @Inject constructor(
    private val eventLogger: EventLogger,
    private val syncRepository: CallSyncRepository,
    private val unmatched: UnmatchedCallStore,
    private val workScheduler: WorkScheduler,
) : ViewModel() {

    enum class Tab { ACTIVITY, CALLS }

    data class Filters(
        val tab: Tab = Tab.ACTIVITY,
        val stage: LogStage? = null,
        val problemsOnly: Boolean = false,
    )

    data class UiState(
        val filters: Filters = Filters(),
        val logs: List<LogEntry> = emptyList(),
        val calls: List<PendingCall> = emptyList(),
        /** Calls the CRM did not recognise yet; only a count is shown, never a number. */
        val waitingForLead: Int = 0,
        val expandedLogId: String? = null,
    ) {
        val tab: Tab get() = filters.tab
        val stageFilter: LogStage? get() = filters.stage
        val problemsOnly: Boolean get() = filters.problemsOnly

        val visibleLogs: List<LogEntry>
            get() = logs.filter { entry ->
                (filters.stage == null || entry.stage == filters.stage) &&
                    (!filters.problemsOnly || entry.level == LogLevel.ERROR || entry.level == LogLevel.WARN)
            }

        val outstandingCalls: Int get() = calls.count { it.isOutstanding }

        /** Recordings waiting for the rep's decision, shown in their own section. */
        val reviewCalls: List<PendingCall> get() = calls.filter { it.needsReview }

        val otherCalls: List<PendingCall> get() = calls.filterNot { it.needsReview }
    }

    private val filters = MutableStateFlow(Filters())
    private val expandedLogId = MutableStateFlow<String?>(null)

    val state: StateFlow<UiState> = combine(
        eventLogger.entries,
        syncRepository.queue,
        unmatched.items,
        filters,
        expandedLogId,
    ) { logs, calls, waiting, filter, expanded ->
        UiState(
            filters = filter,
            logs = logs,
            calls = calls,
            waitingForLead = waiting.size,
            expandedLogId = expanded,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = UiState(),
    )

    fun selectTab(tab: Tab) = filters.update { it.copy(tab = tab) }

    fun setStageFilter(stage: LogStage?) = filters.update { it.copy(stage = stage) }

    fun toggleProblemsOnly() = filters.update { it.copy(problemsOnly = !it.problemsOnly) }

    fun toggleExpanded(id: String) = expandedLogId.update { if (it == id) null else id }

    /** Rep-initiated retry: reset the row, then restart its work immediately. */
    fun retry(id: String) {
        viewModelScope.launch {
            syncRepository.requeue(id)
            workScheduler.forceSync(id)
        }
    }

    /** The rep has looked at a held recording and wants it sent after all. */
    fun uploadAnyway(id: String) {
        viewModelScope.launch {
            syncRepository.uploadAnyway(id)
            workScheduler.forceSync(id)
        }
    }

    /** The held file is not this call's. It is deleted; the call stays logged. */
    fun notThisCall(id: String) {
        viewModelScope.launch {
            syncRepository.notThisCall(id)
            // Only a call that never reached the CRM has anything left to send.
            if (syncRepository.queue.value.any { it.id == id && it.isOutstanding }) {
                workScheduler.forceSync(id)
            }
        }
    }

    /** Give up on a call for good — removes the row and deletes its local recording. */
    fun discard(id: String) {
        viewModelScope.launch {
            workScheduler.cancelSync(id)
            syncRepository.discard(id)
        }
    }

    fun clearLogs() {
        viewModelScope.launch { eventLogger.clear() }
    }
}
