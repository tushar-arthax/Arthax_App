package ai.arthax.app.recording

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads the rep-selected recordings folder.
 *
 * Deliberately does NOT use DocumentFile.listFiles(): that issues a separate binder
 * round trip per file per attribute, so a folder holding a few hundred recordings costs
 * seconds and a fistful of main-thread jank. A single ContentResolver.query against
 * buildChildDocumentsUriUsingTree returns every column we need in one shot.
 */
@Singleton
class RecordingFinder @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    data class Candidate(
        val documentUri: Uri,
        val name: String,
        val sizeBytes: Long,
        val lastModified: Long,
        val mimeType: String,
    )

    /**
     * True if we still hold a persisted read grant on [treeUri]. Grants survive reboots
     * but are dropped if the rep clears app data, revokes access from Settings, or the
     * SD card holding the folder is unmounted — all of which we must detect and report
     * rather than silently capturing nothing.
     */
    fun hasValidGrant(treeUri: Uri): Boolean {
        val persisted = context.contentResolver.persistedUriPermissions
            .any { it.uri == treeUri && it.isReadPermission }
        if (!persisted) return false

        // Holding the grant is not the same as the folder still being reachable, so
        // probe it. An unmounted SD card throws here.
        return runCatching {
            context.contentResolver.query(
                childrenUriFor(treeUri, DocumentsContract.getTreeDocumentId(treeUri)),
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
                null,
                null,
                null,
            )?.use { true } ?: false
        }.getOrDefault(false)
    }

    fun takePersistableGrant(treeUri: Uri) {
        context.contentResolver.takePersistableUriPermission(
            treeUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
    }

    fun releaseGrant(treeUri: Uri) {
        runCatching {
            context.contentResolver.releasePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
    }

    /**
     * Every audio file under [treeUri] modified at or after [notBeforeMillis], newest first.
     *
     * Recurses [MAX_DEPTH] levels because several OEMs nest one level down
     * (Samsung: Recordings/Call, Xiaomi: MIUI/sound_recorder/call_rec), and reps
     * routinely pick the parent folder in the picker rather than the exact leaf.
     */
    /**
     * Outcome of one scan. Carries the counts as well as the matches so callers can tell
     * "the folder is empty" apart from "the folder is full but nothing is recent enough" —
     * the difference between a broken folder choice and a recorder that never fired, which
     * is the first question to ask on any missing-recording report.
     */
    data class ScanResult(
        val candidates: List<Candidate>,
        val foldersScanned: Int,
        val audioFilesSeen: Int,
        val newestSeenAt: Long?,
    )

    fun findCandidatesSince(treeUri: Uri, notBeforeMillis: Long): ScanResult {
        val results = mutableListOf<Candidate>()
        val stats = ScanStats()
        val rootDocId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
            ?: return ScanResult(emptyList(), 0, 0, null)

        scanInto(results, stats, treeUri, rootDocId, notBeforeMillis, depth = 0)

        return ScanResult(
            candidates = results.sortedByDescending { it.lastModified },
            foldersScanned = stats.folders,
            audioFilesSeen = stats.audioFiles,
            newestSeenAt = stats.newestSeenAt.takeIf { it > 0 },
        )
    }

    private class ScanStats {
        var folders = 0
        var audioFiles = 0
        var newestSeenAt = 0L
    }

    private fun scanInto(
        sink: MutableList<Candidate>,
        stats: ScanStats,
        treeUri: Uri,
        documentId: String,
        notBeforeMillis: Long,
        depth: Int,
    ) {
        if (depth > MAX_DEPTH || sink.size >= MAX_CANDIDATES) return

        stats.folders++
        val childrenUri = childrenUriFor(treeUri, documentId)
        val subDirectories = mutableListOf<String>()

        try {
            context.contentResolver.query(childrenUri, PROJECTION, null, null, null)?.use { cursor ->
                val idIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val sizeIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
                val modIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)

                while (cursor.moveToNext()) {
                    val docId = cursor.getString(idIdx) ?: continue
                    val name = cursor.getString(nameIdx) ?: continue
                    val mime = cursor.getString(mimeIdx).orEmpty()

                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        subDirectories += docId
                        continue
                    }

                    if (!isAudio(name, mime)) continue

                    val lastModified = if (cursor.isNull(modIdx)) 0L else cursor.getLong(modIdx)
                    val size = if (cursor.isNull(sizeIdx)) 0L else cursor.getLong(sizeIdx)

                    stats.audioFiles++
                    if (lastModified > stats.newestSeenAt) stats.newestSeenAt = lastModified

                    // The whole point of the timestamp filter: without it we would happily
                    // grab whatever recording happened to be newest, including one from a
                    // personal call made an hour ago.
                    if (lastModified < notBeforeMillis) continue

                    sink += Candidate(
                        documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId),
                        name = name,
                        sizeBytes = size,
                        lastModified = lastModified,
                        mimeType = mime.ifBlank { guessMime(name) },
                    )
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Could not read folder $documentId", t)
            return
        }

        subDirectories.forEach { scanInto(sink, stats, treeUri, it, notBeforeMillis, depth + 1) }
    }

    private fun childrenUriFor(treeUri: Uri, documentId: String): Uri =
        DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)

    /**
     * Extension is checked as well as MIME because several OEM media providers report
     * call recordings as application/octet-stream.
     */
    private fun isAudio(name: String, mimeType: String): Boolean {
        if (mimeType.startsWith("audio/")) return true
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in AUDIO_EXTENSIONS
    }

    private fun guessMime(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "mp3" -> "audio/mpeg"
        "m4a", "mp4" -> "audio/mp4"
        "aac" -> "audio/aac"
        "amr" -> "audio/amr"
        "wav" -> "audio/wav"
        "ogg", "opus" -> "audio/ogg"
        "3gp", "3gpp" -> "audio/3gpp"
        else -> "application/octet-stream"
    }

    companion object {
        private const val TAG = "RecordingFinder"
        private const val MAX_DEPTH = 2
        private const val MAX_CANDIDATES = 200

        val AUDIO_EXTENSIONS = setOf(
            "mp3", "m4a", "aac", "amr", "wav", "ogg", "opus", "3gp", "3gpp", "mp4", "awb", "qcp",
        )

        private val PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
    }
}
