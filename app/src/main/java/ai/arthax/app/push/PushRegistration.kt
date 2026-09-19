package ai.arthax.app.push

import android.content.Context
import ai.arthax.app.data.repository.EventLogger
import ai.arthax.app.domain.model.LogStage
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.tasks.Task
import com.google.firebase.messaging.FirebaseMessaging
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * The Firebase side of push registration — everything that needs Google Play services.
 *
 * Kept apart from [FcmTokenRegistrar] so the sequencing logic stays testable, and so a
 * phone without Google services degrades to "no push" rather than to a crash: every call
 * into Firebase here is wrapped, and the rest of the app never touches it directly.
 */
@Singleton
class PushRegistration @Inject constructor(
    @ApplicationContext private val context: Context,
    private val registrar: FcmTokenRegistrar,
    private val logger: EventLogger,
) {

    /** What Settings shows next to "Push". */
    enum class State { REGISTERED, NOT_SIGNED_IN, NO_GOOGLE_SERVICES, PENDING }

    fun hasGoogleServices(): Boolean = runCatching {
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
    }.getOrDefault(false)

    suspend fun state(isSignedIn: Boolean): State = when {
        !hasGoogleServices() -> State.NO_GOOGLE_SERVICES
        !isSignedIn -> State.NOT_SIGNED_IN
        registrar.isRegistered() -> State.REGISTERED
        else -> State.PENDING
    }

    /**
     * App start. Firebase normally hands the token to the service's onNewToken on first
     * run, but not if that first run was killed before it could be persisted — so a
     * missing token is asked for explicitly, and a stored one is re-offered in case the
     * upload is still owed.
     */
    suspend fun ensureRegistered() {
        if (!hasGoogleServices()) return
        val token = if (registrar.hasToken()) null else fetchToken()
        val registered = if (token != null) registrar.register(token) else registrar.onSignedIn()
        if (registered) logger.info(LogStage.SETUP, "Push notifications registered for this phone")
    }

    /** Firebase issued or rotated the token. */
    suspend fun onNewToken(token: String) {
        val registered = registrar.register(token)
        logger.info(
            LogStage.SETUP,
            if (registered) "Push token registered with the server" else "Push token saved; registered at next sign-in",
        )
    }

    /** Just signed in: flush a token that was waiting. */
    suspend fun onSignedIn() {
        if (registrar.onSignedIn()) logger.info(LogStage.SETUP, "Push notifications registered for this phone")
    }

    /**
     * Signing out. The server keeps the token on the session it just closed, and Firebase
     * would keep delivering to it — so the token itself is deleted, which makes the old
     * one undeliverable and has Firebase issue a fresh one for the next sign-in. Failures
     * are ignored: signing out must never be blocked on Google.
     */
    suspend fun onSignedOut() {
        registrar.onSigningOut()
        if (!hasGoogleServices()) return
        runCatching { FirebaseMessaging.getInstance().deleteToken().await() }
            .onFailure { if (it is CancellationException) throw it }
    }

    private suspend fun fetchToken(): String? = runCatching {
        FirebaseMessaging.getInstance().token.await()?.takeIf { it.isNotBlank() }
    }.getOrElse {
        if (it is CancellationException) throw it
        logger.warn(
            LogStage.SETUP,
            "Could not get a push token — calls requested from the CRM will not reach this phone",
            detail = "${it::class.java.simpleName}: ${it.message.orEmpty()}",
        )
        null
    }

    /** The Play services Task API, as a suspend call, without pulling in another library. */
    private suspend fun <T> Task<T>.await(): T? = suspendCancellableCoroutine { continuation ->
        addOnCompleteListener { task ->
            if (!continuation.isActive) return@addOnCompleteListener
            if (task.isSuccessful) {
                continuation.resume(task.result)
            } else {
                continuation.cancel(task.exception ?: RuntimeException("Task failed"))
            }
        }
    }
}
