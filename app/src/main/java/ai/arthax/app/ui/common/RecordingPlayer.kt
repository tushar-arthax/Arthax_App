package ai.arthax.app.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ai.arthax.app.ui.theme.statusColors

/**
 * Plays a call recording in place.
 *
 * Nothing here navigates away or hands the file to another app: a rep checking what was
 * said on a call is usually mid-conversation about it, and bouncing them into a browser
 * loses the call they were looking at.
 */
// SliderDefaults.Track, which is what lets the track be drawn at 3dp instead of the
// default 16, is still behind an opt-in.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordingPlayer(
    url: String,
    modifier: Modifier = Modifier,
) {
    val player = rememberAudioPlayer(url)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.medium)
            .border(
                BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                MaterialTheme.shapes.medium,
            )
            .padding(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PlayButton(player)

            Spacer(Modifier.width(10.dp))

            Column(Modifier.weight(1f)) {
                when {
                    player.error != null -> Text(
                        text = player.error.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = statusColors.warning,
                    )

                    player.isPreparing -> Text(
                        text = "Loading recording…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    else -> {
                        Slider(
                            // A slim track and a small thumb: this sits inside a call row,
                            // not on a music screen, and a full-height Material slider
                            // dominates everything around it.
                            track = { sliderState ->
                                SliderDefaults.Track(
                                    sliderState = sliderState,
                                    modifier = Modifier.height(3.dp),
                                    thumbTrackGapSize = 0.dp,
                                    drawStopIndicator = null,
                                    colors = SliderDefaults.colors(
                                        activeTrackColor = MaterialTheme.colorScheme.primary,
                                        inactiveTrackColor = MaterialTheme.colorScheme.outlineVariant,
                                    ),
                                )
                            },
                            thumb = {
                                Box(
                                    modifier = Modifier
                                        .size(11.dp)
                                        .background(MaterialTheme.colorScheme.primary, CircleShape),
                                )
                            },
                            value = player.positionMillis.toFloat(),
                            onValueChange = {
                                player.isScrubbing = true
                                player.positionMillis = it.toInt()
                            },
                            onValueChangeFinished = {
                                player.seekTo(player.positionMillis)
                                player.isScrubbing = false
                            },
                            // A zero-length range makes the thumb jump to the far end, so a
                            // recording whose duration MediaPlayer could not read gets a
                            // nominal range and simply does not move.
                            valueRange = 0f..(player.durationMillis.takeIf { it > 0 } ?: 1).toFloat(),
                            colors = SliderDefaults.colors(
                                thumbColor = MaterialTheme.colorScheme.primary,
                                activeTrackColor = MaterialTheme.colorScheme.primary,
                                inactiveTrackColor = MaterialTheme.colorScheme.outlineVariant,
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(18.dp),
                        )

                        Row(Modifier.fillMaxWidth()) {
                            Text(
                                text = formatPlaybackTime(player.positionMillis),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.weight(1f))
                            Text(
                                text = formatPlaybackTime(player.durationMillis),
                                style = MaterialTheme.typography.labelMedium,
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
private fun PlayButton(player: AudioPlayerState) {
    val enabled = player.isReady
    val background = when {
        player.error != null -> MaterialTheme.colorScheme.surfaceVariant
        enabled -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.surfaceVariant
    }

    Box(
        modifier = Modifier
            .size(34.dp)
            .background(background, CircleShape)
            .clickable(enabled = enabled) { player.togglePlayPause() }
            .semantics {
                contentDescription = if (player.isPlaying) "Pause recording" else "Play recording"
            },
        contentAlignment = Alignment.Center,
    ) {
        when {
            player.error != null -> Icon(
                Icons.Default.Refresh,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(15.dp),
            )

            player.isPreparing -> CircularProgressIndicator(
                modifier = Modifier.size(15.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            player.isPlaying -> PauseBars(MaterialTheme.colorScheme.onPrimary)

            else -> Icon(
                Icons.Default.PlayArrow,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(19.dp),
            )
        }
    }
}

/**
 * Two bars, drawn rather than imported.
 *
 * `material-icons-core` ships no pause glyph and the extended set is a 34 MB dependency for
 * one shape — see the note in the app's build file. Two rounded rectangles are the shape.
 */
@Composable
private fun PauseBars(tint: androidx.compose.ui.graphics.Color) {
    Row(
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(3.5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(2) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(12.dp)
                    .background(tint, MaterialTheme.shapes.extraSmall),
            )
        }
    }
}

/**
 * A small outlined chip — "REC", "AI" — for the facts about a row that are worth knowing at
 * a glance but are not worth a line of their own.
 */
@Composable
fun MetaChip(
    text: String,
    color: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .border(1.dp, color.copy(alpha = 0.5f), CircleShape)
            .padding(horizontal = 9.dp, vertical = 3.dp),
    ) {
        Text(
            text = text,
            style = ai.arthax.app.ui.theme.OverlineStyle,
            color = color,
        )
    }
}
