package com.example.arthax.call

import android.provider.CallLog
import com.example.arthax.domain.model.CallDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How a call log row becomes a CRM outcome.
 *
 * This replaced deriving "connected" from telephony state, which was simply wrong: Android
 * fires OFFHOOK when an outbound call starts *dialling*, so every call the rep placed —
 * including the ones that rang out — was being reported as connected, with a duration that
 * counted the ringing.
 */
class CallLogEntryTest {

    private fun entry(type: Int, duration: Int, startedAt: Long = 1_788_000_000_000) =
        CallLogReader.Entry(id = 1, number = "7499639387", type = type, startedAt = startedAt, durationSeconds = duration)

    @Test
    fun `answered outgoing call is connected, outbound, with real talk time`() {
        val e = entry(CallLog.Calls.OUTGOING_TYPE, duration = 42)

        assertTrue(e.connected)
        assertEquals(CallDirection.OUTBOUND, e.direction)
        assertEquals(42, e.durationSeconds)
    }

    @Test
    fun `outgoing call that rang out is not connected`() {
        // The exact case the old off-hook logic got wrong.
        val e = entry(CallLog.Calls.OUTGOING_TYPE, duration = 0)

        assertFalse(e.connected)
        assertEquals(CallDirection.OUTBOUND, e.direction)
    }

    @Test
    fun `answered incoming call is connected and inbound`() {
        val e = entry(CallLog.Calls.INCOMING_TYPE, duration = 95)

        assertTrue(e.connected)
        assertEquals(CallDirection.INBOUND, e.direction)
        assertEquals(95, e.durationSeconds)
    }

    @Test
    fun `missed call is inbound and never connected`() {
        val e = entry(CallLog.Calls.MISSED_TYPE, duration = 0)

        assertFalse(e.connected)
        assertEquals(CallDirection.INBOUND, e.direction)
    }

    @Test
    fun `rejected call is never connected even if a duration leaks through`() {
        // Some OEMs record a second or two on a rejected call; it is still not a conversation.
        val e = entry(CallLog.Calls.REJECTED_TYPE, duration = 2)

        assertFalse(e.connected)
        assertEquals(CallDirection.INBOUND, e.direction)
    }

    @Test
    fun `voicemail counts as inbound`() {
        assertEquals(CallDirection.INBOUND, entry(CallLog.Calls.VOICEMAIL_TYPE, 30).direction)
    }

    @Test
    fun `end time is derived from start plus duration`() {
        val start = 1_788_000_000_000
        val e = entry(CallLog.Calls.OUTGOING_TYPE, duration = 60, startedAt = start)

        assertEquals(start + 60_000, e.endedAt)
    }

    @Test
    fun `an unanswered call ends when it started`() {
        val start = 1_788_000_000_000
        assertEquals(start, entry(CallLog.Calls.OUTGOING_TYPE, 0, start).endedAt)
    }
}
