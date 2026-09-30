package ai.arthax.app.ui.meetings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ai.arthax.app.domain.model.Meeting
import ai.arthax.app.domain.model.MeetingStatus
import ai.arthax.app.domain.model.MeetingType
import ai.arthax.app.ui.calls.absoluteDateTime
import ai.arthax.app.ui.calls.relativeTime
import ai.arthax.app.ui.common.EmptyState
import ai.arthax.app.ui.common.ErrorBanner
import ai.arthax.app.ui.common.FullScreenLoading
import ai.arthax.app.ui.common.StatusPill
import ai.arthax.app.ui.theme.statusColors

private const val LOAD_MORE_THRESHOLD = 4

@Composable
fun MeetingsScreen(
    modifier: Modifier = Modifier,
    viewModel: MeetingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val booking by viewModel.booking.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.refreshOnReturn()
    }

    // Confirmations are momentary and non-critical, so a snackbar is right here — unlike
    // the persistent failures the ErrorBanner carries.
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissMessage()
        }
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

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { viewModel.openBooking() },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Book")
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                Spacer(Modifier.height(12.dp))
                Text("Meetings", style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "Demos and follow-ups booked with your leads",
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
                MeetingsViewModel.Filter.entries.forEach { filter ->
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
                state.isLoading -> FullScreenLoading("Loading your meetings")

                state.meetings.isEmpty() && state.query.isNotBlank() -> EmptyState(
                    icon = Icons.Default.Search,
                    title = "No matches",
                    message = "No meeting matches \"${state.query}\".",
                )

                state.meetings.isEmpty() && state.error == null -> EmptyState(
                    icon = Icons.Default.DateRange,
                    title = when (state.filter) {
                        MeetingsViewModel.Filter.UPCOMING -> "Nothing booked"
                        MeetingsViewModel.Filter.COMPLETED -> "No completed meetings"
                        MeetingsViewModel.Filter.CANCELLED -> "No cancelled meetings"
                        MeetingsViewModel.Filter.ALL -> "No meetings yet"
                    },
                    message = "Book a demo or a follow-up and it will appear here.",
                    actionLabel = "Book a meeting",
                    onAction = { viewModel.openBooking() },
                )

                else -> LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 4.dp,
                        // Clears the floating action button, which would otherwise sit on
                        // top of the last row's own actions.
                        bottom = 88.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(state.meetings, key = { it.id }) { meeting ->
                        MeetingCard(
                            meeting = meeting,
                            isUpdating = state.updatingId == meeting.id,
                            onCall = { viewModel.onCall(meeting) },
                            onStatus = { viewModel.setStatus(meeting, it) },
                            onOpenLink = { link ->
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(link))
                                runCatching { context.startActivity(intent) }
                            },
                        )
                    }

                    item(key = "footer") {
                        ListFooter(
                            isLoadingMore = state.isLoadingMore,
                            hasLoadedEverything = state.hasLoadedEverything,
                            shown = state.meetings.size,
                        )
                    }
                }
            }
        }
    }

    booking?.let { sheet ->
        BookMeetingSheet(
            state = sheet,
            onDismiss = viewModel::closeBooking,
            onLeadSelected = viewModel::onBookingLeadSelected,
            onTypeSelected = viewModel::onBookingTypeSelected,
            onTimeSelected = viewModel::onBookingTimeSelected,
            onDurationSelected = viewModel::onBookingDurationSelected,
            onNotesChanged = viewModel::onBookingNotesChanged,
            onLinkChanged = viewModel::onBookingLinkChanged,
            onSave = viewModel::saveBooking,
        )
    }
}

