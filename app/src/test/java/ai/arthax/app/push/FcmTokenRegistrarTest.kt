package ai.arthax.app.push

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The token and the sign-in arrive in either order, and the server must end up with the
 * current token exactly once per session — not on every start, and never a stale one.
 */
class FcmTokenRegistrarTest {

    private class MemoryStore : FcmTokenRegistrar.Store {
        var state = FcmTokenRegistrar.State()
        override suspend fun read() = state
        override suspend fun write(state: FcmTokenRegistrar.State) {
            this.state = state
        }
    }

    private val store = MemoryStore()
    private val uploads = mutableListOf<String>()
    private var uploadSucceeds = true
    private var signedIn = false

    private val registrar = FcmTokenRegistrar(
        store = store,
        upload = { token ->
            uploads += token
            uploadSucceeds
        },
        isSignedIn = { signedIn },
    )

    @Test
    fun `a token before sign-in is kept and sent at sign-in`() = runTest {
        assertFalse(registrar.register("tok-1"))
        assertTrue(uploads.isEmpty())
        assertEquals("tok-1", store.state.token)
        assertFalse(registrar.isRegistered())

        signedIn = true
        assertTrue(registrar.onSignedIn())
        assertEquals(listOf("tok-1"), uploads)
        assertTrue(registrar.isRegistered())
    }

    @Test
    fun `a token while signed in is sent straight away`() = runTest {
        signedIn = true
        assertTrue(registrar.register("tok-1"))
        assertEquals(listOf("tok-1"), uploads)
        assertTrue(registrar.isRegistered())
    }

    @Test
    fun `a token the server already has is not sent again`() = runTest {
        signedIn = true
        registrar.register("tok-1")
        registrar.register("tok-1")
        registrar.onSignedIn()

        assertEquals(listOf("tok-1"), uploads)
    }

    @Test
    fun `a rotated token is registered again`() = runTest {
        signedIn = true
        registrar.register("tok-1")
        assertTrue(registrar.register("tok-2"))

        assertEquals(listOf("tok-1", "tok-2"), uploads)
        assertEquals("tok-2", store.state.registeredToken)
    }

    @Test
    fun `a failed upload is retried at the next opportunity`() = runTest {
        signedIn = true
        uploadSucceeds = false
        assertFalse(registrar.register("tok-1"))
        assertFalse(registrar.isRegistered())

        uploadSucceeds = true
        assertTrue(registrar.onSignedIn())
        assertEquals(listOf("tok-1", "tok-1"), uploads)
        assertTrue(registrar.isRegistered())
    }

    @Test
    fun `signing out forgets the registration but keeps the token for the next rep`() = runTest {
        signedIn = true
        registrar.register("tok-1")
        registrar.onSigningOut()
        signedIn = false

        assertFalse(registrar.isRegistered())
        assertTrue(registrar.hasToken())

        signedIn = true
        assertTrue(registrar.onSignedIn())
        assertEquals(listOf("tok-1", "tok-1"), uploads)
    }

    @Test
    fun `nothing to send without a token`() = runTest {
        signedIn = true
        assertFalse(registrar.onSignedIn())
        assertFalse(registrar.register("   "))
        assertTrue(uploads.isEmpty())
    }
}
