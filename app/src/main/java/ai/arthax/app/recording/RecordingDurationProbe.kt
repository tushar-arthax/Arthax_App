package ai.arthax.app.recording

import android.media.MediaMetadataRetriever
import android.util.Log
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads how long a recording actually plays for.
 *
 * Wrapped so the harvester can be reasoned about without the platform, and so the one
 * call that can throw for a dozen container-specific reasons is contained: a file the
 * retriever cannot open answers null, never an exception up the capture path.
 */
@Singleton
class RecordingDurationProbe @Inject constructor() {

    /** Seconds of audio, or null when the container could not be read. */
    fun durationSeconds(file: File): Double? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.takeIf { it > 0 }
                ?.let { it / 1_000.0 }
        } catch (t: Throwable) {
            Log.w(TAG, "Could not read the duration of ${file.name}: ${t.message}")
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private companion object {
        const val TAG = "RecordingProbe"
    }
}
