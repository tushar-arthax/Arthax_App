package com.example.arthax.data

import com.example.arthax.data.remote.dto.ApiTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

/**
 * The API sends three different timestamp shapes for the same concept, and the zone-less
 * ones are Asia/Kolkata rather than UTC. Getting that wrong silently shifts every
 * "last called" reading by five and a half hours, which is exactly the kind of bug nobody
 * reports because the number still looks plausible.
 */
class ApiTimeTest {

    @Test
    fun `zone-less timestamp is read as server time, not UTC`() {
        // Measured against staging: a call created at 16:47:23 UTC came back as this.
        val parsed = ApiTime.parseOrNull("2026-09-02T22:17:23.152010")

        assertEquals(Instant.parse("2026-09-02T16:47:23.152Z").toEpochMilli(), parsed)
    }

    @Test
    fun `zone-less timestamp without a fraction parses`() {
        val parsed = ApiTime.parseOrNull("2026-08-25T19:30:00")

        assertEquals(Instant.parse("2026-08-25T14:00:00Z").toEpochMilli(), parsed)
    }

    @Test
    fun `explicit UTC is trusted as-is`() {
        val parsed = ApiTime.parseOrNull("2026-09-02T16:37:14.308Z")

        assertEquals(Instant.parse("2026-09-02T16:37:14.308Z").toEpochMilli(), parsed)
    }

    @Test
    fun `explicit offset is honoured`() {
        val parsed = ApiTime.parseOrNull("2026-09-02T22:17:23+05:30")

        assertEquals(Instant.parse("2026-09-02T16:47:23Z").toEpochMilli(), parsed)
    }

    @Test
    fun `null blank and rubbish never throw`() {
        assertNull(ApiTime.parseOrNull(null))
        assertNull(ApiTime.parseOrNull(""))
        assertNull(ApiTime.parseOrNull("   "))
        assertNull(ApiTime.parseOrNull("not a date"))
        assertNull(ApiTime.parseOrNull("2026-13-45T99:99:99"))
    }

    @Test
    fun `outgoing timestamps always carry an explicit UTC marker`() {
        val formatted = ApiTime.format(Instant.parse("2026-09-02T16:47:23.152Z").toEpochMilli())

        assertEquals("2026-09-02T16:47:23.152Z", formatted)
    }

    @Test
    fun `format and parse round-trip`() {
        val original = Instant.parse("2026-01-15T08:30:00.000Z").toEpochMilli()

        assertEquals(original, ApiTime.parseOrNull(ApiTime.format(original)))
    }
}
