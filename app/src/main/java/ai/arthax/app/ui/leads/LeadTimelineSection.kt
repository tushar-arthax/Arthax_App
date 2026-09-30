package ai.arthax.app.ui.leads

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ai.arthax.app.domain.model.LeadNote
import ai.arthax.app.domain.model.LeadTimelineEvent
import ai.arthax.app.ui.calls.absoluteDateTime
import ai.arthax.app.ui.theme.OverlineStyle
import ai.arthax.app.ui.theme.journeyColors
import kotlinx.coroutines.launch

/**
 * The lead's journey, left to right, oldest first.
 *
 * Horizontal rather than a vertical list on purpose: a journey is about *sequence* — what
 * happened, then what happened next — and reading it along one line makes the order and the
 * gaps obvious in a way a stack of cards does not. It also keeps a long history from
 * pushing the call and message history off the bottom of the screen.
 *
 * Nothing renders when the route returns nothing. Its schema is documented only as
 * `"string"`, so an empty result may mean "no events" *or* "the shape was not what this
 * build expects" — and an empty rail states neither of those as fact.
 */
@Composable
fun LeadJourneySection(
    events: List<LeadTimelineEvent>,
    isLoading: Boolean,
    isLoadingMore: Boolean,
    hasMore: Boolean,
    onLoadMore: () -> Unit,
    modifier: Modifier = Modifier,
    error: String? = null,
    errorDetail: String? = null,
    onRetry: () -> Unit = {},
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // Oldest first: a journey is read in the direction it happened. The route returns
    // newest first, which is right for a feed and backwards for this.
    val ordered = remember(events) {
        events.sortedBy { it.occurredAtMillis ?: Long.MAX_VALUE }
    }

    val canScrollBack by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }
    val canScrollOn by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last < ordered.lastIndex
        }
    }

    Column(modifier.fillMaxWidth()) {

        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape),
            )
            Spacer(Modifier.width(7.dp))
            Text(
                text = "ACTION JOURNEY",
                style = OverlineStyle,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )

            if (ordered.isNotEmpty()) {
                Spacer(Modifier.width(7.dp))
                Box(
                    modifier = Modifier
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant,
                            RoundedCornerShape(50),
                        )
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(50))
                        .padding(horizontal = 7.dp, vertical = 2.dp),
                ) {
                    // Just the count once the row is tight: the word "actions" is already
                    // above it, and the badge is the first thing to overflow on a small
                    // phone once the paddles and the title have taken their share.
                    Text(
                        text = "${ordered.size}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }

            Spacer(Modifier.weight(1f))

            // Paddle buttons for the rail. They are a convenience, not the only way to
            // move it — the row scrolls by drag as well, which is what most reps will do.
            if (ordered.size > 1) {
                ScrollPaddle(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    description = "Earlier",
                    enabled = canScrollBack,
                ) {
                    scope.launch {
                        listState.animateScrollToItem(
                            (listState.firstVisibleItemIndex - 1).coerceAtLeast(0),
                        )
                    }
                }
                ScrollPaddle(
                    icon = Icons.AutoMirrored.Filled.ArrowForward,
                    description = "Later",
                    enabled = canScrollOn,
                ) {
                    scope.launch {
                        listState.animateScrollToItem(
                            (listState.firstVisibleItemIndex + 1).coerceAtMost(ordered.lastIndex),
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        if (isLoading) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(15.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text(
                    "Loading journey",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Column
        }

        // A failed journey used to render as nothing at all — the section simply was not
        // there, indistinguishable from a lead with no history. Both of those now say which
        // they are, in the rep's own words rather than in the log.
        if (error != null && events.isEmpty()) {
            JourneyNotice(
                icon = Icons.Default.Warning,
                tint = MaterialTheme.colorScheme.error,
                message = listOfNotNull(error, errorDetail).distinct().joinToString(" — "),
                onRetry = onRetry,
            )
            return@Column
        }

        if (events.isEmpty()) {
            JourneyNotice(
                icon = Icons.Default.Info,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                message = "Nothing has happened to this lead yet. " +
                    "Calls, notes and status changes will appear here.",
                onRetry = null,
            )
            return@Column
        }

        // The card is sized from the width actually available rather than fixed, so the
        // rail shows a little over two stops on a small phone and rather more on a large
        // one — and always lands mid-card, which is what tells the rep it scrolls.
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val cardWidth = (maxWidth * CARD_WIDTH_FRACTION)
                .coerceIn(CARD_WIDTH_MIN, CARD_WIDTH_MAX)

            LazyRow(
                state = listState,
                modifier = Modifier.fillMaxWidth(),
                // A little room past the last card so it does not sit flush against the
                // edge when the rail is scrolled to its end.
                contentPadding = PaddingValues(end = 8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                itemsIndexed(ordered, key = { _, event -> event.id }) { index, event ->
                    JourneyNode(
                        event = event,
                        isLast = index == ordered.lastIndex,
                        cardWidth = cardWidth,
                    )
                }

                if (hasMore) {
                    item(key = "more") {
                        Box(
                            modifier = Modifier
                                .width(cardWidth)
                                .heightIn(min = 118.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            TextButton(onClick = onLoadMore, enabled = !isLoadingMore) {
                                if (isLoadingMore) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(13.dp),
                                        strokeWidth = 2.dp,
                                    )
                                } else {
                                    Text("Earlier", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
        }

        // How far along the rail the rep is. A journey can run to a dozen stops and the
        // cards give no clue how many are off-screen, which is the one thing a horizontal
        // list has to say out loud — a vertical list gets it from the system scrollbar.
        if (ordered.size > 1) {
            Spacer(Modifier.height(10.dp))
            RailScrollBar(listState = listState, itemCount = ordered.size)
        }

        // The shape of the whole journey, plus the hint that there is more of it sideways;
        // a horizontal list gives no affordance of its own on a touch screen. The legend is
        // the first thing dropped when there is no room for both — the hint is the useful
        // half, and two competing lines on one row is what made this look cramped.
        if (ordered.size > 1) {
            Spacer(Modifier.height(8.dp))
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                // Captured here: inside the Row, `maxWidth` resolves against the row scope.
                val roomForLegend = maxWidth >= LEGEND_MIN_WIDTH

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (roomForLegend) {
                        Text(
                            text = "Creation → Dials → Now",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        Spacer(Modifier.weight(1f))
                    }

                    Text(
                        text = "Swipe for more →",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** An empty or failed rail: one line saying which, and a way out of the failed one. */
@Composable
private fun JourneyNotice(
    icon: ImageVector,
    tint: Color,
    message: String,
    onRetry: (() -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.small)
            .border(
                BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                MaterialTheme.shapes.small,
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (onRetry != null) {
            Spacer(Modifier.width(6.dp))
            TextButton(
                onClick = onRetry,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
            ) {
                Text("Retry", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}


/**
 * The journey, with the lead's own notes folded in.
 *
 * The timeline route reports notes as `note_added` events, so normally this adds nothing.
 * It matters when it does not: the route is newer than the notes themselves, an older note
 * has no event behind it, and a rep whose timeline comes back short would otherwise see a
 * journey with the notes missing and no sign any were ever written.
 *
 * A note already reported is recognised by the event's own `meta.note_id` rather than by
 * comparing text, so a note and its event never appear as two stops.
 */
internal fun mergeNotesIntoJourney(
    events: List<LeadTimelineEvent>,
    notes: List<LeadNote>,
): List<LeadTimelineEvent> {
    if (notes.isEmpty()) return events

    val alreadyReported = events.mapNotNullTo(mutableSetOf()) { it.noteId }
    val ids = events.mapTo(mutableSetOf()) { it.id }

    val missing = notes
        .filterNot { it.id in alreadyReported || it.id in ids }
        .map { note ->
            LeadTimelineEvent(
                id = note.id,
                type = "note_added",
                occurredAtMillis = note.createdAtMillis,
                actor = note.authorName,
                text = note.text,
                noteId = note.id,
            )
        }

    return if (missing.isEmpty()) events else events + missing
}

/**
 * A slim progress track under the rail.
 *
 * Measured in items rather than pixels: a `LazyRow` only knows about what it has composed,
 * so a pixel-exact thumb would jump about as cards come and go. Item counts are stable and
 * the rep only needs the gist — roughly where they are, roughly how much is left.
 */
@Composable
private fun RailScrollBar(
    listState: androidx.compose.foundation.lazy.LazyListState,
    itemCount: Int,
) {
    val visibleCount by remember {
        derivedStateOf { listState.layoutInfo.visibleItemsInfo.size.coerceAtLeast(1) }
    }
    val firstVisible by remember { derivedStateOf { listState.firstVisibleItemIndex } }

    val thumbFraction = (visibleCount.toFloat() / itemCount).coerceIn(MIN_THUMB_FRACTION, 1f)
    val target = if (itemCount <= visibleCount) {
        0f
    } else {
        (firstVisible.toFloat() / (itemCount - visibleCount)).coerceIn(0f, 1f)
    }
    val offsetFraction by animateFloatAsState(targetValue = target, label = "railScroll")

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(3.dp)
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        val thumbWidth = maxWidth * thumbFraction
        Box(
            modifier = Modifier
                .padding(start = (maxWidth - thumbWidth) * offsetFraction)
                .width(thumbWidth)
                .height(3.dp)
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.outline),
        )
    }
}

@Composable
private fun ScrollPaddle(
    icon: ImageVector,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(26.dp)) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            modifier = Modifier.size(13.dp),
            tint = if (enabled) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.outlineVariant
            },
        )
    }
}

/**
 * One stop on the journey: an icon, an arrow onward, and a card of detail beneath.
 *
 * Fixed width so every card is the same size whatever it holds — a rail of ragged cards
 * reads as a mess, and the arrows only line up if the nodes are evenly spaced.
 */
@Composable
private fun JourneyNode(event: LeadTimelineEvent, isLast: Boolean, cardWidth: Dp) {
    val accent = accentFor(event.type)

    Row {
        Column(
            modifier = Modifier.width(cardWidth),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Solid rather than a tinted wash: at 40dp the marker is the only thing on this
            // screen carrying the event's colour, and a translucent fill on a near-black
            // ground loses most of it. A filled disc keeps each kind of event legible at a
            // glance while the rail is moving.
            Box(
                modifier = Modifier
                    .size(MARKER_SIZE)
                    .background(accent, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = iconFor(event.type),
                    contentDescription = null,
                    tint = onAccent(accent),
                    modifier = Modifier.size(14.dp),
                )
            }

            Spacer(Modifier.height(9.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 74.dp)
                    .background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.extraSmall)
                    .border(
                        BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        MaterialTheme.shapes.extraSmall,
                    )
                    .padding(horizontal = 10.dp, vertical = 9.dp),
            ) {
                // One line each. A card that grows to fit its longest field drags every
                // card beside it to the same height, and four ragged lines of wrapped
                // detail is what made the rail feel heavy.
                Text(
                    text = event.typeLabel,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                event.occurredAtMillis?.let {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = absoluteDateTime(it),
                        style = MaterialTheme.typography.labelSmall,
                        color = accent,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                event.text?.let {
                    Spacer(Modifier.height(5.dp))
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                // Only when it adds something. On an assignment the actor *is* the detail
                // line, and printing "Pawar Tushar" twice on one card reads as a bug.
                event.actor?.takeIf { it != event.text }?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        if (!isLast) {
            // Aligned to the icon row above the cards, not to their middle, so the arrows
            // sit on one line across the rail however tall the cards grow. Drawn in the
            // colour of the step it leads *to*, which makes the rail read as one path
            // rather than as cards with grey punctuation between them.
            Box(
                modifier = Modifier
                    .width(CONNECTOR_WIDTH)
                    .height(MARKER_SIZE),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    tint = accent.copy(alpha = 0.5f),
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

/**
 * Icon and colour per kind of event.
 *
 * Matched on substrings rather than exact values: the event vocabulary belongs to the
 * backend and is not fixed, so `call_completed`, `call_made` and `outbound_call` all read
 * as the same kind of thing without this needing to know each one. Anything unrecognised
 * gets the neutral marker rather than being dropped.
 */
@Composable
private fun accentFor(type: String?): Color {
    val word = type?.lowercase().orEmpty()
    val colors = journeyColors
    return when {
        word.contains("call") -> colors.call
        word.contains("assign") -> colors.assigned
        word.contains("note") || word.contains("message") || word.contains("sms") ||
            word.contains("email") || word.contains("whatsapp") -> colors.note
        word.contains("follow") || word.contains("meeting") || word.contains("demo") ||
            word.contains("remind") || word.contains("schedul") -> colors.scheduled
        word.contains("junk") || word.contains("lost") || word.contains("delete") ||
            word.contains("reject") -> colors.lost
        word.contains("creat") || word.contains("ingest") || word.contains("import") ||
            word.contains("upload") || word.contains("new") -> colors.created
        else -> colors.other
    }
}

/**
 * What to draw *on* a filled marker.
 *
 * Decided by the accent's own luminance rather than by the theme: the palette's accents sit
 * on both sides of the line — neon and amber are bright enough to need a dark glyph, the
 * light theme's emerald and cyan are dark enough to need a white one — so asking the colour
 * is the only thing that holds for every accent in both themes.
 */
private fun onAccent(accent: Color): Color =
    if (accent.luminance() > 0.45f) Color(0xFF050506) else Color.White

private fun iconFor(type: String?): ImageVector {
    val word = type?.lowercase().orEmpty()
    return when {
        word.contains("call") -> Icons.Default.Call
        word.contains("assign") -> Icons.Default.Person
        word.contains("note") || word.contains("message") || word.contains("sms") ||
            word.contains("email") || word.contains("whatsapp") -> Icons.Default.Edit
        word.contains("follow") || word.contains("meeting") || word.contains("demo") ||
            word.contains("remind") || word.contains("schedul") -> Icons.Default.DateRange
        word.contains("junk") || word.contains("lost") || word.contains("delete") ||
            word.contains("reject") -> Icons.Default.Clear
        word.contains("creat") || word.contains("ingest") || word.contains("import") ||
            word.contains("upload") || word.contains("new") -> Icons.Default.Add
        else -> Icons.Default.Info
    }
}

/**
 * How much of the rail one stop takes.
 *
 * A fraction rather than a fixed width so the rail lands part-way through a card on any
 * screen: a card that ends exactly at the edge looks like the end of the journey. Clamped
 * at both ends because a proportional card is unreadable on a small phone and absurd on a
 * tablet.
 */
private const val CARD_WIDTH_FRACTION = 0.40f
private val CARD_WIDTH_MIN = 126.dp
private val CARD_WIDTH_MAX = 164.dp

private val MARKER_SIZE = 30.dp

private val CONNECTOR_WIDTH = 22.dp

/** Under this there is only room for the swipe hint, not the legend beside it. */
private val LEGEND_MIN_WIDTH = 320.dp

/** Below this the thumb is too small to see, whatever the real ratio says. */
private const val MIN_THUMB_FRACTION = 0.12f
