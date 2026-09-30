package ai.arthax.app.ui.calls

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ai.arthax.app.domain.model.AnalysisState
import ai.arthax.app.domain.model.CallAssessment
import ai.arthax.app.domain.model.CallRecord
import ai.arthax.app.ui.common.ErrorBanner
import ai.arthax.app.ui.common.RecordingPlayer
import ai.arthax.app.ui.common.StatusPill
import ai.arthax.app.ui.theme.OverlineStyle
import ai.arthax.app.ui.theme.statusColors

/**
 * Everything the CRM knows about one call.
 *
 * Every field the assessment returns is rendered somewhere here. That is deliberate: the
 * analysis is the reason this endpoint is worth calling, and quietly dropping half of it
 * because there was no obvious place to put it is how a screen ends up looking thorough
 * while telling a rep less than the API actually knows.
 *
 * Laid out so it survives a narrow phone. The score tiles are the only side-by-side pair,
 * and they collapse to one column when a score is missing; everything else is a wrapping
 * [FlowRow] or full-width text. Nothing here has a fixed width.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CallDetailSheet(
    detail: CallsViewModel.DetailState,
    onDismiss: () -> Unit,
    onCallBack: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val call = detail.call

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
                .padding(bottom = 40.dp),
        ) {
            Header(call)

            if (detail.isLoadingFull) {
                Spacer(Modifier.height(14.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            detail.error?.let {
                Spacer(Modifier.height(14.dp))
                // Not fatal: the row's own summary is still on screen above.
                ErrorBanner(message = "Could not load the full call", detail = it)
            }

            if (!call.leadPhone.isNullOrBlank()) {
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = onCallBack,
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.small,
                ) {
                    Icon(Icons.Default.Call, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Call back")
                }
            }

            call.recordingUrl?.let { url ->
                SheetSection("Recording") { RecordingPlayer(url = url) }
            }

            call.notes?.let {
                SheetSection("Notes") {
                    Text(it, style = MaterialTheme.typography.bodyMedium)
                }
            }

            when (call.analysis) {
                AnalysisState.IN_PROGRESS -> SheetSection("AI insights") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(15.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "Still being analysed. Check back in a few minutes.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                AnalysisState.FAILED -> SheetSection("AI insights") {
                    Text(
                        "The server could not analyse this call. The recording and transcript, " +
                            "where present, are still below.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = statusColors.warning,
                    )
                }

                AnalysisState.READY, AnalysisState.NONE ->
                    call.assessment?.takeIf { it.hasAnythingToShow }?.let { AssessmentBlock(it) }
            }

            CallFactsSection(call)

            call.transcript?.let {
                SheetSection("Transcript") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant,
                                MaterialTheme.shapes.small,
                            )
                            .padding(14.dp),
                    ) {
                        Text(it, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(call: CallRecord) {
    Text(
        text = call.leadName,
        style = MaterialTheme.typography.headlineSmall,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )

    call.leadPhone?.let {
        Spacer(Modifier.height(3.dp))
        Text(
            text = it,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    Spacer(Modifier.height(12.dp))

    // Wrapping, so a long outcome word plus a direction plus a duration does not run off
    // the edge on a narrow handset.
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val outcomeColour = if (call.connected) statusColors.success else statusColors.warning
        StatusPill(
            text = if (call.connected) "Connected" else (call.outcome?.replace('_', ' ') ?: "Not picked"),
            container = outcomeColour.copy(alpha = 0.12f),
            content = outcomeColour,
            outlined = true,
        )
        StatusPill(
            text = if (call.isInbound) "Incoming" else "Outgoing",
            container = Color.Transparent,
            content = MaterialTheme.colorScheme.onSurfaceVariant,
            outlined = true,
        )
        StatusPill(
            text = call.durationLabel,
            container = Color.Transparent,
            content = MaterialTheme.colorScheme.onSurfaceVariant,
            outlined = true,
        )
    }

    call.callTimeMillis?.let {
        Spacer(Modifier.height(10.dp))
        Text(
            text = "${absoluteDateTime(it)}  ·  ${relativeTime(it)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AssessmentBlock(assessment: CallAssessment) {

    // What the rep should act on, before anything they have to read. The rest of this
    // screen is complete rather than prioritised — every field the analysis returns is
    // somewhere below — and on a call with a full assessment that is four screens of
    // scrolling in which an unhandled objection looks exactly like a keyword.
    HighlightsBlock(assessment)

    // The two scores lead, because they are the only thing here that can be taken in at a
    // glance. Side by side when both exist, full width when only one does.
    if (assessment.sentimentScore != null || assessment.qualityScore != null) {
        SheetSection("Scores") {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                assessment.sentimentScore?.let {
                    ScoreTile("Sentiment", it, Modifier.weight(1f))
                }
                assessment.qualityScore?.let {
                    ScoreTile("Call quality", it, Modifier.weight(1f))
                }
            }
        }
    }

    assessment.summary?.let {
        SheetSection("Summary") {
            Text(it, style = MaterialTheme.typography.bodyMedium)
        }
    }

    // Every single-value verdict the analysis produced, as wrapping tiles. A FlowRow rather
    // than a table: how many of these come back varies per call, and a fixed two-column
    // table leaves a ragged hole whenever one is missing.
    val facts = buildList {
        assessment.sentiment?.let { add("Sentiment" to it.humanise()) }
        assessment.intent?.let { add("Intent" to it.humanise()) }
        assessment.buyerIntent?.let { add("Buyer intent" to it.humanise()) }
        assessment.buyingReadiness?.let { add("Readiness" to it.humanise()) }
        assessment.customerSatisfaction?.let { add("Satisfaction" to it.humanise()) }
        assessment.objection?.let { add("Objection" to it.humanise()) }
        assessment.discoveryQuestions?.let { add("Discovery questions" to it.toString()) }
        assessment.followUpTimeframe?.let { add("Follow up in" to it.humanise()) }
        assessment.suggestedFollowUpMillis?.let { add("Suggested date" to absoluteDate(it)) }
        if (assessment.competitorMentioned) {
            add("Competitor" to (assessment.competitorName ?: "Mentioned"))
        }
    }

    if (facts.isNotEmpty()) {
        SheetSection("Signals") {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                facts.forEach { (label, value) -> FactTile(label, value) }
            }
        }
    }

    // Raised and addressed only mean anything together: an objection raised and *not*
    // addressed is the single most actionable thing on this screen.
    if (assessment.objectionRaised || assessment.objectionAddressed) {
        SheetSection("Objection handling") {
            OutcomeLine(
                label = "Objection raised",
                yes = assessment.objectionRaised,
                goodWhenYes = false,
            )
            Spacer(Modifier.height(10.dp))
            OutcomeLine(
                label = "Objection addressed",
                yes = assessment.objectionAddressed,
                goodWhenYes = true,
            )

            if (assessment.objectionRaised && !assessment.objectionAddressed) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "An objection was raised and not handled on this call.",
                    style = MaterialTheme.typography.bodySmall,
                    color = statusColors.warning,
                )
            }
        }
    }

    if (assessment.keyTopics.isNotEmpty()) {
        SheetSection("Topics") { TagRow(assessment.keyTopics) }
    }

    if (assessment.keywords.isNotEmpty()) {
        SheetSection("Keywords detected") { TagRow(assessment.keywords) }
    }

    assessment.suggestedClassification?.let { classification ->
        SheetSection("Suggested classification") {
            Text(
                text = classification.humanise(),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            assessment.classificationReason?.let {
                Spacer(Modifier.height(6.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (assessment.improvementSuggestions.isNotEmpty()) {
        SheetSection("What to do differently") {
            assessment.improvementSuggestions.forEachIndexed { index, suggestion ->
                if (index > 0) Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.Top) {
                    Box(
                        modifier = Modifier
                            .padding(top = 7.dp)
                            .size(5.dp)
                            .background(MaterialTheme.colorScheme.primary, CircleShape),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(suggestion, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

/**
 * The facts about the call itself, as opposed to what the AI made of it.
 *
 * Kept apart from the assessment so a rep can tell the two sources apart: the call log is
 * what happened, the analysis is an opinion about it.
 */
