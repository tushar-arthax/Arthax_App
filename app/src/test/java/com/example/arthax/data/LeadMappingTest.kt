package com.example.arthax.data

import com.example.arthax.data.remote.dto.LeadDto
import com.example.arthax.data.remote.dto.toDomain
import com.example.arthax.domain.model.LeadTemperature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The DTO mapping is the seam where the real API lands, and staging returns *both* null and
 * "" for the same optional fields depending on how a lead was created. A single malformed
 * lead must never blank the whole dashboard.
 */
class LeadMappingTest {

    @Test
    fun `a fully populated lead maps across`() {
        val lead = LeadDto(
            id = "eb4da459-cf0e-46eb-9fd8-b61d746f2d6f",
            name = "Sanjana",
            phone = "7989130171",
            company = "Sunrise Textiles",
            status = "contacted",
            temperature = "warm",
            rating = 5,
        ).toDomain()

        assertEquals("Sanjana", lead.name)
        assertEquals("7989130171", lead.phoneNumber)
        assertEquals("Sunrise Textiles", lead.company)
        assertEquals("contacted", lead.status)
        assertEquals(LeadTemperature.WARM, lead.temperature)
        assertEquals(5, lead.rating)
        assertTrue(lead.isCallable)
    }

    @Test
    fun `empty strings are normalised to null, exactly like nulls`() {
        // Staging returns "" for these on manually created leads and null on imported ones.
        val blanks = LeadDto(id = "1", name = "X", phone = "1", company = "", email = "", location = "")
        val nulls = LeadDto(id = "1", name = "X", phone = "1")

        listOf(blanks.toDomain(), nulls.toDomain()).forEach { lead ->
            assertNull(lead.company)
            assertNull(lead.email)
            assertNull(lead.location)
        }
    }

    @Test
    fun `a blank name gets a readable placeholder rather than an empty row`() {
        assertEquals("Unnamed lead", LeadDto(id = "1", name = "   ", phone = "1").toDomain().name)
    }

    @Test
    fun `a lead with no phone is marked uncallable so the list can drop it`() {
        val lead = LeadDto(id = "1", name = "No Number").toDomain()

        assertEquals("", lead.phoneNumber)
        assertFalse(lead.isCallable)
    }

    @Test
    fun `unknown or missing temperature falls back to cold instead of throwing`() {
        assertEquals(LeadTemperature.COLD, LeadDto(id = "1", name = "X", temperature = "molten").toDomain().temperature)
        assertEquals(LeadTemperature.COLD, LeadDto(id = "1", name = "X", temperature = null).toDomain().temperature)
    }

    @Test
    fun `temperature matching is case insensitive`() {
        assertEquals(LeadTemperature.HOT, LeadDto(id = "1", name = "X", temperature = "HOT").toDomain().temperature)
    }

    @Test
    fun `status is free text and is shown, not mapped onto a fixed enum`() {
        // The CRM lets orgs define their own statuses, so an unseen value must survive.
        val lead = LeadDto(id = "1", name = "X", status = "awaiting_paperwork").toDomain()

        assertEquals("awaiting_paperwork", lead.status)
        assertEquals("Awaiting paperwork", lead.statusLabel)
    }

    @Test
    fun `zone-less last contacted date is interpreted as server time`() {
        val lead = LeadDto(id = "1", name = "X", lastContactedDate = "2026-09-02T17:04:35.193425").toDomain()

        // 17:04:35 IST == 11:34:35 UTC
        assertEquals(
            java.time.Instant.parse("2026-09-02T11:34:35.193Z").toEpochMilli(),
            lead.lastContactedAt,
        )
    }
}