@Composable
private fun ListFooter(isLoadingMore: Boolean, hasLoadedEverything: Boolean, shown: Int) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        when {
            isLoadingMore -> CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
            )

            hasLoadedEverything -> Text(
                text = "$shown meeting(s) shown",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MeetingCard(
    meeting: Meeting,
    isUpdating: Boolean,
    onCall: () -> Unit,
    onStatus: (MeetingStatus) -> Unit,
    onOpenLink: (String) -> Unit,
) {
    val overdue = meeting.isOverdue(System.currentTimeMillis())

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = MaterialTheme.shapes.medium,
        // An overdue meeting carries a warning edge: it is the one row on this screen that
        // needs the rep to do something, and it should not look like the rest.
        border = BorderStroke(
            1.dp,
            if (overdue) {
                statusColors.warning.copy(alpha = 0.55f)
            } else {
                MaterialTheme.colorScheme.outlineVariant
            },
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                DateBadge(meeting)
                Spacer(Modifier.width(12.dp))

                Column(Modifier.weight(1f)) {
                    Text(
                        text = meeting.leadName,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = buildString {
                            append(MeetingType.labelFor(meeting.type))
                            meeting.durationMinutes?.let { append(" · ").append("${it} min") }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(Modifier.width(8.dp))
                MeetingStatusPill(meeting.status, overdue)
            }

            Spacer(Modifier.height(10.dp))
            Text(
                text = meeting.scheduledAtMillis
                    ?.let { "${absoluteDateTime(it)}  ·  ${relativeTime(it)}" }
                    ?: "No date set",
                style = MaterialTheme.typography.bodyMedium,
                color = if (overdue) statusColors.warning else MaterialTheme.colorScheme.onSurface,
            )

            meeting.notes?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(6.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (meeting.isCallable) {
                    TextButton(onClick = onCall) {
                        Icon(Icons.Default.Call, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Call")
                    }
                }

                meeting.meetingLink?.let { link ->
                    TextButton(onClick = { onOpenLink(link) }) {
                        Icon(Icons.Default.DateRange, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Join")
                    }
                }

                Spacer(Modifier.weight(1f))

                if (isUpdating) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    StatusMenu(current = meeting.status, onStatus = onStatus)
                }
            }
        }
    }
}

/**
 * The date, as a block rather than a line.
 *
 * A diary is scanned by date before anything else, so it gets the same position the lead
 * avatar has on the other lists and the eye can run straight down it.
 */
@Composable
private fun DateBadge(meeting: Meeting) {
    val millis = meeting.scheduledAtMillis

    Box(
        modifier = Modifier
            .size(44.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (millis == null) {
            Icon(
                Icons.Default.DateRange,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = dayOf(millis),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = monthOf(millis),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun MeetingStatusPill(status: MeetingStatus, overdue: Boolean) {
    if (overdue) {
        StatusPill(
            text = "Overdue",
            container = statusColors.warningContainer,
            content = statusColors.warning,
        )
        return
    }

    val (container, content) = when (status) {
        MeetingStatus.COMPLETED -> statusColors.successContainer to statusColors.success
        MeetingStatus.CANCELLED, MeetingStatus.NO_SHOW ->
            MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        MeetingStatus.RESCHEDULED -> statusColors.warningContainer to statusColors.warning
        MeetingStatus.SCHEDULED ->
            MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        MeetingStatus.UNKNOWN ->
            MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
    }

    StatusPill(text = status.label, container = container, content = content)
}

@Composable
private fun StatusMenu(current: MeetingStatus, onStatus: (MeetingStatus) -> Unit) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                Icons.Default.MoreVert,
                contentDescription = "Change status",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            MeetingStatus.settable.forEach { status ->
                DropdownMenuItem(
                    text = { Text(status.label) },
                    onClick = {
                        expanded = false
                        onStatus(status)
                    },
                    leadingIcon = if (status == current) {
                        {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    } else {
                        null
                    },
                )
            }
        }
    }
}

private fun dayOf(millis: Long): String =
    java.text.SimpleDateFormat("d", java.util.Locale.getDefault())
        .format(java.util.Date(millis))

private fun monthOf(millis: Long): String =
    java.text.SimpleDateFormat("MMM", java.util.Locale.getDefault())
        .format(java.util.Date(millis))
        .uppercase()
