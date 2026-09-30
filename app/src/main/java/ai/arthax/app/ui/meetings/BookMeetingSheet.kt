package ai.arthax.app.ui.meetings

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ai.arthax.app.domain.model.Lead
import ai.arthax.app.domain.model.MeetingType
import ai.arthax.app.ui.calls.absoluteDateTime
import ai.arthax.app.ui.common.ErrorBanner
import ai.arthax.app.ui.theme.OverlineStyle
import java.util.Calendar

/**
 * Books a meeting against a lead.
 *
 * Deliberately short: a lead, a type, a time, a length. Notes and a link are optional and
 * the server fills in the title and the product itself, so asking for them here would be
 * asking the rep to type something that is about to be overwritten.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookMeetingSheet(
    state: MeetingsViewModel.BookingState,
    onDismiss: () -> Unit,
    onLeadSelected: (Lead) -> Unit,
    onTypeSelected: (MeetingType) -> Unit,
    onTimeSelected: (Long) -> Unit,
    onDurationSelected: (Int) -> Unit,
    onNotesChanged: (String) -> Unit,
    onLinkChanged: (String) -> Unit,
    onSave: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current
    var leadQuery by remember { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
        ) {
            Text("Book a meeting", style = MaterialTheme.typography.headlineSmall)

            state.error?.let {
                Spacer(Modifier.height(12.dp))
                ErrorBanner(message = it)
            }

            SheetLabel("Lead")
            when {
                state.isLoadingLeads -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "Loading your leads",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                state.leads.isEmpty() -> Text(
                    "No leads to book against.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                else -> {
                    OutlinedTextField(
                        value = leadQuery,
                        onValueChange = { leadQuery = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Find a lead") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        trailingIcon = {
                            if (leadQuery.isNotEmpty()) {
                                IconButton(onClick = { leadQuery = "" }) {
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
                    )

                    Spacer(Modifier.height(8.dp))

                    // Filtered on the device rather than the server: the sheet already has
                    // the rep's first page in hand, and a request per keystroke inside a
                    // modal is latency the rep feels with nothing to look at.
                    val visible = remember(leadQuery, state.leads) {
                        val needle = leadQuery.trim()
                        if (needle.isEmpty()) {
                            state.leads
                        } else {
                            state.leads.filter {
                                it.name.contains(needle, ignoreCase = true) ||
                                    it.phoneNumber.contains(needle) ||
                                    it.company?.contains(needle, ignoreCase = true) == true
                            }
                        }
                    }

                    // A search that matches nothing needs saying. The list is filtered on
                    // the device against the page already in hand, so "no match" here means
                    // "not on this page" rather than "not in the CRM" — and the rep is told
                    // which, or they will assume the lead is gone.
                    if (visible.isEmpty()) {
                        Text(
                            text = "No lead here matches \"${leadQuery.trim()}\". " +
                                "Only your loaded leads are searched.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                    } else if (leadQuery.isNotBlank()) {
                        Text(
                            text = if (visible.size == 1) "1 match" else "${visible.size} matches",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(6.dp))
                    }

                    // Bounded so the list cannot push the Book button off the sheet.
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 220.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(visible, key = { it.id }) { lead ->
                            LeadOption(
                                lead = lead,
                                selected = state.selectedLead?.id == lead.id,
                                onClick = { onLeadSelected(lead) },
                            )
                        }
                    }
                }
            }

            SheetLabel("Type")
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MeetingType.entries.forEach { type ->
                    FilterChip(
                        selected = state.type == type,
                        onClick = { onTypeSelected(type) },
                        label = { Text(type.label) },
                    )
                }
            }

            SheetLabel("When")
            OutlinedButton(
                onClick = {
                    // The platform pickers rather than Material3's, chained date then time.
                    // They are what the rep's phone already uses everywhere else, and they
                    // handle the 12/24-hour and first-day-of-week settings for free.
                    val calendar = Calendar.getInstance().apply {
                        timeInMillis = state.scheduledAtMillis
                    }
                    DatePickerDialog(
                        context,
                        { _, year, month, day ->
                            TimePickerDialog(
                                context,
                                { _, hour, minute ->
                                    val picked = Calendar.getInstance().apply {
                                        set(year, month, day, hour, minute, 0)
                                        set(Calendar.MILLISECOND, 0)
                                    }
                                    onTimeSelected(picked.timeInMillis)
                                },
                                calendar.get(Calendar.HOUR_OF_DAY),
                                calendar.get(Calendar.MINUTE),
                                android.text.format.DateFormat.is24HourFormat(context),
                            ).show()
                        },
                        calendar.get(Calendar.YEAR),
                        calendar.get(Calendar.MONTH),
                        calendar.get(Calendar.DAY_OF_MONTH),
                    ).apply {
                        // A meeting cannot be booked into the past.
                        datePicker.minDate = System.currentTimeMillis() - DAY_MILLIS
                    }.show()
                },
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small,
            ) {
                Icon(Icons.Default.DateRange, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Text(absoluteDateTime(state.scheduledAtMillis))
            }

            SheetLabel("Length")
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DURATION_OPTIONS.forEach { minutes ->
                    FilterChip(
                        selected = state.durationMinutes == minutes,
                        onClick = { onDurationSelected(minutes) },
                        label = { Text("$minutes min") },
                    )
                }
            }

            SheetLabel("Notes (optional)")
            OutlinedTextField(
                value = state.notes,
                onValueChange = onNotesChanged,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("What is this meeting for?") },
                minLines = 2,
                maxLines = 4,
                shape = MaterialTheme.shapes.small,
            )

            SheetLabel("Meeting link (optional)")
            OutlinedTextField(
                value = state.meetingLink,
                onValueChange = onLinkChanged,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("https://meet.google.com/…") },
                singleLine = true,
                shape = MaterialTheme.shapes.small,
            )

            Spacer(Modifier.height(24.dp))

            Button(
                onClick = onSave,
                enabled = state.canSave,
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small,
            ) {
                if (state.isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text("Booking…")
                } else {
                    Text("Book meeting")
                }
            }

            if (state.selectedLead == null && !state.isLoadingLeads && state.leads.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Choose a lead to book with.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun LeadOption(lead: Lead, selected: Boolean, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(
            1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(
                text = lead.name,
                style = MaterialTheme.typography.titleSmall,
                color = if (selected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = listOfNotNull(lead.company, lead.phoneNumber).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = if (selected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SheetLabel(text: String) {
    Spacer(Modifier.height(22.dp))
    Text(
        text = text.uppercase(),
        style = OverlineStyle,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(10.dp))
}

private val DURATION_OPTIONS = listOf(15, 30, 45, 60, 90)

private const val DAY_MILLIS = 24L * 60 * 60 * 1000
