package ai.arthax.app.ui.leads

import android.content.ActivityNotFoundException
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ai.arthax.app.domain.model.CallMode
import ai.arthax.app.domain.model.Lead
import ai.arthax.app.domain.model.LeadTemperature
import ai.arthax.app.ui.common.EmptyState
import ai.arthax.app.ui.common.ErrorBanner
import ai.arthax.app.ui.common.FullScreenLoading
import ai.arthax.app.ui.common.StatusPill
import ai.arthax.app.ui.theme.statusColors
import java.util.concurrent.TimeUnit

/** How many rows from the end to start fetching the next page. */
private const val LOAD_MORE_THRESHOLD = 4

@Composable
fun LeadsScreen(
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LeadsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val listState = rememberLazyListState()

    // On every return to the screen, not just the first composition: permissions and the
    // folder grant can be changed in system settings while the app is away, and the lead
    // list itself is edited in the CRM by other people all day.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.refreshEnvironment()
        viewModel.refreshOnReturn()
    }

    // Fetch the next page slightly before the rep reaches the bottom, so the list keeps
    // moving instead of stalling on a spinner. derivedStateOf keeps this from recomposing
    // the whole screen on every scroll pixel.
    val shouldLoadMore by remember {
        derivedStateOf {
            val layout = listState.layoutInfo
            val lastVisible = layout.visibleItemsInfo.lastOrNull()?.index ?: return@derivedStateOf false
            lastVisible >= layout.totalItemsCount - LOAD_MORE_THRESHOLD
        }
    }

    LaunchedEffect(listState) {
        snapshotFlow { shouldLoadMore }
            .collect { if (it) viewModel.loadMore() }
    }

    // Firing the dial intent is the screen's job, not the ViewModel's — but the failure
    // has to get back to the tracker, or a session would sit open against a call that
    // never happened and claim the next recording.
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

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Leads", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        text = if (state.callMode == CallMode.DIRECT) {
                            "Tap CALL to dial straight away"
                        } else {
                            "Tap CALL to open your dialler"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (state.pendingCalls > 0) {
                    PendingUploadsChip(count = state.pendingCalls)
                }
            }

            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::onQueryChanged,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search name, company or number") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
            )

            state.warnings.forEach { warning ->
                Spacer(Modifier.height(10.dp))
                SetupWarningCard(warning = warning, onFix = onOpenSettings)
            }

            if (state.error != null) {
                Spacer(Modifier.height(10.dp))
                ErrorBanner(
                    message = state.error.orEmpty(),
                    detail = state.errorDetail,
                    onRetry = viewModel::refresh,
                )
            }

            Spacer(Modifier.height(12.dp))
        }

        if (state.isRefreshing) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        when {
            state.isLoading -> FullScreenLoading("Loading your leads")

            state.leads.isEmpty() && state.query.isNotBlank() -> EmptyState(
                icon = Icons.Default.Search,
                title = "No matches",
                message = "No lead matches \"${state.query}\".",
            )

            state.leads.isEmpty() && state.error == null -> EmptyState(
                icon = Icons.Default.Person,
                title = "No leads yet",
                message = "Leads assigned to you will appear here.",
                actionLabel = "Refresh",
                onAction = viewModel::refresh,
            )

            else -> LazyColumn(
                state = listState,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(state.leads, key = { it.id }) { lead ->
                    LeadCard(
                        lead = lead,
                        onCall = { viewModel.onCallClicked(lead) },
                    )
                }

                item(key = "footer") {
                    ListFooter(
                        isLoadingMore = state.isLoadingMore,
                        hasLoadedEverything = state.hasLoadedEverything,
                        shown = state.leads.size,
                        total = state.total,
                    )
                }
            }
        }
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
                    text = "Loading more leads",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            hasLoadedEverything -> Text(
                // Confirms the list really is finished, rather than leaving the rep
                // wondering whether more will appear if they keep scrolling.
                text = if (total > 0) "All $shown of $total leads shown" else "$shown lead(s) shown",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PendingUploadsChip(count: Int) {
    Row(
        modifier = Modifier
            .background(
                MaterialTheme.colorScheme.secondaryContainer,
                RoundedCornerShape(50),
            )
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.Send,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSecondaryContainer,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = if (count == 1) "1 pending" else "$count pending",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

@Composable
private fun SetupWarningCard(
    warning: LeadsViewModel.Warning,
    onFix: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(statusColors.warningContainer, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.Warning,
            contentDescription = null,
            tint = statusColors.warning,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = warning.message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onFix) { Text("Fix") }
    }
}

@Composable
private fun LeadCard(
    lead: Lead,
    onCall: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = lead.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!lead.company.isNullOrBlank()) {
                        Text(
                            text = lead.company,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = lead.phoneNumber,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }

                LeadStatusPill(lead)
            }

            if (!lead.notes.isNullOrBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = lead.notes,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(12.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = lead.lastContactedAt?.let { "Last called ${relativeTime(it)}" }
                        ?: "Not called yet",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )

                Button(
                    onClick = onCall,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                    ),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
                ) {
                    Icon(Icons.Default.Call, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("CALL", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@Composable
private fun LeadStatusPill(lead: Lead) {
    // `status` is free text on this backend ("contacted", "new", whatever the CRM was
    // configured with), so it is shown as-is rather than mapped onto a fixed enum that
    // would silently swallow values we have not seen. `temperature` is a real server enum,
    // so it carries the colour.
    val (container, content) = when (lead.temperature) {
        LeadTemperature.HOT -> MaterialTheme.colorScheme.errorContainer to
            MaterialTheme.colorScheme.onErrorContainer
        LeadTemperature.WARM -> statusColors.warningContainer to statusColors.warning
        LeadTemperature.COLD -> MaterialTheme.colorScheme.secondaryContainer to
            MaterialTheme.colorScheme.onSecondaryContainer
    }

    StatusPill(
        text = lead.statusLabel ?: lead.temperature.label,
        container = container,
        content = content,
    )
}

/** Coarse relative time — a rep needs "2 days ago", never a precise timestamp. */
private fun relativeTime(millis: Long): String {
    val diff = (System.currentTimeMillis() - millis).coerceAtLeast(0)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(diff)
    val hours = TimeUnit.MILLISECONDS.toHours(diff)
    val days = TimeUnit.MILLISECONDS.toDays(diff)

    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "${minutes}m ago"
        hours < 24 -> "${hours}h ago"
        days < 30 -> "${days}d ago"
        else -> "a while ago"
    }
}
