package ai.arthax.app.recording

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which files in the holding area may be deleted at start-up.
 *
 * The copy is written before the queue row that references it, and a reconcile worker
 * can be between those two steps as the app starts. A fresh copy is therefore not an
 * orphan yet, however unreferenced it looks; only age makes it one.
 */
class RecordingStorageOrphanTest {

    private val grace = RecordingStorage.ORPHAN_GRACE_MILLIS

    @Test
    fun `a referenced recording is never deleted`() {
        assertFalse(RecordingStorage.shouldDelete("1_call.m4a", referenced = true, ageMillis = 0))
        assertFalse(RecordingStorage.shouldDelete("1_call.m4a", referenced = true, ageMillis = grace * 100))
    }

    @Test
    fun `an unreferenced recording is left alone while it may still be mid-capture`() {
        assertFalse(RecordingStorage.shouldDelete("1_call.m4a", referenced = false, ageMillis = 0))
        assertFalse(RecordingStorage.shouldDelete("1_call.m4a", referenced = false, ageMillis = grace - 1))
    }

    @Test
    fun `an unreferenced recording past the grace period is an orphan`() {
        assertTrue(RecordingStorage.shouldDelete("1_call.m4a", referenced = false, ageMillis = grace))
    }

    @Test
    fun `a staging file is deleted once old enough, referenced or not`() {
        // A .part is a copy that never finished. It is never what a queue row points at,
        // and one still being written is protected by its age, not by a reference.
        assertFalse(RecordingStorage.shouldDelete("1_call.m4a.part", referenced = false, ageMillis = 1_000))
        assertTrue(RecordingStorage.shouldDelete("1_call.m4a.part", referenced = false, ageMillis = grace))
        assertTrue(RecordingStorage.shouldDelete("1_call.m4a.part", referenced = true, ageMillis = grace))
    }
}
