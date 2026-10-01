package ai.arthax.app.ui.leads

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.border
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ai.arthax.app.domain.model.CallRecord
import ai.arthax.app.domain.model.Lead
import ai.arthax.app.domain.model.LeadTemperature
import ai.arthax.app.ui.calls.absoluteDateTime
import ai.arthax.app.ui.calls.relativeTime
import ai.arthax.app.ui.common.ErrorBanner
import ai.arthax.app.ui.common.FullScreenLoading
import ai.arthax.app.ui.common.MetaChip
import ai.arthax.app.ui.common.RecordingPlayer
import ai.arthax.app.ui.common.StatusPill
import ai.arthax.app.ui.theme.OverlineStyle
import ai.arthax.app.ui.theme.statusColors

/**
 * One lead: who they are, what has been done, and everything that can be done next.
 *
 * Opened over the leads list rather than through a nav route. The list's own scroll
 * position, search and filter are state the rep expects to come back to untouched, and a
 * route would rebuild the whole tab around them.
 */
@Composable
fun LeadDetailScreen(
    leadId: String,
    onBack: (changed: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LeadDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val sheet by viewModel.sheet.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(leadId) { viewModel.load(leadId) }

    // Notes the timeline did not report are folded in here rather than in the view model,
    // because it is a presentation concern: both halves are already loaded and neither
    // request changes.
    val journey = remember(state.timeline, state.lead) {
        mergeNotesIntoJourney(state.timeline, state.lead?.noteHistory.orEmpty())
    }

    // The lead is gone; there is nothing left to show.
    LaunchedEffect(state.deleted) {
        if (state.deleted) onBack(true)
    }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissMessage()
        }
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

    BackHandler { onBack(state.changed) }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Scaffold(
            containerColor = Color.Transparent,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { onBack(state.changed) }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                    Text(
                        text = state.lead?.name ?: "Lead",
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            },
        ) { padding ->
            val lead = state.lead

            when {
                state.isLoading && lead == null -> FullScreenLoading(
                    label = "Loading lead",
                    modifier = Modifier.padding(padding),
                )

                lead == null -> Column(
                    Modifier
                        .padding(padding)
                        .padding(16.dp),
                ) {
                    ErrorBanner(
                        message = state.error ?: "This lead could not be loaded",
                        detail = state.errorDetail,
                        onRetry = viewModel::refresh,
                    )
                }

                else -> Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 32.dp),
                ) {
                    if (state.isWorking) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(8.dp))
                    }

                    state.error?.let {
                        ErrorBanner(message = it, detail = state.errorDetail)
                        Spacer(Modifier.height(12.dp))
                    }

                    HeaderCard(
                        lead = lead,
                        onCall = viewModel::onCall,
                        onMessage = {
                            // An SMS intent, not an API call: the backend exposes no
                            // send-message route, so this hands the number to whatever the
                            // rep already uses for texting rather than inventing one.
                            val intent = Intent(
                                Intent.ACTION_SENDTO,
                                Uri.fromParts("smsto", lead.phoneNumber, null),
                            )
                            runCatching { context.startActivity(intent) }
                        },
                        onEdit = { viewModel.openSheet(LeadDetailViewModel.Sheet.EDIT) },
                        onJunk = { viewModel.openSheet(LeadDetailViewModel.Sheet.JUNK) },
                        onDelete = { confirmDelete = true },
                    )

                    Section("Lead info") {
                        InfoRow("Assigned to", lead.assignedToName)
                        InfoRow("Source", lead.sourceLabel)
                        InfoRow("Company", lead.company)
                        InfoRow("Location", lead.location)
                        InfoRow("Occupation", lead.occupation)
                        InfoRow("GST", lead.gstNo)
                        InfoRow("Created", lead.createdAt?.let(::absoluteDateTime))
                        InfoRow(
                            "Last contacted",
                            lead.lastContactedAt?.let { "${absoluteDateTime(it)}  ·  ${relativeTime(it)}" },
                        )
                        InfoRow(
                            "Next follow-up",
                            lead.nextFollowUpAt?.let { "${absoluteDateTime(it)}  ·  ${relativeTime(it)}" },
                        )
                    }

                    FollowUpCard(
                        lead = lead,
                        onSet = { viewModel.openSheet(LeadDetailViewModel.Sheet.FOLLOW_UP) },
                        onComplete = { viewModel.completeFollowUp("connected", "") },
                    )

                    lead.aiClassification?.let { classification ->
                        Section("AI classification") {
                            Text(
                                text = classification.replace('_', ' ').replaceFirstChar { it.uppercase() },
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            lead.aiClassificationReason?.let {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    if (lead.customFields.isNotEmpty()) {
                        Section("More") {
                            // Ordered and labelled the way the organisation defined them in
                            // `GET /api/leads/custom-fields`, with anything the lead carries
                            // that is not in that list appended rather than dropped.
                            val defined = state.customFields.filter { it.name in lead.customFields }
                            defined.forEach { field ->
                                InfoRow(field.label, lead.customFields[field.name])
                            }
                            val definedNames = defined.mapTo(mutableSetOf()) { it.name }
                            lead.customFields
                                .filterKeys { it !in definedNames }
                                .forEach { (key, value) ->
                                    InfoRow(
                                        key.replace('_', ' ').replaceFirstChar { it.uppercase() },
                                        value,
                                    )
                                }
                        }
                    }

                    lead.notes?.let {
                        Section("Notes") {
                            Text(it, style = MaterialTheme.typography.bodyMedium)
                        }
                    }

                    // Above the call and message history, and outside a Section card: the
                    // journey carries its own header and scrolls horizontally, and nesting a
                    // scrolling rail inside a bordered card reads as a box within a box.
                    //
                    // Unconditional. This used to be skipped whenever the rail had nothing
                    // to draw, which meant a timeline that failed to load took the entire
                    // section away with it — the rep saw no journey and no reason for its
                    // absence. The rail now renders its own empty and failed states.
                    Spacer(Modifier.height(24.dp))
                    LeadJourneySection(
                        events = journey,
                        isLoading = state.isLoadingTimeline,
                        isLoadingMore = state.isLoadingMoreTimeline,
                        hasMore = state.hasMoreTimeline,
                        onLoadMore = viewModel::loadMoreTimeline,
                        error = state.timelineError,
                        errorDetail = state.timelineErrorDetail,
                        onRetry = viewModel::retryTimeline,
                    )

                    // Driven by the timeline, not by `state.calls`. The calls list is
                    // scoped to the signed-in rep, so on a lead two people have worked it
                    // showed two rows where the CRM shows twenty-six. The rep's own calls
                    // are still passed in, to put the recording and the AI analysis on the
                    // entries this device is allowed to fetch.
                    LeadActivitySection(
                        events = journey,
                        calls = state.calls,
                        isLoading = state.isLoadingTimeline,
                        isLoadingMore = state.isLoadingMoreTimeline,
                        hasMore = state.hasMoreTimeline,
                        onLoadMore = viewModel::loadMoreTimeline,
                    )
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            icon = { Icon(Icons.Default.Warning, contentDescription = null) },
            title = { Text("Delete this lead?") },
            text = {
                Text(
                    "${state.lead?.name ?: "This lead"} is removed from the CRM for everyone. " +
                        "Calls already logged against them are kept.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    viewModel.delete()
                }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            },
        )
    }

    state.lead?.let { lead ->
        when (sheet) {
            LeadDetailViewModel.Sheet.EDIT -> EditLeadSheet(
                lead = lead,
                statuses = state.statuses,
                customFields = state.customFields,
                isSaving = state.isWorking,
                onDismiss = viewModel::closeSheet,
                onSave = viewModel::saveEdit,
            )

            LeadDetailViewModel.Sheet.JUNK -> MarkJunkSheet(
                leadName = lead.name,
                isSaving = state.isWorking,
                onDismiss = viewModel::closeSheet,
                onConfirm = viewModel::markJunk,
            )

            LeadDetailViewModel.Sheet.FOLLOW_UP -> FollowUpSheet(
                existingMillis = lead.nextFollowUpAt,
                isSaving = state.isWorking,
                onDismiss = viewModel::closeSheet,
                onConfirm = viewModel::setFollowUp,
            )

            LeadDetailViewModel.Sheet.NONE -> Unit
        }
    }
}

@Composable
private fun HeaderCard(
    lead: Lead,
    onCall: () -> Unit,
    onMessage: () -> Unit,
    onEdit: () -> Unit,
    onJunk: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(18.dp)) {

            Row(verticalAlignment = Alignment.Top) {
                LeadMonogram(lead.name)
                Spacer(Modifier.width(14.dp))

                Column(Modifier.weight(1f)) {
                    Text(
                        text = lead.name,
                        style = MaterialTheme.typography.headlineSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    lead.company?.let {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            if (lead.phoneNumber.isNotBlank()) {
                ContactLine(Icons.Default.Call, lead.phoneNumber)
            }
            lead.email?.let {
                Spacer(Modifier.height(10.dp))
                ContactLine(Icons.AutoMirrored.Filled.Send, it)
            }
            lead.location?.let {
                Spacer(Modifier.height(10.dp))
                ContactLine(Icons.Default.Place, it)
            }

            Spacer(Modifier.height(16.dp))

            // Wraps rather than truncating: an org with long status names would otherwise
            // push the temperature pill off the edge on a narrow phone.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                lead.statusLabel?.let {
                    StatusPill(
                        text = it,
                        container = Color.Transparent,
                        content = MaterialTheme.colorScheme.primary,
                        outlined = true,
                    )
                }
                StatusPill(
                    text = lead.temperature.label,
                    container = Color.Transparent,
                    content = when (lead.temperature) {
                        LeadTemperature.HOT -> MaterialTheme.colorScheme.error
                        LeadTemperature.WARM -> statusColors.warning
                        LeadTemperature.COLD -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    outlined = true,
                )
                lead.rating?.takeIf { it > 0 }?.let {
                    StatusPill(
                        text = "$it / 5",
                        container = Color.Transparent,
                        content = MaterialTheme.colorScheme.onSurfaceVariant,
                        outlined = true,
                    )
                }
                if (lead.isJunk) {
                    StatusPill(
                        text = lead.junkCategoryLabel ?: "Junk",
                        container = MaterialTheme.colorScheme.errorContainer,
                        content = MaterialTheme.colorScheme.error,
                        outlined = true,
                    )
                }
                if (lead.isDuplicate) {
                    StatusPill(
                        text = "Duplicate",
                        container = Color.Transparent,
                        content = statusColors.warning,
                        outlined = true,
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            // Call is the job; it gets the filled button and the full width. Everything
            // else is secondary and sits below it as a quiet row of three.
            Button(
                onClick = onCall,
                enabled = lead.isCallable,
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small,
                contentPadding = PaddingValues(vertical = 14.dp),
            ) {
                Icon(Icons.Default.Call, contentDescription = null, modifier = Modifier.size(19.dp))
                Spacer(Modifier.width(10.dp))
                Text("Call now", style = MaterialTheme.typography.labelLarge)
            }

            Spacer(Modifier.height(10.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QuietAction(
                    label = "Message",
                    icon = Icons.AutoMirrored.Filled.Send,
                    enabled = lead.isCallable,
                    onClick = onMessage,
                    modifier = Modifier.weight(1f),
                )
                QuietAction(
                    label = "Edit",
                    icon = Icons.Default.Edit,
                    onClick = onEdit,
                    modifier = Modifier.weight(1f),
                )
                QuietAction(
                    label = "Junk",
                    icon = Icons.Default.Clear,
                    tint = statusColors.warning,
                    enabled = !lead.isJunk,
                    onClick = onJunk,
                    modifier = Modifier.weight(1f),
                )
                QuietAction(
                    label = "Delete",
                    icon = Icons.Default.Delete,
                    tint = MaterialTheme.colorScheme.error,
                    onClick = onDelete,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** The lead's initials, so the header has an anchor before any text is read. */
@Composable
private fun LeadMonogram(name: String) {
    Box(
        modifier = Modifier
            .size(52.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = name.trim()
                .split(Regex("\\s+"))
                .filter { it.isNotBlank() }
                .take(2)
                .mapNotNull { part -> part.firstOrNull { it.isLetterOrDigit() } }
                .joinToString("")
                .uppercase()
                .ifBlank { "?" },
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ContactLine(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(15.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * A secondary action: icon over label, in a bordered tile.
 *
 * Stacked rather than side by side so four of them fit a 400dp screen without the labels
 * shrinking to the point where "Edit" and "Delete" look alike \u2014 which is not a mistake
 * worth risking on a destructive action.
 */
@Composable
private fun QuietAction(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    enabled: Boolean = true,
) {
    val colour = if (enabled) tint else MaterialTheme.colorScheme.outlineVariant

    Column(
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, tint = colour, modifier = Modifier.size(18.dp))
        Spacer(Modifier.height(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = colour,
            maxLines = 1,
        )
    }
}

@Composable
private fun FollowUpCard(lead: Lead, onSet: () -> Unit, onComplete: () -> Unit) {
    val due = lead.nextFollowUpAt
    val overdue = due != null && due < System.currentTimeMillis()

    Section("Follow-up") {
        if (due == null) {
            Text(
                "Nothing scheduled.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                text = "${absoluteDateTime(due)}  ·  ${relativeTime(due)}",
                style = MaterialTheme.typography.bodyMedium,
                color = if (overdue) statusColors.warning else MaterialTheme.colorScheme.onSurface,
            )
            if (overdue) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "This follow-up is overdue.",
                    style = MaterialTheme.typography.bodySmall,
                    color = statusColors.warning,
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onSet, shape = MaterialTheme.shapes.small) {
                Icon(Icons.Default.DateRange, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (due == null) "Schedule" else "Reschedule")
            }
            if (due != null) {
                TextButton(onClick = onComplete) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Mark done")
                }
            }
        }
    }
}

/**
 * One call against this lead.
 *
 * The recording plays inline and the transcript and assessment expand in place, rather than
 * opening the sheet the Calls tab uses: a rep on this screen is working through a lead's
 * history in order, and a modal per call would mean opening and closing one for each.
 */
@Composable
internal fun LeadCallCard(call: CallRecord, heading: String? = null) {
    var showTranscript by remember { mutableStateOf(false) }
    var showAssessment by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(14.dp)) {
            heading?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(8.dp))
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                val colour = if (call.connected) statusColors.success else statusColors.warning
                StatusPill(
                    text = if (call.connected) "Connected" else (call.outcome?.replace('_', ' ') ?: "Not picked"),
                    container = colour.copy(alpha = 0.12f),
                    content = colour,
                    outlined = true,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = call.durationLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.width(8.dp))
                call.callTimeMillis?.let {
                    Text(
                        text = relativeTime(it),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            call.callTimeMillis?.let {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = absoluteDateTime(it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Only when the heading has not already named them.
            call.agentName?.takeIf { heading == null }?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, style = MaterialTheme.typography.titleSmall)
            }

            call.recordingUrl?.let { url ->
                Spacer(Modifier.height(12.dp))
                RecordingPlayer(url = url)
            }

            if (call.hasTranscript) {
                Spacer(Modifier.height(8.dp))
                ExpandLink(
                    label = if (showTranscript) "Hide transcript" else "View transcript",
                    onClick = { showTranscript = !showTranscript },
                )
                if (showTranscript) {
                    Spacer(Modifier.height(8.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small)
                            .padding(12.dp),
                    ) {
                        Text(call.transcript.orEmpty(), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            call.assessment?.takeIf { it.hasAnythingToShow }?.let { assessment ->
                Spacer(Modifier.height(4.dp))
                ExpandLink(
                    label = if (showAssessment) "Hide AI assessment" else "View AI assessment",
                    onClick = { showAssessment = !showAssessment },
                )
                if (showAssessment) {
                    Spacer(Modifier.height(8.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small)
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        assessment.summary?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall)
                        }
                        val facts = buildList {
                            assessment.sentimentScore?.let { add("Sentiment" to "${(it * 100).toInt()}%") }
                            assessment.qualityScore?.let { add("Quality" to "${(it * 100).toInt()}%") }
                            assessment.buyingReadiness?.let { add("Readiness" to it.replace('_', ' ')) }
                            assessment.objection?.let { add("Objection" to it.replace('_', ' ')) }
                        }
                        facts.forEach { (label, value) ->
                            Row {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(value, style = MaterialTheme.typography.titleSmall)
                            }
                        }
                        if (assessment.keyTopics.isNotEmpty()) {
                            Text(
                                text = assessment.keyTopics.joinToString(" · "),
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

@Composable
private fun ExpandLink(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
    )
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Spacer(Modifier.height(24.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = title.uppercase(),
            style = OverlineStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(12.dp))
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant,
        )
    }
    Spacer(Modifier.height(10.dp))
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(16.dp), content = content)
    }
}

/** One label/value line. Renders nothing at all when the value is absent. */
@Composable
private fun InfoRow(label: String, value: String?) {
    if (value.isNullOrBlank()) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(120.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f),
        )
    }
}