@Composable
private fun CallFactsSection(call: CallRecord) {
    val lines = buildList {
        call.agentName?.let { add("Agent" to it) }
        call.matchSource?.let { add("Matched by" to it.humanise()) }
        call.performanceFlag?.let { add("Performance" to it.humanise()) }
        call.callbackScheduledAtMillis?.let { add("Callback" to absoluteDateTime(it)) }
    }

    if (lines.isEmpty() && !call.complianceFlag) return

    SheetSection("Call record") {
        lines.forEachIndexed { index, (label, value) ->
            if (index > 0) {
                Spacer(Modifier.height(9.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(9.dp))
            }
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = value,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // Raised on its own rather than as another row: a compliance flag is the one thing
        // here that someone has to act on.
        if (call.complianceFlag) {
            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        MaterialTheme.colorScheme.errorContainer,
                        MaterialTheme.shapes.small,
                    )
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(17.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "Flagged for compliance review",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
    }
}

/**
 * A 0..1 score as a tile with its own bar.
 *
 * Coloured by band rather than always the accent: a quality score of 0.2 drawn in the same
 * confident green as 0.9 reads as an achievement.
 */

/**
 * The few things on this call that change what the rep does next.
 *
 * Deliberately short. Each line is a judgement the analysis already made — this adds no
 * new inference, it only decides what deserves to be above the fold and colours it by
 * whether it is good news or a problem. An empty result renders nothing rather than an
 * empty box, which happens on a short call the model had little to say about.
 */
@Composable
private fun HighlightsBlock(assessment: CallAssessment) {
    val highlights = buildList {
        // The most actionable thing this screen can say, so it goes first.
        if (assessment.objectionRaised && !assessment.objectionAddressed) {
            add(
                Highlight(
                    icon = Icons.Default.Warning,
                    text = assessment.objection
                        ?.let { "Objection not handled: ${it.humanise()}" }
                        ?: "An objection was raised and not handled",
                    tone = Tone.BAD,
                ),
            )
        }

        if (assessment.competitorMentioned) {
            add(
                Highlight(
                    icon = Icons.Default.Warning,
                    text = assessment.competitorName
                        ?.let { "Competitor mentioned: $it" }
                        ?: "A competitor was mentioned",
                    tone = Tone.BAD,
                ),
            )
        }

        assessment.buyingReadiness?.let {
            add(Highlight(Icons.Default.CheckCircle, "Buying readiness: ${it.humanise()}", Tone.NEUTRAL))
        }

        // A date beats a timeframe: "follow up in 2 days" still needs working out.
        val followUp = assessment.suggestedFollowUpMillis?.let { "Follow up by ${absoluteDate(it)}" }
            ?: assessment.followUpTimeframe?.let { "Follow up ${it.humanise().lowercase()}" }
        followUp?.let { add(Highlight(Icons.Default.DateRange, it, Tone.NEUTRAL)) }

        assessment.suggestedClassification?.let {
            add(Highlight(Icons.Default.CheckCircle, "Suggested: ${it.humanise()}", Tone.GOOD))
        }
    }

    if (highlights.isEmpty()) return

    SheetSection("Key points") {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small)
                .border(
                    BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    MaterialTheme.shapes.small,
                )
                .padding(14.dp),
        ) {
            highlights.forEachIndexed { index, highlight ->
                if (index > 0) Spacer(Modifier.height(11.dp))

                val tint = when (highlight.tone) {
                    Tone.GOOD -> statusColors.success
                    Tone.BAD -> statusColors.warning
                    Tone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
                }

                Row(verticalAlignment = Alignment.Top) {
                    Icon(
                        imageVector = highlight.icon,
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier
                            .padding(top = 1.dp)
                            .size(16.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = highlight.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (highlight.tone == Tone.NEUTRAL) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            tint
                        },
                    )
                }
            }
        }
    }
}

private enum class Tone { GOOD, BAD, NEUTRAL }

private data class Highlight(
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val text: String,
    val tone: Tone,
)

@Composable
private fun ScoreTile(label: String, score: Double, modifier: Modifier = Modifier) {
    val tint = when {
        score >= 0.7 -> statusColors.success
        score >= 0.4 -> statusColors.warning
        else -> MaterialTheme.colorScheme.error
    }

    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small)
            .border(
                BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                MaterialTheme.shapes.small,
            )
            .padding(14.dp),
    ) {
        Text(
            text = label.uppercase(),
            style = OverlineStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "${(score * 100).toInt()}%",
            style = MaterialTheme.typography.headlineSmall,
            color = tint,
        )
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = { score.toFloat() },
            modifier = Modifier
                .fillMaxWidth()
                .height(5.dp),
            color = tint,
            trackColor = MaterialTheme.colorScheme.outlineVariant,
            strokeCap = StrokeCap.Round,
            gapSize = 0.dp,
            drawStopIndicator = {},
        )
    }
}

