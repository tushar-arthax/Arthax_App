package ai.arthax.app.ui.leads

import ai.arthax.app.domain.model.CallRecord
import ai.arthax.app.domain.model.LeadTimelineEvent
import ai.arthax.app.ui.theme.OverlineStyle
import ai.arthax.app.ui.theme.journeyColors
import ai.arthax.app.ui.theme.statusColors
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Everything that has happened to this lead, newest first, grouped by day.
 *
 * Built from the **timeline** rather than from the calls list, and that is the whole point
 * of the file. `GET /api/calls/` is scoped to the signed-in rep by `agent_id`, so on a lead
 * two people have worked it shows each of them only their own calls — on a real lead with
 * twenty-six events that meant a history of two rows, while the CRM's web view showed all
 * of them. The timeline is the only complete record.
 *
 * The rep's own calls are still fetched, and still used: a timeline event carries an
 * outcome and a duration but no recording, transcript or AI analysis. Where [calls] holds
 * the call an event refers to — matched on `meta.call_id` — the full row is rendered with
 * everything on it. A colleague's call, which the rep is not allowed to fetch, still
 * appears with who made it and how it went.
 */
@Composable
fun LeadActivitySection(
    events: List<LeadTimelineEvent>,
    calls: List<CallRecord>,
    isLoading: Boolean,
    isLoadingMore: Boolean,
    hasMore: Boolean,
    onLoadMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var filter by remember { mutableStateOf(ActivityFilter.EVERYTHING) }

    // Matched by id so a rep's own call keeps its recording and analysis. Colleagues' calls
    // simply will not be in this map, which is the expected case, not a failure.
    val callsById = remember(calls) { calls.associateBy { it.id } }

    val ordered = remember(events) {
        events.sortedByDescending { it.occurredAtMillis ?: Long.MIN_VALUE }
    }
    val visible = remember(ordered, filter) { ordered.filter(filter::matches) }

    Column(modifier.fillMaxWidth()) {

        Spacer(Modifier.height(22.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "ACTIVITY HISTORY",
                style = OverlineStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (ordered.isNotEmpty()) {
                Text(
                    text = if (ordered.size == 1) "1 entry" else "${ordered.size} entries",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (ordered.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                // Only the filters this lead has something for, so a lead with no follow-ups
                // does not offer a chip that can only ever empty the list.
                ActivityFilter.entries
                    .filter { it == ActivityFilter.EVERYTHING || ordered.any(it::matches) }
                    .forEach { option ->
                        FilterChip(
                            selected = filter == option,
                            onClick = { filter = option },
                            label = {
                                Text(option.label, style = MaterialTheme.typography.labelMedium)
                            },
                        )
                    }
            }
        }

        Spacer(Modifier.height(12.dp))

        when {
            isLoading && ordered.isEmpty() -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text(
                    "Loading history",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            ordered.isEmpty() -> Text(
                "Nothing has happened to this lead yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            visible.isEmpty() -> Text(
                "No ${filter.label.lowercase()} on this lead.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            else -> Column {
                var lastDay: String? = null

                visible.forEach { event ->
                    val day = event.occurredAtMillis?.let(::dayLabel) ?: "Undated"
                    if (day != lastDay) {
                        if (lastDay != null) Spacer(Modifier.height(14.dp))
                        DayHeading(day)
                        lastDay = day
                    }

                    Spacer(Modifier.height(8.dp))
                    ActivityRow(event = event, call = event.callId?.let(callsById::get))
                }

                if (hasMore) {
                    Spacer(Modifier.height(6.dp))
                    TextButton(
                        onClick = onLoadMore,
                        enabled = !isLoadingMore,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (isLoadingMore) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("Loading")
                        } else {
                            Text("Show earlier activity")
                        }
                    }
                }
            }
        }
    }
}

/** The tabs the CRM's own lead page offers, matched on the event vocabulary. */
enum class ActivityFilter(val label: String) {
    EVERYTHING("Everything"),
    CALLS("Calls"),
    FOLLOW_UPS("Follow-ups"),
    NOTES("Notes"),
    STATUS("Status & owner"),
    ;

    fun matches(event: LeadTimelineEvent): Boolean {
        val word = event.type?.lowercase().orEmpty()
        return when (this) {
            EVERYTHING -> true
            CALLS -> event.isCall
            FOLLOW_UPS -> word.contains("follow") || word.contains("meeting")
            NOTES -> word.contains("note")
            STATUS -> word.contains("status") || word.contains("assign") ||
                word.contains("temperature") || word.contains("rating") ||
                word.contains("junk") || word.contains("revived")
        }
    }
}

@Composable
private fun DayHeading(day: String) {
    Spacer(Modifier.height(10.dp))
    Text(
        text = day,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

/**
 * One entry.
 *
 * A call the rep can open is handed to [LeadCallCard], which carries the recording and the
 * analysis. Everything else — including a colleague's call — is this compact row, which
 * says who did what and when without pretending to more detail than the timeline gave.
 */
@Composable
private fun ActivityRow(event: LeadTimelineEvent, call: CallRecord?) {
    if (call != null) {
        LeadCallCard(call = call, heading = event.headline())
        return
    }

    val accent = accentForActivity(event.type)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.small)
            .border(
                BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                MaterialTheme.shapes.small,
            )
            .padding(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .padding(top = 4.dp)
                .size(7.dp)
                .background(accent, CircleShape),
        )
        Spacer(Modifier.width(11.dp))

        Column(Modifier.weight(1f)) {
            Text(
                text = event.headline(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )

            event.occurredAtMillis?.let {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = timeLabel(it),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // The outcome and length of a call nobody on this device can open.
            if (event.isCall) {
                Spacer(Modifier.height(7.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    event.outcome?.let { outcome ->
                        OutcomePill(
                            text = outcome.replace('_', ' '),
                            connected = outcome.equals("connected", ignoreCase = true),
                        )
                    }
                    event.durationSeconds?.let { seconds ->
                        OutcomePill(text = "${seconds / 60}m ${seconds % 60}s", connected = false)
                    }
                }
            }

            event.detailLine()?.let {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun OutcomePill(text: String, connected: Boolean) {
    val tint = if (connected) statusColors.success else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(50))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(50))
            .padding(horizontal = 9.dp, vertical = 3.dp),
    ) {
        Text(text = text, style = MaterialTheme.typography.labelSmall, color = tint)
    }
}

/**
 * The line at the top of an entry.
 *
 * Written as a sentence about a person where there is one, because "Pawar Tushar made a
 * call" is what a rep is scanning for. `actor: null` means the CRM did it by itself, which
 * the API documentation is explicit about, so those say so rather than naming nobody.
 */
private fun LeadTimelineEvent.headline(): String {
    val who = actor?.takeIf { it.isNotBlank() }

    return when {
        isCall && who != null -> "$who made a call"
        isCall -> "Call logged"
        type?.contains("note", ignoreCase = true) == true && who != null -> "$who added a note"
        type?.contains("note", ignoreCase = true) == true -> "Note added"
        who != null -> "$typeLabel · $who"
        else -> typeLabel
    }
}

/**
 * The body of an entry, with anything already in the headline or the pills left out.
 *
 * A call's [text] is built for the journey card and repeats the outcome and the duration,
 * both of which are pills here — so a call shows only the note a rep actually typed.
 */
private fun LeadTimelineEvent.detailLine(): String? {
    if (!isCall) return text?.takeIf { it.isNotBlank() }

    val spoken = text?.substringAfterLast(" · ")?.trim()
    return spoken?.takeIf {
        it.isNotBlank() &&
            !it.equals(outcome?.replace('_', ' '), ignoreCase = true) &&
            !it.matches(Regex("""\d+m \d+s"""))
    }
}

/** Matches the journey rail, so an event is the same colour in both places. */
@Composable
private fun accentForActivity(type: String?): Color {
    val word = type?.lowercase().orEmpty()
    val colors = journeyColors
    return when {
        word.contains("call") -> colors.call
        word.contains("assign") -> colors.assigned
        word.contains("note") || word.contains("message") -> colors.note
        word.contains("follow") || word.contains("meeting") -> colors.scheduled
        word.contains("junk") || word.contains("lost") -> colors.lost
        word.contains("creat") || word.contains("ingest") || word.contains("attribution") ->
            colors.created
        else -> colors.other
    }
}

private fun dayLabel(millis: Long): String =
    SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(millis))

private fun timeLabel(millis: Long): String =
    SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(millis))
