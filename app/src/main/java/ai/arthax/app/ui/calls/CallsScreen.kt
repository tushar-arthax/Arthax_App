package ai.arthax.app.ui.calls

import android.content.ActivityNotFoundException
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ai.arthax.app.domain.model.AnalysisState
import ai.arthax.app.domain.model.CallRecord
import ai.arthax.app.ui.common.EmptyState
import ai.arthax.app.ui.common.ErrorBanner
import ai.arthax.app.ui.common.FullScreenLoading
import ai.arthax.app.ui.common.MetaChip
import ai.arthax.app.ui.common.StatusPill
import ai.arthax.app.ui.theme.statusColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/** How many rows from the end to start fetching the next page. */
private const val LOAD_MORE_THRESHOLD = 4

@Composable
fun CallsScreen(
    modifier: Modifier = Modifier,
    viewModel: CallsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val detail by viewModel.detail.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val listState = rememberLazyListState()

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.refreshOnReturn()
    }

    val shouldLoadMore by remember {
        derivedStateOf {
            val layout = listState.layoutInfo
            val lastVisible = layout.visibleItemsInfo.lastOrNull()?.index ?: return@derivedStateOf false
            lastVisible >= layout.totalItemsCount - LOAD_MORE_THRESHOLD
        }
    }

    LaunchedEffect(listState) {
        snapshotFlow { shouldLoadMore }.collect { if (it) viewModel.loadMore() }
    }

    // Firing the dial intent is the screen's job, but a failure has to reach the tracker or
    // a remembered tap would sit open and claim the next call the rep makes by hand.
    LaunchedEffect(Unit) {
        viewModel.callIntents.collect { intent ->
            try {
                context.startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                viewModel.onCallLaunchFailed("No phone app could handle the call")
            } catch (e: SecurityException) {
                viewModel.onCallLaunchFailed("Permission to place calls was denied")
            }
        }
    }

    Column(modifier = modifier.fillMaxSize()) {

        Column(Modifier.padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(12.dp))
            Text("Calls", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(2.dp))
            Text(
                // "50 of 2265 calls" — how much of the history is in hand, which is the
                // one number that makes an endlessly scrolling list feel finite.
                text = when {
                    state.total > state.calls.size -> "${state.calls.size} of ${state.total} calls"
                    state.calls.isNotEmpty() -> "${state.calls.size} call(s)"
                    else -> "Every call you make, as the CRM has it"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::onQueryChanged,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search by lead name") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                shape = MaterialTheme.shapes.small,
            )

            if (state.error != null) {
                Spacer(Modifier.height(10.dp))
                ErrorBanner(
                    message = state.error.orEmpty(),
                    detail = state.errorDetail,
                    onRetry = viewModel::refresh,
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CallsViewModel.Filter.entries.forEach { filter ->
                FilterChip(
                    selected = state.filter == filter,
                    onClick = { viewModel.setFilter(filter) },
                    label = { Text(filter.label) },
                )
            }
        }

        if (state.isRefreshing) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        when {
            state.isLoading -> FullScreenLoading("Loading your calls")

            state.calls.isEmpty() && state.query.isNotBlank() -> EmptyState(
                icon = Icons.Default.Search,
                title = "No matches",
                message = "No call matches \"${state.query}\".",
            )

            state.calls.isEmpty() && state.error == null -> EmptyState(
                icon = Icons.Default.Call,
                title = "No calls yet",
                message = "Calls appear here once they have been logged to the CRM. " +
                    "A call you just made can take a moment to arrive.",
                actionLabel = "Refresh",
                onAction = viewModel::refresh,
            )

            else -> LazyColumn(
                state = listState,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(state.calls, key = { it.id }) { call ->
                    CallCard(
                        call = call,
                        onClick = { viewModel.openDetail(call) },
                    )
                }

                item(key = "footer") {
                    ListFooter(
                        isLoadingMore = state.isLoadingMore,
                        hasLoadedEverything = state.hasLoadedEverything,
                        shown = state.calls.size,
                        total = state.total,
                    )
                }
            }
        }
    }

    detail?.let { open ->
        CallDetailSheet(
            detail = open,
            onDismiss = viewModel::closeDetail,
            onCallBack = { viewModel.onCallBack(open.call) },
        )
    }
}

@Composable
private fun ListFooter(
    isLoadingMore: Boolean,
    hasLoadedEverything: Boolean,
    shown: Int,
    total: Int,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        when {
            isLoadingMore -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "Loading more calls",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            hasLoadedEverything -> Text(
                text = if (total > 0) "All $shown of $total calls shown" else "$shown call(s) shown",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
@Composable
private fun CallCard(
    call: CallRecord,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(14.dp)) {

            Row(verticalAlignment = Alignment.Top) {
                DirectionArrow(call)
                Spacer(Modifier.width(12.dp))

                Column(Modifier.weight(1f)) {
                    // The lead's name is what a rep scans for, so it is the loudest thing
                    // on the row and everything else is deliberately quieter.
                    Text(
                        text = call.leadName,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    call.leadPhone?.let {
                        Spacer(Modifier.height(1.dp))
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                Spacer(Modifier.width(8.dp))
                OutcomePill(call)
            }

            Spacer(Modifier.height(10.dp))

            // Duration, who made it, and when — the three facts that never vary, on one
            // line so they read as metadata rather than competing with the name above.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(13.dp),
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    text = buildString {
                        append(call.durationLabel)
                        call.agentName?.let { append("  ·  ").append(it) }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                call.callTimeMillis?.let {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = relativeTime(it),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // The AI's read, boxed so it is visibly a quotation of the call rather than
            // something the app is asserting.
            call.assessment?.summary?.let { summary ->
                Spacer(Modifier.height(10.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            MaterialTheme.shapes.small,
                        )
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            val chips = buildList {
                if (call.hasRecording) add("REC" to statusColors.success)
                when (call.analysis) {
                    AnalysisState.READY -> add("AI" to MaterialTheme.colorScheme.primary)
                    AnalysisState.IN_PROGRESS -> add("AI PENDING" to MaterialTheme.colorScheme.onSurfaceVariant)
                    AnalysisState.FAILED -> add("AI FAILED" to statusColors.warning)
                    AnalysisState.NONE -> Unit
                }
                if (call.hasTranscript) add("TRANSCRIPT" to MaterialTheme.colorScheme.onSurfaceVariant)
            }

            if (chips.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    chips.forEach { (label, colour) -> MetaChip(text = label, color = colour) }
                }
            }
        }
    }
}

/**
 * Direction and outcome in one mark.
 *
 * The arrow says which way the call went; the colour says how it ended. Keeping those on
 * separate channels means a rep scanning a long list can find "outgoing, nobody answered"
 * without reading a single word.
 */
@Composable
private fun DirectionArrow(call: CallRecord) {
    val tint = when {
        call.connected -> statusColors.success
        call.isInbound -> MaterialTheme.colorScheme.error
        else -> statusColors.warning
    }

    Box(
        modifier = Modifier
            .size(38.dp)
            .background(tint.copy(alpha = 0.14f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (call.isInbound) {
                Icons.AutoMirrored.Filled.ArrowBack
            } else {
                Icons.AutoMirrored.Filled.ArrowForward
            },
            contentDescription = if (call.isInbound) "Incoming call" else "Outgoing call",
            tint = tint,
            modifier = Modifier
                .size(18.dp)
                // The arrows are horizontal; a quarter turn makes them read as the
                // up-and-out / down-and-in pair every dialler uses.
                .rotate(if (call.isInbound) 45f else -45f),
        )
    }
}

@Composable
private fun OutcomePill(call: CallRecord) {
    val (text, colour) = when {
        call.connected -> "Connected" to statusColors.success
        call.isInbound -> "Missed" to MaterialTheme.colorScheme.error
        else -> (call.outcome?.replace('_', ' ') ?: "Not picked") to statusColors.warning
    }

    StatusPill(
        text = text,
        container = colour.copy(alpha = 0.12f),
        content = colour,
        outlined = true,
    )
}

/** Coarse relative time — a rep needs "2 days ago", never a precise timestamp. */
internal fun relativeTime(millis: Long): String {
    val diff = System.currentTimeMillis() - millis
    if (diff < 0) return upcomingTime(-diff)

    val minutes = TimeUnit.MILLISECONDS.toMinutes(diff)
    val hours = TimeUnit.MILLISECONDS.toHours(diff)
    val days = TimeUnit.MILLISECONDS.toDays(diff)

    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "${minutes}m ago"
        hours < 24 -> "${hours}h ago"
        days < 7 -> "${days}d ago"
        else -> absoluteDate(millis)
    }
}

private fun upcomingTime(untilMillis: Long): String {
    val minutes = TimeUnit.MILLISECONDS.toMinutes(untilMillis)
    val hours = TimeUnit.MILLISECONDS.toHours(untilMillis)
    val days = TimeUnit.MILLISECONDS.toDays(untilMillis)
    return when {
        minutes < 1 -> "now"
        minutes < 60 -> "in ${minutes}m"
        hours < 24 -> "in ${hours}h"
        days < 7 -> "in ${days}d"
        else -> "on ${absoluteDate(System.currentTimeMillis() + untilMillis)}"
    }
}

internal fun absoluteDate(millis: Long): String =
    SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(millis))

internal fun absoluteDateTime(millis: Long): String =
    SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(Date(millis))
