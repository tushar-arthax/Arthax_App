package com.example.arthax.ui.logs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.arthax.data.local.store.LogEntry
import com.example.arthax.data.local.store.PendingCall
import com.example.arthax.data.repository.CallSyncRepository
import com.example.arthax.data.repository.EventLogger
import com.example.arthax.domain.model.LogLevel
import com.example.arthax.domain.model.LogStage
import com.example.arthax.work.WorkScheduler
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
    }

    private val filters = MutableStateFlow(Filters())
    private val expandedLogId = MutableStateFlow<String?>(null)

    val state: StateFlow<UiState> = combine(
        eventLogger.entries,
        syncRepository.queue,
        filters,
        expandedLogId,
    ) { logs, calls, filter, expanded ->
        UiState(filters = filter, logs = logs, calls = calls, expandedLogId = expanded)
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
