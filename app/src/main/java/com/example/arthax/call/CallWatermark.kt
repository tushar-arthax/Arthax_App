package com.example.arthax.call

/**
 * Where call tracking starts from.
 *
 * Small enough to look trivial, and it was wrong in a way that cost every rep their first
 * call. The rule is: anchor to the newest call the phone *already had*, never to the clock.
 *
 * Anchoring to the clock loses any call that happened between signing in and the watermark
 * being set — and because a finished call is exactly what triggers the first reconcile, that
 * was guaranteed to be the rep's first call, every single time.
 */
object CallWatermark {

    /**
     * @param newestExistingCallAt timestamp of the most recent call already in the log, or
     *   0 when the log is empty.
     * @param now current wall clock, used only when there is no earlier call to anchor to.
     */
    fun initial(newestExistingCallAt: Long, now: Long): Long =
        if (newestExistingCallAt > 0) newestExistingCallAt else now

    /**
     * Whether a call-log row is one this app has already turned into CRM activity.
     *
     * A row is identified by its id *and* the timestamp it carried when we saw it. The id
     * alone is not an identity: Xiaomi and some other builds record a repeat unanswered call
     * to the same number by re-dating the row that is already there rather than inserting a
     * new one, so an id-only check reported "already done" and every call after the first in
     * such a run was dropped.
     *
     * @param storedDate zero for rows queued before the date was recorded. Those fall back
     *   to matching on the id alone, so upgrading cannot re-post calls already delivered.
     */
    fun isSameCall(storedId: Long, storedDate: Long, rowId: Long, rowDate: Long): Boolean =
        storedId == rowId && (storedDate == 0L || storedDate == rowDate)

    /**
     * Whether a row is one this app has yet to look at. Mirrors the selection the call log
     * is queried with — `_id > sinceId OR date > sinceDate` — and exists so the reasoning
     * behind that selection is executable rather than only asserted in a comment.
     *
     * Both halves earn their place:
     *  - **id** catches a call that began before the previous one had ended. Rows are written
     *    at hang-up but stamped with the start, so that row carries an *earlier* date than
     *    one already processed and a date-only watermark would never return it.
     *  - **date** catches a row an OEM re-dated in place to record a repeat call, which
     *    keeps its original id and so slips under an id-only watermark.
     */
    fun isUnseen(rowId: Long, rowDate: Long, sinceId: Long, sinceDate: Long): Boolean =
        rowId > sinceId || rowDate > sinceDate

    /**
     * Where the trailing window each pass re-reads begins.
     *
     * Behind the watermark by the configured look-back, but never below the floor set when
     * tracking began on this install — however wide the window is made, the phone's call
     * history from before the app cannot be swept up and posted as though it just happened.
     */
    fun windowStart(floor: Long, watermark: Long, lookbackMillis: Long): Long =
        maxOf(floor, watermark - lookbackMillis)
}
