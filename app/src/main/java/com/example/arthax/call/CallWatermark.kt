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
}
