package ai.arthax.app.ui.notifications

import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The date filter on the Notifications screen, as the backend reads it: whole days in IST,
 * both ends inclusive, `YYYY-MM-DD`. Pure Kotlin so the bounds are unit-tested.
 */
enum class NotificationDateRange(val label: String) {
    ANY("Any time"),
    TODAY("Today"),
    YESTERDAY("Yesterday"),
    LAST_7_DAYS("Last 7 days"),
    LAST_30_DAYS("Last 30 days"),
    THIS_MONTH("This month"),
    ;

    /** (from, to) for the API; both null for [ANY]. */
    fun bounds(today: LocalDate = todayInIst()): Pair<String?, String?> {
        val (from, to) = when (this) {
            ANY -> return null to null
            TODAY -> today to today
            YESTERDAY -> today.minusDays(1).let { it to it }
            LAST_7_DAYS -> today.minusDays(6) to today
            LAST_30_DAYS -> today.minusDays(29) to today
            THIS_MONTH -> today.withDayOfMonth(1) to today
        }
        return from.format(FORMAT) to to.format(FORMAT)
    }

    companion object {
        /** The backend stores and filters notification times in IST. */
        val IST: ZoneId = ZoneId.of("Asia/Kolkata")
        private val FORMAT: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

        fun todayInIst(): LocalDate = LocalDate.now(IST)
    }
}