/** One labelled verdict, sized by its content so a row of them wraps naturally. */
@Composable
private fun FactTile(label: String, value: String) {
    Column(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small)
            .border(
                BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                MaterialTheme.shapes.small,
            )
            .padding(horizontal = 12.dp, vertical = 9.dp),
    ) {
        Text(
            text = label.uppercase(),
            style = OverlineStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** A yes/no the rep should read as good or bad, not merely as true or false. */
@Composable
private fun OutcomeLine(label: String, yes: Boolean, goodWhenYes: Boolean) {
    val good = yes == goodWhenYes
    val tint = if (good) statusColors.success else statusColors.warning

    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = if (yes) Icons.Default.CheckCircle else Icons.Default.Clear,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(17.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = if (yes) "Yes" else "No",
            style = MaterialTheme.typography.titleSmall,
            color = tint,
        )
    }
}

@Composable
private fun TagRow(tags: List<String>) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        tags.forEach { tag ->
            Box(
                modifier = Modifier
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(50),
                    )
                    .border(
                        1.dp,
                        MaterialTheme.colorScheme.outlineVariant,
                        RoundedCornerShape(50),
                    )
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(
                    text = tag,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SheetSection(title: String, content: @Composable () -> Unit) {
    Spacer(Modifier.height(26.dp))
    Text(
        text = title.uppercase(),
        style = OverlineStyle,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(10.dp))
    content()
}

/** "buying_intent" -> "Buying intent". The server's vocabulary is snake_case throughout. */
private fun String.humanise(): String =
    trim().replace('_', ' ').replaceFirstChar { it.uppercase() }
