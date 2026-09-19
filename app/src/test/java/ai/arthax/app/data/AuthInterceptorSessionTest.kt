package ai.arthax.app.data

import ai.arthax.app.data.remote.api.AuthInterceptor
import ai.arthax.app.data.remote.api.AuthInterceptor.SessionVerdict
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * When a 401 is allowed to end the session.
 *
 * Signing out is expensive: the rep loses their place, and every queued call waits until
 * they sign in again. So a 401 from an authenticated endpoint is confirmed against the
 * profile endpoint first, and only the server's own second refusal counts. The mapping is
 * pinned here, code by code.
 */
class AuthInterceptorSessionTest {

    @Test
    fun `the server refusing the token again ends the session`() {
        assertEquals(SessionVerdict.INVALID, AuthInterceptor.sessionVerdict(401))
        assertEquals(SessionVerdict.INVALID, AuthInterceptor.sessionVerdict(403))
    }

    @Test
    fun `a profile that still loads means the first 401 was transient`() {
        assertEquals(SessionVerdict.VALID, AuthInterceptor.sessionVerdict(200))
        assertEquals(SessionVerdict.VALID, AuthInterceptor.sessionVerdict(204))
    }

    @Test
    fun `an outage during the probe never signs the rep out`() {
        // No connection, a gateway error, a throttle: none of these say anything about
        // the token, so the session is kept and the original request fails as transient.
        assertEquals(SessionVerdict.UNKNOWN, AuthInterceptor.sessionVerdict(null))
        assertEquals(SessionVerdict.UNKNOWN, AuthInterceptor.sessionVerdict(500))
        assertEquals(SessionVerdict.UNKNOWN, AuthInterceptor.sessionVerdict(502))
        assertEquals(SessionVerdict.UNKNOWN, AuthInterceptor.sessionVerdict(429))
    }

    @Test
    fun `an unrelated rejection is inconclusive too`() {
        // A 404 from the profile route on an older backend is not a verdict on the token.
        assertEquals(SessionVerdict.UNKNOWN, AuthInterceptor.sessionVerdict(404))
    }
}
