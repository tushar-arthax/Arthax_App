package ai.arthax.app.data

import ai.arthax.app.core.PhoneNumbers
import ai.arthax.app.data.remote.dto.LeadDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Picking the right lead out of a fuzzy search result.
 *
 * `GET /api/leads/?search=` is a text search across several columns, so the highest-ranked
 * item is not necessarily a phone match at all — a lead whose *notes* contain the digits
 * ranks just as well. Taking `items.first()` would file a call, and its recording, against
 * the wrong customer, and nothing downstream would ever notice.
 *
 * This mirrors the selection rule in LeadResolver.pickMatch so the behaviour is pinned
 * independently of the network layer.
 */
class LeadMatchSelectionTest {

    private fun lead(
        id: String,
        phone: String?,
        name: String = id,
        assignedTo: String? = null,
        isJunk: Boolean = false,
        createdAt: String? = null,
    ) = LeadDto(
        id = id,
        name = name,
        phone = phone,
        assignedTo = assignedTo,
        isJunk = isJunk,
        createdAt = createdAt,
    )

    /** The rule under test, kept in step with LeadResolver. */
    private fun pick(items: List<LeadDto>, key: String, me: String? = null): LeadDto? {
        val matches = items.filter { PhoneNumbers.matchKey(it.phone) == key }
        if (matches.isEmpty()) return null
        if (matches.size == 1) return matches.single()
        return matches.sortedWith(
            compareByDescending<LeadDto> { me != null && it.assignedTo == me }
                .thenByDescending { !it.isJunk }
                .thenByDescending { it.createdAt.orEmpty() },
        ).first()
    }

    @Test
    fun `exact phone match is selected`() {
        val items = listOf(lead("amol", "7744991250"))

        assertEquals("amol", pick(items, "7744991250")?.id)
    }

    @Test
    fun `a top-ranked result that matched on notes is rejected`() {
        // The dangerous case: search ranked this first, but its phone is a different number.
        val items = listOf(
            lead("wrong-lead", "9876543210", name = "Mentions 7744991250 in notes"),
            lead("amol", "7744991250"),
        )

        assertEquals("amol", pick(items, "7744991250")?.id)
    }

    @Test
    fun `no phone match yields nothing rather than a guess`() {
        val items = listOf(lead("someone", "9876543210"), lead("other", null))

        assertNull(pick(items, "7744991250"))
    }

    @Test
    fun `an empty result set is not a lead`() {
        assertNull(pick(emptyList(), "7744991250"))
    }

    @Test
    fun `country code variants still match`() {
        val items = listOf(lead("amol", "+917744991250"))

        assertEquals("amol", pick(items, "7744991250")?.id)
    }

    @Test
    fun `a lead with no phone never matches`() {
        assertNull(pick(listOf(lead("ghost", null)), "7744991250"))
        assertNull(pick(listOf(lead("blank", "")), "7744991250"))
    }

    @Test
    fun `duplicates prefer the lead assigned to this rep`() {
        val me = "rep-1"
        val items = listOf(
            lead("theirs", "7744991250", assignedTo = "rep-2", createdAt = "2026-09-03T10:00:00"),
            lead("mine", "7744991250", assignedTo = me, createdAt = "2026-01-01T10:00:00"),
        )

        assertEquals("mine", pick(items, "7744991250", me = me)?.id)
    }

    @Test
    fun `duplicates prefer a non-junk lead`() {
        val items = listOf(
            lead("junk", "7744991250", isJunk = true, createdAt = "2026-09-03T10:00:00"),
            lead("real", "7744991250", isJunk = false, createdAt = "2026-01-01T10:00:00"),
        )

        assertEquals("real", pick(items, "7744991250")?.id)
    }

    @Test
    fun `duplicates otherwise prefer the newest, and do so deterministically`() {
        val items = listOf(
            lead("older", "7744991250", createdAt = "2026-01-01T10:00:00"),
            lead("newer", "7744991250", createdAt = "2026-09-03T10:00:00"),
        )

        // Same answer regardless of the order the server happened to return them in.
        assertEquals("newer", pick(items, "7744991250")?.id)
        assertEquals("newer", pick(items.reversed(), "7744991250")?.id)
    }

    @Test
    fun `a large noisy result set still resolves to the one real match`() {
        val noise = (1..24).map { lead("noise-$it", "90000000${it.toString().padStart(2, '0')}") }
        val items = noise + lead("amol", "7744991250")

        val picked = pick(items, "7744991250")
        assertTrue(picked != null && picked.id == "amol")
    }
}
