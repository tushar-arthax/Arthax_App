package ai.arthax.app.ui.leads

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import ai.arthax.app.domain.model.JunkCategory
import ai.arthax.app.domain.model.Lead
import ai.arthax.app.domain.model.LeadCustomField
import ai.arthax.app.domain.model.LeadOption
import ai.arthax.app.domain.model.LeadTemperature
import ai.arthax.app.ui.calls.absoluteDateTime
import ai.arthax.app.ui.common.ErrorBanner
import ai.arthax.app.ui.theme.OverlineStyle
import java.util.Calendar

/** Edits the fields a rep can reasonably change from a phone. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditLeadSheet(
    lead: Lead,
    statuses: List<LeadOption>,
    customFields: List<LeadCustomField>,
    isSaving: Boolean,
    onDismiss: () -> Unit,
    onSave: (
        name: String,
        phone: String,
        email: String,
        company: String,
        location: String,
        notes: String,
        status: String?,
        temperature: LeadTemperature,
        rating: Int?,
        customValues: Map<String, String>,
    ) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Seeded from the lead and keyed on its id, so reopening the sheet after a save starts
    // from what the server now holds rather than from the last thing typed.
    var name by remember(lead.id) { mutableStateOf(lead.name) }
    var phone by remember(lead.id) { mutableStateOf(lead.phoneNumber) }
    var email by remember(lead.id) { mutableStateOf(lead.email.orEmpty()) }
    var company by remember(lead.id) { mutableStateOf(lead.company.orEmpty()) }
    var location by remember(lead.id) { mutableStateOf(lead.location.orEmpty()) }
    var notes by remember(lead.id) { mutableStateOf(lead.notes.orEmpty()) }
    var status by remember(lead.id) { mutableStateOf(lead.status) }
    var temperature by remember(lead.id) { mutableStateOf(lead.temperature) }
    var rating by remember(lead.id) { mutableStateOf(lead.rating?.toString().orEmpty()) }

    // Seeded from whatever the lead already carries for each org-defined column.
    val customValues = remember(lead.id, customFields) {
        mutableStateMapOf<String, String>().apply {
            customFields.forEach { field -> put(field.name, lead.customFields[field.name].orEmpty()) }
        }
    }

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
            Text("Edit lead", style = MaterialTheme.typography.headlineSmall)

            Field("Name", name) { name = it }
            Field("Phone", phone, keyboard = KeyboardType.Phone) { phone = it }
            Field("Email", email, keyboard = KeyboardType.Email) { email = it }
            Field("Company", company) { company = it }
            Field("Location", location) { location = it }

            // Only shown when the org's statuses are known. Offering a free-text status
            // box would let a rep invent one the CRM does not have.
            if (statuses.isNotEmpty()) {
                SheetLabel("Status")
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    statuses.forEach { option ->
                        FilterChip(
                            selected = status.equals(option.api, ignoreCase = true),
                            onClick = { status = option.api },
                            label = { Text(option.label) },
                        )
                    }
                }
            }

            SheetLabel("Temperature")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LeadTemperature.entries.forEach { option ->
                    FilterChip(
                        selected = temperature == option,
                        onClick = { temperature = option },
                        label = { Text(option.label) },
                    )
                }
            }

            Field("Rating (0-5)", rating, keyboard = KeyboardType.Number) { new ->
                // Clamped as it is typed: the server rejects anything outside 0..5, and
                // finding that out on save costs the rep the whole sheet.
                rating = new.filter(Char::isDigit).take(1).takeIf { it.isEmpty() || it.toInt() <= 5 } ?: rating
            }

            // The organisation's own columns. A `select` offers its values rather than a
            // text box, so a rep cannot type something the CRM will not recognise.
            customFields.forEach { field ->
                SheetLabel(field.label)
                if (field.isSelect) {
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        field.options.forEach { option ->
                            FilterChip(
                                selected = customValues[field.name] == option,
                                onClick = {
                                    // Tapping the selected chip clears it, which is the only
                                    // way to unset an optional field from a chip row.
                                    customValues[field.name] =
                                        if (customValues[field.name] == option) "" else option
                                },
                                label = { Text(option) },
                            )
                        }
                    }
                } else {
                    OutlinedTextField(
                        value = customValues[field.name].orEmpty(),
                        onValueChange = { customValues[field.name] = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = if (field.isNumber) KeyboardType.Number else KeyboardType.Text,
                        ),
                        shape = MaterialTheme.shapes.small,
                    )
                }
            }

            SheetLabel("Notes")
            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                maxLines = 6,
                shape = MaterialTheme.shapes.small,
            )

            Spacer(Modifier.height(24.dp))
            Button(
                onClick = {
                    onSave(
                        name, phone, email, company, location, notes,
                        status, temperature, rating.toIntOrNull(),
                        // Only what actually changed. Sending every column back would
                        // overwrite values another user edited between this sheet opening
                        // and the rep saving.
                        customValues.filter { (key, value) ->
                            value.trim() != lead.customFields[key].orEmpty()
                        },
                    )
                },
                enabled = !isSaving && name.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small,
            ) {
                if (isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text("Saving…")
                } else {
                    Text("Save changes")
                }
            }
        }
    }
}

/**
 * Marks a lead junk.
 *
 * A category is required and picked from a list rather than typed, so the reasons stay
 * consistent enough across a team to be worth reporting on.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarkJunkSheet(
    leadName: String,
    isSaving: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (JunkCategory, String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var category by remember { mutableStateOf(JunkCategory.INVALID_NUMBER) }
    var reason by remember { mutableStateOf("") }

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
            Text("Mark as junk", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(6.dp))
            Text(
                text = "$leadName stops appearing in the dialling list. Calls already " +
                    "logged against them are kept.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SheetLabel("Why")
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                JunkCategory.entries.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { option ->
                            FilterChip(
                                selected = category == option,
                                onClick = { category = option },
                                label = { Text(option.label) },
                            )
                        }
                    }
                }
            }

            SheetLabel("Note (optional)")
            OutlinedTextField(
                value = reason,
                onValueChange = { reason = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Anything worth recording") },
                minLines = 2,
                maxLines = 4,
                shape = MaterialTheme.shapes.small,
            )

            Spacer(Modifier.height(24.dp))
            Button(
                onClick = { onConfirm(category, reason) },
                enabled = !isSaving,
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small,
            ) {
                if (isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text("Saving…")
                } else {
                    Text("Mark as junk")
                }
            }
        }
    }
}

/** Schedules or moves the lead's next follow-up. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FollowUpSheet(
    existingMillis: Long?,
    isSaving: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (Long, String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current

    // An existing follow-up that has already passed is a bad starting point for a new one,
    // so a fresh default is offered instead.
    var whenMillis by remember {
        mutableStateOf(
            existingMillis?.takeIf { it > System.currentTimeMillis() } ?: defaultFollowUpSlot(),
        )
    }
    var note by remember { mutableStateOf("") }

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
            Text(
                text = if (existingMillis == null) "Schedule follow-up" else "Reschedule follow-up",
                style = MaterialTheme.typography.headlineSmall,
            )

            SheetLabel("When")
            OutlinedButton(
                onClick = {
                    val calendar = Calendar.getInstance().apply { timeInMillis = whenMillis }
                    DatePickerDialog(
                        context,
                        { _, year, month, day ->
                            TimePickerDialog(
                                context,
                                { _, hour, minute ->
                                    whenMillis = Calendar.getInstance().apply {
                                        set(year, month, day, hour, minute, 0)
                                        set(Calendar.MILLISECOND, 0)
                                    }.timeInMillis
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
                        datePicker.minDate = System.currentTimeMillis() - DAY_MILLIS
                    }.show()
                },
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small,
            ) {
                Icon(Icons.Default.DateRange, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Text(absoluteDateTime(whenMillis))
            }

            SheetLabel("Note (optional)")
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("What is this follow-up about?") },
                minLines = 2,
                maxLines = 4,
                shape = MaterialTheme.shapes.small,
            )

            Spacer(Modifier.height(24.dp))
            Button(
                onClick = { onConfirm(whenMillis, note) },
                enabled = !isSaving,
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small,
            ) {
                if (isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text("Saving…")
                } else {
                    Text(if (existingMillis == null) "Schedule" else "Reschedule")
                }
            }
        }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    keyboard: KeyboardType = KeyboardType.Text,
    onChange: (String) -> Unit,
) {
    SheetLabel(label)
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        shape = MaterialTheme.shapes.small,
    )
}

@Composable
private fun SheetLabel(text: String) {
    Spacer(Modifier.height(18.dp))
    Text(
        text = text.uppercase(),
        style = OverlineStyle,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
}

/** Tomorrow at 10:00 — never in the past, and inside working hours. */
private fun defaultFollowUpSlot(): Long = Calendar.getInstance().apply {
    add(Calendar.DAY_OF_YEAR, 1)
    set(Calendar.HOUR_OF_DAY, 10)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}.timeInMillis

