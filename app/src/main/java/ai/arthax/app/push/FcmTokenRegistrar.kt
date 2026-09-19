package ai.arthax.app.push

/**
 * Keeps the server's copy of this phone's push token current.
 *
 * The token and the sign-in arrive in either order — Firebase issues the token on first
 * start, usually before the rep has typed a code — and the server can only be told once
 * there is a bearer token to tell it with. So the token is always persisted first, and the
 * upload happens at whichever comes second: [register] when the rep is already signed in,
 * [onSignedIn] when the token was waiting. A failed upload leaves the token marked
 * unregistered, and the next start or sign-in retries it.
 *
 * Signing out forgets what the server was told, not the token itself: the next rep on the
 * handset registers the same token under their own session, and the server detaches it
 * from the previous one.
 *
 * Pure Kotlin: the store and the upload are handed in, so the sequencing is unit-tested.
 */
class FcmTokenRegistrar(
    private val store: Store,
    /** PATCH /users/me/fcm-token. True on a 2xx. */
    private val upload: suspend (token: String) -> Boolean,
    private val isSignedIn: () -> Boolean,
) {

    data class State(val token: String? = null, val registeredToken: String? = null) {
        /** The server has the token this phone currently holds. */
        val isRegistered: Boolean get() = token != null && token == registeredToken
    }

    interface Store {
        suspend fun read(): State
        suspend fun write(state: State)
    }

    /**
     * A token arrived — first issue or a rotation. Returns true once the server has it.
     */
    suspend fun register(token: String): Boolean {
        val clean = token.trim()
        if (clean.isEmpty()) return false

        val current = store.read()
        if (current.token != clean) {
            // A new token invalidates whatever the server was told before.
            store.write(State(token = clean, registeredToken = null))
        } else if (current.isRegistered) {
            return true
        }

        return uploadIfSignedIn(clean)
    }

    /** Just signed in: flush a token that was waiting for a session. */
    suspend fun onSignedIn(): Boolean {
        val current = store.read()
        val token = current.token ?: return false
        if (current.isRegistered) return true
        return uploadIfSignedIn(token)
    }

    /** Signing out: the server's record belongs to the ending session, not the phone. */
    suspend fun onSigningOut() {
        val current = store.read()
        if (current.registeredToken != null) store.write(current.copy(registeredToken = null))
    }

    /** Whether the server has this phone's current token — reported on the heartbeat. */
    suspend fun isRegistered(): Boolean = store.read().isRegistered

    suspend fun hasToken(): Boolean = store.read().token != null

    private suspend fun uploadIfSignedIn(token: String): Boolean {
        if (!isSignedIn()) return false
        if (!upload(token)) return false
        // Re-read rather than reusing the earlier snapshot: a rotation can land mid-upload.
        val latest = store.read()
        if (latest.token == token) store.write(latest.copy(registeredToken = token))
        return latest.token == token
    }
}
