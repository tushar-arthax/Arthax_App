package com.example.arthax.ui.logs

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.arthax.data.local.store.LogEntry
import com.example.arthax.data.local.store.PendingCall
import com.example.arthax.data.local.store.SyncState
import com.example.arthax.domain.model.LogLevel
import com.example.arthax.domain.model.LogStage
import com.example.arthax.ui.common.EmptyState
import com.example.arthax.ui.common.StatusPill
import com.example.arthax.ui.theme.statusColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun LogsScreen(
    modifier: Modifier = Modifier,
    viewModel: LogsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(modifier = modifier.fillMaxSize()) {

        Column(Modifier.padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(12.dp))
            Text("Activity", style = MaterialTheme.typography.headlineSmall)
            Text(
                text = "Everything the app did, and every call waiting to reach the server.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
        }

        SecondaryTabRow(selectedTabIndex = state.tab.ordinal) {
            Tab(
                selected = state.tab == LogsViewModel.Tab.ACTIVITY,
                onClick = { viewModel.selectTab(LogsViewModel.Tab.ACTIVITY) },
                text = { Text("Log") },
            )
            Tab(
                selected = state.tab == LogsViewModel.Tab.CALLS,
                onClick = { viewModel.selectTab(LogsViewModel.Tab.CALLS) },
                text = {
                    Text(if (state.outstandingCalls > 0) "Calls (${state.outstandingCalls})" else "Calls")
                },
            )
        }

        when (state.tab) {
            LogsViewModel.Tab.ACTIVITY -> ActivityTab(state, viewModel)
            LogsViewModel.Tab.CALLS -> CallsTab(state, viewModel)
        }
    }
}

@Composable
private fun ActivityTab(state: LogsViewModel.UiState, viewModel: LogsViewModel) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = state.stageFilter == null && !state.problemsOnly,
                onClick = {
                    viewModel.setStageFilter(null)
                    if (state.problemsOnly) viewModel.toggleProblemsOnly()
                },
                label = { Text("All") },
            )
            FilterChip(
                selected = state.problemsOnly,
                onClick = viewModel::toggleProblemsOnly,
                label = { Text("Problems only") },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.errorContainer,
                ),
            )
            LogStage.entries.forEach { stage ->
                FilterChip(
                    selected = state.stageFilter == stage,
                    onClick = { viewModel.setStageFilter(if (state.stageFilter == stage) null else stage) },
                    label = { Text(stage.label) },
                )
            }
        }

        if (state.visibleLogs.isEmpty()) {
            EmptyState(
                icon = Icons.Default.Info,
                title = "Nothing logged yet",
                message = "Call a lead and the full trace will appear here.",
            )
        } else {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(state.visibleLogs, key = { it.id }) { entry ->
                    LogRow(
                        entry = entry,
                        expanded = state.expandedLogId == entry.id,
                        onClick = { viewModel.toggleExpanded(entry.id) },
                    )
                }
                item {
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = viewModel::clearLogs, modifier = Modifier.fillMaxWidth()) {
                        Text("Clear activity log")
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
}

@Composable
private fun LogRow(entry: LogEntry, expanded: Boolean, onClick: () -> Unit) {
    val accent = when (entry.level) {
        LogLevel.SUCCESS -> statusColors.success
        LogLevel.WARN -> statusColors.warning
        LogLevel.ERROR -> MaterialTheme.colorScheme.error
        LogLevel.INFO -> MaterialTheme.colorScheme.outline
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !entry.detail.isNullOrBlank(), onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = RoundedCornerShape(10.dp),
    ) {
        Row(Modifier.padding(12.dp)) {
            Box(
                modifier = Modifier
                    .padding(top = 5.dp)
                    .size(8.dp)
                    .background(accent, CircleShape),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = entry.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = "${formatTime(entry.timestamp)} · ${entry.stage.label}" +
                        (entry.leadName?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                AnimatedVisibility(visible = expanded && !entry.detail.isNullOrBlank()) {
                    Column {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = entry.detail.orEmpty(),
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                                .padding(10.dp),
                        )
                    }
                }

                if (!entry.detail.isNullOrBlank() && !expanded) {
                    Text(
                        text = "Tap for details",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@Composable
private fun CallsTab(state: LogsViewModel.UiState, viewModel: LogsViewModel) {
    if (state.calls.isEmpty()) {
        EmptyState(
            icon = Icons.Default.CheckCircle,
            title = "No calls queued",
            message = "Calls appear here after you hang up, and clear once the server has them.",
        )
        return
    }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(state.calls, key = { it.id }) { call ->
            CallRow(
                call = call,
                onRetry = { viewModel.retry(call.id) },
                onDiscard = { viewModel.discard(call.id) },
            )
        }
    }
}

@Composable
private fun CallRow(call: PendingCall, onRetry: () -> Unit, onDiscard: () -> Unit) {
    val (label, container, content) = when (call.state) {
        SyncState.PENDING_CALL_RECORD -> Triple(
            "Waiting to send",
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
        )
        SyncState.PENDING_UPLOAD -> Triple(
            "Uploading audio",
            statusColors.warningContainer,
            statusColors.warning,
        )
        SyncState.DONE -> Triple("Sent", statusColors.successContainer, statusColors.success)
        SyncState.FAILED -> Triple(
            "Failed",
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(call.leadName, style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = buildString {
                            append(formatTime(call.endedAt))
                            append(" · ")
                            append(if (call.connected) "${call.durationSeconds}s call" else "not answered")
                            if (call.hasRecording) append(" · ${call.sizeBytes / 1024} KB")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StatusPill(text = label, container = container, content = content)
            }

            if (call.connected && !call.hasRecording) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "No recording was found for this call — the call itself is still logged.",
                    style = MaterialTheme.typography.bodySmall,
                    color = statusColors.warning,
                )
            }

            if (!call.lastError.isNullOrBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = call.lastError,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            if (call.attempts > 0 && call.state != SyncState.DONE) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "${call.attempts} attempt(s) so far",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (call.state == SyncState.FAILED) {
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onRetry) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Retry")
                    }
                    TextButton(onClick = onDiscard) {
                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Discard")
                    }
                }
            }

            if (call.state == SyncState.DONE) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = statusColors.success,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (call.hasRecording || call.recordingUrl != null) {
                            "Call and recording stored"
                        } else {
                            "Call logged to the CRM"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * Cached per locale rather than a single static field: a rep switching the phone language
 * mid-session should see timestamps follow, and rebuilding the formatter for every row
 * while scrolling is wasteful.
 */
private var cachedLocale: Locale? = null
private var cachedFormat: SimpleDateFormat? = null

private fun formatTime(millis: Long): String {
    val locale = Locale.getDefault()
    val format = cachedFormat?.takeIf { cachedLocale == locale }
        ?: SimpleDateFormat("dd MMM, HH:mm:ss", locale).also {
            cachedFormat = it
            cachedLocale = locale
        }
    return format.format(Date(millis))
}
