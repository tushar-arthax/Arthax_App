package ai.arthax.app.ui.common

import android.media.AudioAttributes
import android.media.MediaPlayer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * Streams one audio URL, exposed as Compose state.
 *
 * Built on the platform [MediaPlayer] rather than ExoPlayer. This is a single remote file
 * with no playlist, no DRM and no adaptive streaming, which is exactly what MediaPlayer is
 * for — and Media3 would add roughly a megabyte to an APK whose own build file already
 * refuses a 34 MB icon dependency for the same reason.
 *
 * MediaPlayer is a strict state machine that throws [IllegalStateException] the moment it is
 * called in the wrong state, so every call out to it is guarded and the instance is released
 * the moment the composable leaves — a leaked MediaPlayer holds an audio focus handle and a
 * codec for the life of the process.
 */
class AudioPlayerState internal constructor() {
    var isPreparing by mutableStateOf(true)
        internal set

    var isPlaying by mutableStateOf(false)
        internal set

    var positionMillis by mutableIntStateOf(0)
        internal set

    var durationMillis by mutableIntStateOf(0)
        internal set

    /** Set when the file could not be loaded or played. Terminal: nothing retries it. */
    var error by mutableStateOf<String?>(null)
        internal set

    internal var player: MediaPlayer? = null

    /** True while the rep has their finger on the scrubber; position updates stand back. */
    internal var isScrubbing by mutableStateOf(false)

    val isReady: Boolean get() = !isPreparing && error == null

    fun togglePlayPause() {
        val mp = player ?: return
        if (!isReady) return

        runCatching {
            if (mp.isPlaying) {
                mp.pause()
                isPlaying = false
            } else {
                mp.start()
                isPlaying = true
            }
        }
    }

    /** Moves playback. Safe to call while paused. */
    fun seekTo(millis: Int) {
        val mp = player ?: return
        if (!isReady) return
        val target = millis.coerceIn(0, durationMillis)
        runCatching { mp.seekTo(target) }
        positionMillis = target
    }

    internal fun pauseIfPlaying() {
        val mp = player ?: return
        runCatching {
            if (mp.isPlaying) {
                mp.pause()
                isPlaying = false
            }
        }
    }
}

/**
 * Prepares [url] and keeps [AudioPlayerState] in step with it.
 *
 * Playback stops when the app leaves the foreground: audio that keeps running after the rep
 * has switched away is the kind of thing that gets an app uninstalled, and this is a call
 * recording — possibly on speaker, possibly in a room with the customer in it.
 */
@Composable
fun rememberAudioPlayer(url: String): AudioPlayerState {
    val state = remember(url) { AudioPlayerState() }

    DisposableEffect(url) {
        val player = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build(),
            )

            setOnPreparedListener { prepared ->
                state.durationMillis = prepared.duration.coerceAtLeast(0)
                state.isPreparing = false
            }

            setOnCompletionListener {
                // Rewound rather than left at the end, so the play button is immediately
                // useful again instead of needing a scrub first.
                state.isPlaying = false
                state.positionMillis = 0
                runCatching { seekTo(0) }
            }

            setOnErrorListener { _, what, extra ->
                state.isPreparing = false
                state.isPlaying = false
                state.error = "This recording could not be played ($what/$extra)"
                // true: the error is handled here, so MediaPlayer must not also fire
                // onCompletion, which would clear the message we just set.
                true
            }
        }

        state.player = player

        // setDataSource throws on a malformed URL rather than reporting through the error
        // listener, so it needs its own guard.
        runCatching {
            player.setDataSource(url)
            player.prepareAsync()
        }.onFailure {
            state.isPreparing = false
            state.error = "This recording could not be opened"
        }

        onDispose {
            state.player = null
            runCatching { player.stop() }
            player.release()
        }
    }

    // Polled rather than pushed: MediaPlayer has no position callback, and a quarter second
    // is smooth enough for a progress bar while costing nothing measurable.
    LaunchedEffect(state.isPlaying, state.isScrubbing) {
        while (state.isPlaying && !state.isScrubbing) {
            state.player?.let { mp ->
                runCatching { state.positionMillis = mp.currentPosition }
            }
            delay(POLL_INTERVAL_MILLIS)
        }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) {
        state.pauseIfPlaying()
    }

    return state
}

/** "1:04", or "12:07" for a long one. Hours are not a case call recordings reach. */
fun formatPlaybackTime(millis: Int): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0)
    return String.format(Locale.US, "%d:%02d", totalSeconds / 60, totalSeconds % 60)
}

private const val POLL_INTERVAL_MILLIS = 250L
