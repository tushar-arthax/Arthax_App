package ai.arthax.app.ui

import ai.arthax.app.ui.notifications.NotificationDateRange
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/** The date chips, as the backend reads them: whole IST days, both ends inclusive. */
class NotificationDateRangeTest {

    private val today = LocalDate.of(2026, 11, 3)

    @Test
    fun `each preset sends the right days`() {
        assertEquals(null to null, NotificationDateRange.ANY.bounds(today))
        assertEquals("2026-11-03" to "2026-11-03", NotificationDateRange.TODAY.bounds(today))
        assertEquals("2026-11-02" to "2026-11-02", NotificationDateRange.YESTERDAY.bounds(today))
        assertEquals("2026-10-28" to "2026-11-03", NotificationDateRange.LAST_7_DAYS.bounds(today))
        assertEquals("2026-10-05" to "2026-11-03", NotificationDateRange.LAST_30_DAYS.bounds(today))
        assertEquals("2026-11-01" to "2026-11-03", NotificationDateRange.THIS_MONTH.bounds(today))
    }

    @Test
    fun `yesterday across a month boundary`() {
        assertEquals("2026-10-31" to "2026-10-31", NotificationDateRange.YESTERDAY.bounds(LocalDate.of(2026, 11, 1)))
    }
}
