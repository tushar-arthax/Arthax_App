package ai.arthax.app.ui.leads

import android.content.ActivityNotFoundException
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.IconButton
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
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

/** A notification tap pointing at one lead: outline it by id, find it by search. */
data class LeadFocus(
    val leadId: String?,
    val search: String?,
    val nonce: Long,
)

@Composable
fun LeadsScreen(
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    focus: LeadFocus? = null,
    viewModel: LeadsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val listState = rememberLazyListState()

    LaunchedEffect(focus?.nonce) {
        focus?.let(viewModel::focusLead)
    }

    // Once the highlighted lead is on the list, bring it into view.
    LaunchedEffect(state.highlightedLeadId, state.leads) {
        val id = state.highlightedLeadId ?: return@LaunchedEffect
        val index = state.leads.indexOfFirst { it.id == id }
        if (index >= 0) listState.animateScrollToItem(index)
    }

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

    // The detail screen is drawn over the list rather than navigated to, so the rep comes
    // back to their scroll position, search and filter exactly as they left them.
    state.openLeadId?.let { openId ->
        LeadDetailScreen(
            leadId = openId,
            onBack = viewModel::closeLead,
            modifier = modifier,
        )
        return
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {

            Column(Modifier.padding(horizontal = 16.dp)) {
                Spacer(Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Leads", style = MaterialTheme.typography.headlineMedium)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            // "50 of 365 leads" — how much of the list is in hand, which is what
                            // makes an endlessly scrolling list feel finite.
                            text = when {
                                state.total > state.leads.size -> "${state.leads.size} of ${state.total} leads"
                                state.leads.isNotEmpty() -> "${state.leads.size} lead(s)"
                                state.callMode == CallMode.DIRECT -> "Tap CALL to dial straight away"
                                else -> "Tap CALL to open your dialler"
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

                // The search is debounced and served by the server, so between the last
                // keystroke and the new rows there is a moment where the list underneath is
                // still the *old* result. Saying so in the field itself — and offering the
                // way out of a search in the same place — is what stops that moment reading
                // as "it ignored me".
                OutlinedTextField(
                    value = state.query,
                    onValueChange = viewModel::onQueryChanged,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Search name, company or number") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        when {
                            state.isSearching -> CircularProgressIndicator(
                                modifier = Modifier.size(17.dp),
                                strokeWidth = 2.dp,
                            )

                            state.query.isNotEmpty() -> IconButton(
                                onClick = { viewModel.onQueryChanged("") },
                            ) {
                                Icon(
                                    Icons.Default.Clear,
                                    contentDescription = "Clear the search",
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    },
                    singleLine = true,
                    shape = MaterialTheme.shapes.small,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                )

                // What the search actually found, so an empty-looking list is explained
                // rather than just empty.
                if (state.query.isNotBlank() && !state.isLoading) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = when (state.total) {
                            0 -> "No leads match \"${state.query.trim()}\""
                            1 -> "1 lead matches \"${state.query.trim()}\""
                            else -> "${state.total} leads match \"${state.query.trim()}\""
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(Modifier.height(10.dp))

                // Built from the organisation's own statuses, so an org that has invented
                // "Follow up pending" gets a chip for it without an app release.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    state.filters.forEach { filter ->
                        FilterChip(
                            selected = state.filter == filter,
                            onClick = { viewModel.setFilter(filter) },
                            label = { Text(filter.label) },
                            leadingIcon = if (state.filter == filter) {
                                {
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = null,
                                        modifier = Modifier.size(15.dp),
                                    )
                                }
                            } else {
                                null
                            },
                        )
                    }

                    // The org's own statuses arrive a moment after the list. Saying so beats
                    // a row that silently grows two seconds later.
                    if (state.isLoadingFilters) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(start = 2.dp),
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(13.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "Loading statuses",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    // A row with nothing on it but All is either an org with no statuses or
                    // a request that failed, and those must not look the same.
                    state.filterError?.let { message ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(start = 2.dp),
                        ) {
                            Icon(
                                Icons.Default.Warning,
                                contentDescription = null,
                                tint = statusColors.warning,
                                modifier = Modifier.size(14.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                // The reason, not just the fact. "Statuses unavailable" on
                                // its own is indistinguishable between a dead connection and
                                // a response the app could not read, and those are fixed in
                                // completely different places.
                                text = listOfNotNull(message, state.filterErrorDetail)
                                    .distinct()
                                    .joinToString(" — "),
                                style = MaterialTheme.typography.bodySmall,
                                color = statusColors.warning,
                            )
                            Spacer(Modifier.width(4.dp))
                            TextButton(
                                onClick = viewModel::loadFilters,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                            ) {
                                Text("Retry", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }

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
                    title = if (state.filter == LeadFilter.All) {
                        "No leads yet"
                    } else {
                        "Nothing under ${state.filter.label}"
                    },
                    message = if (state.filter == LeadFilter.All) {
                        "Leads assigned to you will appear here."
                    } else {
                        "No lead currently sits in this bucket."
                    },
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
                            highlighted = lead.id == state.highlightedLeadId,
                            onCall = { viewModel.onCallClicked(lead) },
                            onOpen = { viewModel.openLead(lead.id) },
                        )
                    }

                    item(key = "footer") {
                        ListFooter(
                            isLoadingMore = state.isLoadingMore,
                            hasLoadedEverything = state.hasLoadedEverything,
                            shown = state.leads.size,
                            total = state.total,
                        )
                        // Clears the floating button, which would otherwise sit on the last row.
                        Spacer(Modifier.height(72.dp))
                    }
                }
            }
        }

        ExtendedFloatingActionButton(
            onClick = viewModel::openAddLead,
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(20.dp),
        ) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Add lead")
        }
    }

    state.addLead?.let { sheet ->
        AddLeadSheet(
            sources = sheet.sources,
            isSaving = sheet.isSaving,
            error = sheet.error,
            onDismiss = viewModel::closeAddLead,
            onSave = viewModel::createLead,
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
            Icons.AutoMirrored.Filled.Send,
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
    highlighted: Boolean,
    onCall: () -> Unit,
    onOpen: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = MaterialTheme.shapes.medium,
        // The lead a notification pointed at, so it stands out from the rows around it.
        border = if (highlighted) {
            BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        } else {
            BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        },
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = lead.name,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(1.dp))
                    Text(
                        text = lead.phoneNumber.ifBlank { "No phone number" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(Modifier.width(8.dp))
                Column(horizontalAlignment = Alignment.End) {
                    LeadStatusPill(lead)
                    if (lead.isJunk) {
                        Spacer(Modifier.height(6.dp))
                        StatusPill(
                            text = "Junk",
                            container = MaterialTheme.colorScheme.errorContainer,
                            content = MaterialTheme.colorScheme.error,
                            outlined = true,
                        )
                    }
                }
            }

            // Company, then whatever the notes say. A rep recognises a lead by one or the
            // other, and the list is far easier to scan with a line of context per row.
            val subtitle = lead.company ?: lead.notes
            if (!subtitle.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            val meta = buildList {
                lead.lastContactedAt?.let { add("Last called ${relativeTime(it)}") }
                lead.nextFollowUpAt?.let { add("Follow-up ${relativeTime(it)}") }
            }
            if (meta.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = meta.joinToString("  ·  "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (lead.isCallable) {
                Spacer(Modifier.height(14.dp))
                // The one action on the row. Outlined rather than filled: the row itself is
                // already tappable, and a solid primary button per row would make a list of
                // forty leads a wall of accent colour.
                OutlinedButton(
                    onClick = onCall,
                    shape = MaterialTheme.shapes.small,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.primary,
                    ),
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
                ) {
                    Icon(Icons.Default.Call, contentDescription = null, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Call now", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@Composable
private fun LeadAvatar(name: String) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initialsOf(name),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Up to two initials from the lead's name; "?" when there is nothing usable to take. */
private fun initialsOf(name: String): String =
    name.trim()
        .split(Regex("\\s+"))
        .filter { it.isNotBlank() }
        .take(2)
        .mapNotNull { part -> part.firstOrNull { it.isLetterOrDigit() } }
        .joinToString("")
        .uppercase()
        .ifBlank { "?" }

@Composable
private fun LeadStatusPill(lead: Lead) {
    // `status` is free text on this backend — an org defines its own — so it is shown as
    // given rather than mapped onto a fixed enum that would swallow anything unexpected.
    // The colour is keyed off the few words that mean the same thing everywhere; anything
    // else falls back to the lead's temperature, which is a real server enum.
    val word = lead.status?.trim()?.lowercase().orEmpty()
    val colour = when {
        word.contains("won") || word.contains("qualified") -> statusColors.success
        word.contains("lost") || word.contains("reject") -> MaterialTheme.colorScheme.error
        word.contains("contact") || word.contains("follow") -> statusColors.warning
        word == "new" -> MaterialTheme.colorScheme.primary
        else -> when (lead.temperature) {
            LeadTemperature.HOT -> MaterialTheme.colorScheme.error
            LeadTemperature.WARM -> statusColors.warning
            LeadTemperature.COLD -> MaterialTheme.colorScheme.onSurfaceVariant
        }
    }

    StatusPill(
        text = lead.statusLabel ?: lead.temperature.label,
        container = colour.copy(alpha = 0.12f),
        content = colour,
        outlined = true,
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