private const val DAY_MILLIS = 24L * 60 * 60 * 1000

/**
 * Adds a lead.
 *
 * Only name and phone are required, because those are the two a rep has in front of them
 * when they meet someone. Everything else can be filled in from the lead screen later.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddLeadSheet(
    sources: List<LeadOption>,
    isSaving: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onSave: (
        name: String,
        phone: String,
        email: String,
        company: String,
        location: String,
        occupation: String,
        source: String?,
        notes: String,
    ) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var company by remember { mutableStateOf("") }
    var location by remember { mutableStateOf("") }
    var occupation by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var source by remember(sources) { mutableStateOf(sources.firstOrNull { it.isDefault }?.api) }

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
            Text("Add lead", style = MaterialTheme.typography.headlineSmall)

            error?.let {
                Spacer(Modifier.height(12.dp))
                ErrorBanner(message = it)
            }

            Field("Name", name) { name = it }
            Field("Phone", phone, keyboard = KeyboardType.Phone) { phone = it }
            Field("Email", email, keyboard = KeyboardType.Email) { email = it }
            Field("Company", company) { company = it }
            Field("Location", location) { location = it }
            Field("Occupation", occupation) { occupation = it }

            // Only shown when the org's sources are known; offering a free-text source
            // would let a rep invent one the CRM cannot report on.
            if (sources.isNotEmpty()) {
                SheetLabel("Source")
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    sources.forEach { option ->
                        FilterChip(
                            selected = source == option.api,
                            onClick = { source = option.api },
                            label = { Text(option.label) },
                        )
                    }
                }
            }

            SheetLabel("Notes")
            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Anything worth remembering") },
                minLines = 2,
                maxLines = 4,
                shape = MaterialTheme.shapes.small,
            )

            Spacer(Modifier.height(24.dp))
            Button(
                onClick = {
                    onSave(name, phone, email, company, location, occupation, source, notes)
                },
                enabled = !isSaving && name.isNotBlank() && phone.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small,
            ) {
                if (isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text("Adding\u2026")
                } else {
                    Text("Add lead")
                }
            }

            if (name.isBlank() || phone.isBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "A name and a phone number are required.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
