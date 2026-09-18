package ai.arthax.app.recording

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App-private holding area for captured recordings.
 *
 * This copy is the durability guarantee. WorkManager can retry an upload for days, but
 * the SAF grant can be revoked and OEM recorders prune their own folders on a rolling
 * window — so by the time a retry finally gets network, the original may be long gone.
 * We copy first, queue second, and only delete once the server has acknowledged.
 */
@Singleton
class RecordingStorage @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val dir: File
        get() = File(context.filesDir, DIR_NAME).apply { if (!exists()) mkdirs() }

    /**
     * Streams [sourceUri] into app-private storage.
     *
     * Writes to a .part file and renames on completion, so a process death mid-copy
     * leaves an obviously-incomplete artefact rather than a truncated file that would
     * upload as a corrupt recording.
     */
    suspend fun copyIn(sourceUri: Uri, displayName: String): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val safeName = sanitize(displayName)
            val target = File(dir, "${System.currentTimeMillis()}_$safeName")
            val staging = File(target.absolutePath + PART_SUFFIX)

            context.contentResolver.openInputStream(sourceUri).use { input ->
                requireNotNull(input) { "Recording provider returned no stream for $sourceUri" }
                FileOutputStream(staging).use { output ->
                    input.copyTo(output, DEFAULT_BUFFER_SIZE)
                    output.fd.sync()
                }
            }

            check(staging.length() > 0) { "Copied recording was empty" }
            check(staging.renameTo(target)) { "Could not finalise copy at ${target.name}" }
            target
        }
    }

    fun delete(path: String) {
        runCatching { File(path).takeIf { it.exists() }?.delete() }
            .onFailure { Log.w(TAG, "Could not delete $path", it) }
    }

    fun exists(path: String): Boolean = File(path).let { it.exists() && it.length() > 0 }

    fun totalBytesHeld(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0L

    /**
     * Clears orphans left by a crash mid-copy, plus any file no longer referenced by a
     * queue row. Called on app start.
     */
    fun cleanOrphans(referencedPaths: Set<String>) {
        val files = dir.listFiles() ?: return
        files.forEach { file ->
            val isStaging = file.name.endsWith(PART_SUFFIX)
            val isOrphan = file.absolutePath !in referencedPaths
            if (isStaging || isOrphan) {
                runCatching { file.delete() }
            }
        }
    }

    private fun sanitize(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(MAX_NAME_LENGTH).ifBlank { "recording.m4a" }

    private companion object {
        const val TAG = "RecordingStorage"
        const val DIR_NAME = "pending_recordings"
        const val PART_SUFFIX = ".part"
        const val MAX_NAME_LENGTH = 80
    }
}
