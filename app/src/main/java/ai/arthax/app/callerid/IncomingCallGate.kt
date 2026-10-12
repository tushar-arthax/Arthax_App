package ai.arthax.app.callerid

/**
 * Decides whether an incoming-call signal should start a caller-ID lookup.
 *
 * The same ring is reported more than once. On Android 10+ the PHONE_STATE broadcast is
 * delivered twice (once without the number, once with it), and when Arthax also holds the
 * call-screening role the screening service reports the ring a third time — all within a
 * few hundred milliseconds. One lookup per ring is the whole point of this class, so it is
 * kept pure and pinned by unit tests rather than left as ad-hoc checks in a receiver.
 */
class IncomingCallGate(
    private val clock: () -> Long = System::currentTimeMillis,
    private val windowMillis: Long = DEFAULT_WINDOW_MILLIS,
) {

    sealed interface Decision {
        /** Start a lookup for this number. */
        data class Lookup(val key: String) : Decision

        /** The number is withheld, malformed, or already being handled. */
        data object Ignore : Decision
    }

    private var currentKey: String? = null
    private var currentSince = 0L

    /**
     * [key] is the number's match key (trailing digits); empty for a withheld number.
     * A withheld number can never be a lead, so it is ignored outright.
     */
    @Synchronized
    fun onRinging(key: String): Decision {
        if (key.isEmpty()) return Decision.Ignore

        val now = clock()
        val sameRing = currentKey == key && now - currentSince < windowMillis
        if (sameRing) return Decision.Ignore

        currentKey = key
        currentSince = now
        return Decision.Lookup(key)
    }

    /** The line went off-hook or idle: whatever was showing is over. */
    @Synchronized
    fun onCallEnded() {
        currentKey = null
        currentSince = 0L
    }

    /** True while a ring for [key] is being handled. */
    @Synchronized
    fun isCurrent(key: String): Boolean = currentKey == key

    companion object {
        /**
         * Long enough to cover the slowest OEM's spread of duplicate signals, short enough
         * that a genuine second call from the same number after a missed call is shown again.
         */
        const val DEFAULT_WINDOW_MILLIS = 20_000L
    }
}
