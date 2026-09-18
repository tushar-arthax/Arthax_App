package ai.arthax.app.data

import ai.arthax.app.data.local.store.SyncHealthState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The last-day counters the heartbeat reports, and the upload hold a 402 sets. */
class SyncHealthStateTest {

    private val now = 1_788_000_000_000L
    private val hour = 60L * 60 * 1000

    @Test
    fun `only the last twenty four hours are counted`() {
        val state = SyncHealthState(
            callsSeen = listOf(now - 25 * hour, now - 23 * hour, now - hour),
            callsPosted = listOf(now - 30 * hour),
            errors = listOf(now - 2 * hour, now - 26 * hour),
        )

        assertEquals(2, state.callsSeen24h(now))
        assertEquals(0, state.callsPosted24h(now))
        assertEquals(1, state.errors24h(now))
    }

    @Test
    fun `an upload hold is only a hold while it is in the future`() {
        assertTrue(SyncHealthState(uploadBlockedUntil = now + hour).isUploadBlocked(now))
        assertFalse(SyncHealthState(uploadBlockedUntil = now - 1).isUploadBlocked(now))
        assertFalse(SyncHealthState().isUploadBlocked(now))
    }
}
