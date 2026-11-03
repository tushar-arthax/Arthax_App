package ai.arthax.app.ui.notifications

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ai.arthax.app.data.repository.NotificationsRepository.ReadStatus
import ai.arthax.app.domain.model.AppNotification
import ai.arthax.app.push.NotificationCategories
import ai.arthax.app.push.NotificationTarget
import ai.arthax.app.ui.calls.absoluteDateTime
import ai.arthax.app.ui.calls.relativeTime
import ai.arthax.app.ui.common.EmptyState
import ai.arthax.app.ui.common.ErrorBanner
import ai.arthax.app.ui.common.FullScreenLoading
import ai.arthax.app.ui.theme.statusColors
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private const val LOAD_MORE_THRESHOLD = 4

/**
 * The CRM's notifications — the same list as the bell on the web: filter by category,
 * read state, date and text; scroll to load more; tap to open what it is about.
 */
@Composable
fun NotificationsScreen(
    onOpen: (NotificationTarget) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: NotificationsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.refreshOnReturn()
    }

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

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Notifications", style = MaterialTheme.typography.headlineMedium)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = if (state.unreadInRange > 0) {
                                "${state.unreadInRange} unread · follow-ups, deals, meetings and alerts"
                            } else {
                                "Follow-ups, deals, meetings and alerts"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(
                        onClick = viewModel::markAllRead,
                        enabled = state.unreadInView > 0 && !state.isMarkingAll,
                    ) {
                        Text(if (state.category != null || state.range != NotificationDateRange.ANY) "Mark these read" else "Mark all read")
                    }
                }

                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = state.query,
                    onValueChange = viewModel::onQueryChanged,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Search notifications") },
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

            // Category: All, then each kind that has anything in the chosen dates.
            ChipRow {
                FilterChip(
                    selected = state.category == null,
                    onClick = { viewModel.setCategory(null) },
                    label = { Text(withCount("All", state.unreadInRange)) },
                )
                NotificationCategories.ALL.forEach { category ->
                    val (total, unread) = state.counts[category] ?: (0 to 0)
                    // Empty kinds are hidden once the counts are in — except the selected one.
                    val show = state.counts.isEmpty() || total > 0 || state.category == category
                    if (show) {
                        FilterChip(
                            selected = state.category == category,
                            onClick = { viewModel.setCategory(category) },
                            label = { Text(withCount(NotificationCategories.label(category), unread)) },
                        )
                    }
                }
            }

            // Read state, then the date range.
            ChipRow(top = 0.dp) {
                ReadStatus.entries.forEach { status ->
                    FilterChip(
                        selected = state.status == status,
                        onClick = { viewModel.setStatus(status) },
                        label = { Text(status.label) },
                    )
                }
                Spacer(Modifier.width(4.dp))
                NotificationDateRange.entries.forEach { range ->
                    FilterChip(
                        selected = state.range == range,
                        onClick = { viewModel.setRange(range) },
                        label = { Text(range.label) },
                    )
                }
            }

            if (state.isRefreshing) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            when {
                state.isLoading -> FullScreenLoading("Loading notifications")

                state.items.isEmpty() && state.error == null -> EmptyState(
                    icon = if (state.hasFilters) Icons.Default.Search else Icons.Default.Notifications,
                    title = if (state.hasFilters) "Nothing matches" else "No notifications yet",
                    message = if (state.hasFilters) {
                        "Try another category or a wider date range."
                    } else {
                        "Reminders, deals and alerts from the CRM will appear here."
                    },
                    actionLabel = if (state.hasFilters) "Clear filters" else null,
                    onAction = if (state.hasFilters) {
                        { viewModel.clearFilters() }
                    } else {
                        null
                    },
                )

                else -> LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // Grouped under a header per day. The loop variable is not called `item`:
                    // that would hide LazyListScope.item() below.
                    var lastDay: String? = null
                    state.items.forEach { notification ->
                        val day = dayLabel(notification.createdAtMillis)
                        if (day != lastDay) {
                            lastDay = day
                            item(key = "day:$day:${notification.id}") { DayHeader(day) }
                        }
                        item(key = notification.id) {
                            NotificationCard(
                                item = notification,
                                expanded = notification.id in state.expanded,
                                onClick = { viewModel.onOpen(notification)?.let(onOpen) },
                                onMarkRead = { viewModel.markRead(notification) },
                            )
                        }
                    }

                    item(key = "footer") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 20.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            when {
                                state.isLoadingMore -> CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                )

                                state.hasLoadedEverything -> Text(
                                    text = "${state.items.size} of ${state.total} shown",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChipRow(top: androidx.compose.ui.unit.Dp = 10.dp, content: @Composable () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = top, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        content()
    }
}

@Composable
private fun DayHeader(label: String) {
    Text(
        text = label.uppercase(Locale.getDefault()),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
    )
}

@Composable
private fun NotificationCard(
    item: AppNotification,
    expanded: Boolean,
    onClick: () -> Unit,
    onMarkRead: () -> Unit,
) {
    val accent = when {
        item.isUrgent -> MaterialTheme.colorScheme.error
        item.notificationType == "reminder" -> statusColors.warning
        else -> MaterialTheme.colorScheme.primary
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(
            1.dp,
            if (item.isRead) MaterialTheme.colorScheme.outlineVariant else accent.copy(alpha = 0.55f),
        ),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
            Icon(
                imageVector = when {
                    item.isUrgent -> Icons.Default.Warning
                    item.notificationType == "reminder" -> Icons.Default.Notifications
                    else -> Icons.Default.Info
                },
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(12.dp))

            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = if (item.isRead) FontWeight.Normal else FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (!item.isRead) {
                        Spacer(Modifier.width(8.dp))
                        Box(
                            Modifier
                                .size(8.dp)
                                .background(MaterialTheme.colorScheme.primary, CircleShape),
                        )
                    }
                }

                if (item.message.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = item.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = if (expanded) Int.MAX_VALUE else 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = listOfNotNull(
                            NotificationCategories.label(item.category),
                            item.createdAtMillis?.let { relativeTime(it) },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    if (!item.isRead) {
                        TextButton(onClick = onMarkRead) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Mark read", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                if (expanded) {
                    item.createdAtMillis?.let {
                        Text(
                            text = absoluteDateTime(it),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

private val ReadStatus.label: String
    get() = when (this) {
        ReadStatus.ALL -> "All"
        ReadStatus.UNREAD -> "Unread"
        ReadStatus.READ -> "Read"
    }

private fun withCount(label: String, unread: Int): String = if (unread > 0) "$label ($unread)" else label

/** "Today", "Yesterday" or the date, in the phone's own time zone. */
private fun dayLabel(millis: Long?): String {
    if (millis == null) return "Earlier"
    val day = Calendar.getInstance().apply { timeInMillis = millis }
    val today = Calendar.getInstance()
    fun sameDay(a: Calendar, b: Calendar) =
        a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
    if (sameDay(day, today)) return "Today"
    today.add(Calendar.DAY_OF_YEAR, -1)
    if (sameDay(day, today)) return "Yesterday"
    return SimpleDateFormat("EEE, d MMM yyyy", Locale.getDefault()).format(Date(millis))
}
