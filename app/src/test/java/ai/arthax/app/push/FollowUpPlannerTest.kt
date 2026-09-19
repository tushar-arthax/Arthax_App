package ai.arthax.app.push

import ai.arthax.app.domain.model.Lead
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which follow-ups get an on-device timer, and for when. Too eager and the phone buzzes
 * about last week's follow-ups the moment the app opens; too lazy and the one this
 * afternoon is missed.
 */
class FollowUpPlannerTest {

    private val now = 1_788_000_000_000L
    private val minute = 60_000L
    private val hour = 60 * minute

    private fun lead(id: String, followUpAt: Long?, name: String = "Lead $id") =
        Lead(id = id, name = name, phoneNumber = "98765$id", nextFollowUpAt = followUpAt)

    private val never = { _: String, _: Long -> false }

    @Test
    fun `a follow-up later today is planned ten minutes before it`() {
        val plans = FollowUpPlanner.plan(listOf(lead("1", now + 3 * hour)), now, never)

        assertEquals(1, plans.size)
        val plan = plans.single()
        assertEquals(now + 3 * hour - 10 * minute, plan.fireAt)
        assertEquals(3 * hour - 10 * minute, plan.delayFrom(now))
        assertEquals("followup:1:${now + 3 * hour}", plan.workName)
        assertEquals("1:${now + 3 * hour}", plan.remindedKey)
    }

    @Test
    fun `leads without a follow-up date are skipped`() {
        assertTrue(FollowUpPlanner.plan(listOf(lead("1", null)), now, never).isEmpty())
    }

    @Test
    fun `follow-ups already past are skipped`() {
        val plans = FollowUpPlanner.plan(
            listOf(lead("old", now - hour), lead("justNow", now), lead("soon", now + hour)),
            now,
            never,
        )

        assertEquals(listOf("soon"), plans.map { it.leadId })
    }

    @Test
    fun `only the next forty-eight hours are armed`() {
        val plans = FollowUpPlanner.plan(
            listOf(
                lead("inside", now + 47 * hour),
                lead("edge", now + FollowUpPlanner.HORIZON_MILLIS),
                lead("outside", now + 49 * hour),
                lead("nextWeek", now + 7 * 24 * hour),
            ),
            now,
            never,
        )

        assertEquals(listOf("inside", "edge"), plans.map { it.leadId })
    }

    @Test
    fun `a follow-up within the lead time fires straight away`() {
        val plan = FollowUpPlanner.plan(listOf(lead("1", now + 4 * minute)), now, never).single()

        assertEquals(0L, plan.delayFrom(now))
    }

    @Test
    fun `a follow-up already reminded about is not armed again`() {
        val remindedKeys = setOf(FollowUpPlanner.remindedKey("1", now + hour))
        val plans = FollowUpPlanner.plan(
            listOf(lead("1", now + hour), lead("2", now + hour)),
            now,
        ) { leadId, dueAt -> FollowUpPlanner.remindedKey(leadId, dueAt) in remindedKeys }

        assertEquals(listOf("2"), plans.map { it.leadId })
    }

    @Test
    fun `a moved follow-up gets a new key, so the old reminder does not silence it`() {
        val remindedKeys = setOf(FollowUpPlanner.remindedKey("1", now + hour))
        val plans = FollowUpPlanner.plan(
            listOf(lead("1", now + 2 * hour)),
            now,
        ) { leadId, dueAt -> FollowUpPlanner.remindedKey(leadId, dueAt) in remindedKeys }

        assertEquals(listOf("followup:1:${now + 2 * hour}"), plans.map { it.workName })
    }

    @Test
    fun `the reminder carries what the notification needs`() {
        val plan = FollowUpPlanner.plan(listOf(lead("7", now + hour, name = "Amol")), now, never).single()

        assertEquals("Amol", plan.leadName)
        assertEquals("987657", plan.phone)
        assertEquals(now + hour, plan.dueAt)
    }
}
