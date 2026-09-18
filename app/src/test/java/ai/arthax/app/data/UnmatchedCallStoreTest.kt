package ai.arthax.app.data

import ai.arthax.app.data.local.store.UnmatchedCall
import ai.arthax.app.data.local.store.UnmatchedCallStore
import com.squareup.moshi.Moshi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Calls the CRM did not recognise are parked, re-asked about on a budget, and let go only
 * when retention runs out. Each of those has a number attached to it, so each is pinned.
 */
class UnmatchedCallStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val t0 = 1_788_000_000_000L
    private val hour = 60L * 60 * 1000
    private val day = 24 * hour

    private fun store(file: File = folder.newFile("unmatched.json")) =
        UnmatchedCallStore(file, Moshi.Builder().build())

    private fun call(id: Long, date: Long = t0, firstSeenAt: Long = t0, lastCheckedAt: Long = 0L) = UnmatchedCall(
        callLogId = id,
        callLogDate = date,
        phone = "+917744991250",
        direction = "outbound",
        connected = true,
        durationSeconds = 42,
        startedAt = date,
        endedAt = date + 42_000,
        firstSeenAt = firstSeenAt,
        lastCheckedAt = lastCheckedAt,
    )

    // -------------------------------------------------------------------------------
    // Re-check budget
    // -------------------------------------------------------------------------------

    @Test
    fun `a row never asked about is due at once`() {
        assertTrue(call(1).isDueForServerCheck(now = t0, recheckMillis = 6 * hour))
    }

    @Test
    fun `a row asked about an hour ago is not due on a six hour budget`() {
        val row = call(1, lastCheckedAt = t0)
        assertFalse(row.isDueForServerCheck(now = t0 + hour, recheckMillis = 6 * hour))
    }

    @Test
    fun `a row asked about six hours ago is due again`() {
        val row = call(1, lastCheckedAt = t0)
        assertTrue(row.isDueForServerCheck(now = t0 + 6 * hour, recheckMillis = 6 * hour))
    }

    @Test
    fun `marking a row checked stamps it and counts the ask`() = runTest {
        val store = store()
        store.load()
        store.remember(call(7))

        store.markChecked(7, t0, at = t0 + hour)

        val row = store.outstanding().single()
        assertEquals(t0 + hour, row.lastCheckedAt)
        assertEquals(1, row.checks)
        assertFalse(row.isDueForServerCheck(now = t0 + 2 * hour, recheckMillis = 6 * hour))
    }

    // -------------------------------------------------------------------------------
    // Retention
    // -------------------------------------------------------------------------------

    @Test
    fun `a row inside retention is kept`() {
        assertFalse(call(1, firstSeenAt = t0).isExpired(now = t0 + 6 * day, retentionMillis = 7 * day))
    }

    @Test
    fun `a row past retention is expired`() {
        assertTrue(call(1, firstSeenAt = t0).isExpired(now = t0 + 7 * day, retentionMillis = 7 * day))
    }

    @Test
    fun `pruning drops only the expired rows and says how many`() = runTest {
        val store = store()
        store.load()
        store.remember(call(1, date = t0, firstSeenAt = t0))
        store.remember(call(2, date = t0 + day, firstSeenAt = t0 + 5 * day))

        val dropped = store.pruneExpired(retentionMillis = 7 * day, now = t0 + 8 * day)

        assertEquals(1, dropped)
        assertEquals(listOf(2L), store.outstanding().map { it.callLogId })
    }

    // -------------------------------------------------------------------------------
    // Identity and delivery
    // -------------------------------------------------------------------------------

    @Test
    fun `a row is recognised by id and date, so a re-dated row is a new call`() = runTest {
        val store = store()
        store.load()
        store.remember(call(3, date = t0))

        assertTrue(store.contains(3, t0))
        assertFalse("same id, later date: the OEM re-dated the row for a repeat call", store.contains(3, t0 + 60_000))
    }

    @Test
    fun `remembering the same call twice keeps one row`() = runTest {
        val store = store()
        store.load()
        store.remember(call(4))
        store.remember(call(4))

        assertEquals(1, store.count)
    }

    @Test
    fun `a matched row is removed and the rest stay in oldest-first order`() = runTest {
        val store = store()
        store.load()
        store.remember(call(10, date = t0 + 2 * hour))
        store.remember(call(11, date = t0))
        store.remember(call(12, date = t0 + hour))

        store.remove(12, t0 + hour)

        assertEquals(listOf(11L, 10L), store.outstanding().map { it.callLogId })
    }

    @Test
    fun `rows survive a restart with everything needed to deliver the call`() = runTest {
        val file = folder.newFile("persisted.json")
        store(file).apply {
            load()
            remember(call(20).copy(nextCallStartedAt = t0 + 90_000))
        }

        val reloaded = store(file).apply { load() }
        val row = reloaded.outstanding().single()

        assertEquals(20L, row.callLogId)
        assertEquals("+917744991250", row.phone)
        assertEquals(42, row.durationSeconds)
        assertEquals(t0 + 42_000, row.endedAt)
        assertEquals(t0 + 90_000, row.nextCallStartedAt)
    }
}
