package ai.arthax.app.data.repository

import android.content.Context
import ai.arthax.app.call.CallLogReconciler
import ai.arthax.app.call.CallMonitorService
import dagger.hilt.android.qualifiers.ApplicationContext
import ai.arthax.app.core.PhoneNumbers
import ai.arthax.app.data.local.prefs.AppSettings
import ai.arthax.app.data.local.store.LeadLookupCache
import ai.arthax.app.data.local.prefs.SecureTokenStore
import ai.arthax.app.data.remote.api.ApiResult
import ai.arthax.app.data.remote.api.ArthaxApi
import ai.arthax.app.data.remote.api.safeApiCall
import ai.arthax.app.data.remote.dto.LoginRequest
import ai.arthax.app.data.remote.dto.SendOtpRequest
import ai.arthax.app.data.remote.dto.UserDto
import ai.arthax.app.domain.model.LogStage
import ai.arthax.app.push.PushRegistration
import ai.arthax.app.work.WorkScheduler
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: ArthaxApi,
    private val tokenStore: SecureTokenStore,
    private val lookupCache: LeadLookupCache,
    private val settings: AppSettings,
    private val workScheduler: WorkScheduler,
    private val reconciler: dagger.Lazy<CallLogReconciler>,
    private val push: dagger.Lazy<PushRegistration>,
    private val logger: EventLogger,
) {

    val authState: StateFlow<SecureTokenStore.AuthState> = tokenStore.authState

    val session: SecureTokenStore.Session? get() = tokenStore.session

    val isLoggedIn: Boolean get() = tokenStore.isLoggedIn

    /**
     * Requests a one-time code.
     *
     * Returns the server's own message, because it says something the rep needs and we
     * could not otherwise know: the code is delivered to their *registered email address*,
     * not by SMS to the number they just typed.
     */
    suspend fun sendOtp(phone: String): ApiResult<String> {
        val normalized = normalizePhone(phone)
        logger.info(LogStage.AUTH, "Requesting sign-in code for $normalized")

        return when (val result = safeApiCall { api.sendOtp(SendOtpRequest(normalized)) }) {
            is ApiResult.Success -> {
                val message = result.data.message?.takeIf { it.isNotBlank() }
                    ?: "Code sent to your registered email address"
                logger.success(LogStage.AUTH, message)
                ApiResult.Success(message)
            }

            is ApiResult.Failure -> {
                logger.error(LogStage.AUTH, "Could not send code: ${result.message}", detail = result.detail)
                result
            }
        }
    }

    suspend fun verifyOtp(phone: String, otp: String): ApiResult<Unit> {
        val normalized = normalizePhone(phone)

        return when (val result = safeApiCall { api.login(LoginRequest(phone = normalized, otp = otp)) }) {
            is ApiResult.Success -> {
                val body = result.data
                if (body.accessToken.isBlank()) {
                    logger.error(LogStage.AUTH, "Server accepted the code but returned no token")
                    return ApiResult.Failure.Unexpected("Sign-in failed, please try again")
                }

                tokenStore.saveSession(body.accessToken, body.user.toSession(normalized))

                // Armed here, at the moment of signing in, anchored to the newest call
                // that already existed. Doing it lazily on the first reconcile meant the
                // trigger for that reconcile - a finished call - was itself skipped.
                reconciler.get().armWatermark()
                workScheduler.ensurePeriodicWork()
                CallMonitorService.start(context, "signed in")

                // The push token usually arrived before the rep signed in; now there is a
                // session to register it under. Never blocks the sign-in.
                runCatching { push.get().onSignedIn() }

                logger.success(
                    LogStage.AUTH,
                    "Signed in as ${body.user?.fullName ?: normalized}",
                    detail = body.user?.role?.let { "Role: $it" },
                )
                ApiResult.Success(Unit)
            }

            is ApiResult.Failure -> {
                // A 401 here means the code was wrong, not that a session expired. Saying
                // "session expired" on the login screen would be nonsense.
                val mapped = if (result is ApiResult.Failure.Unauthorized) {
                    ApiResult.Failure.Rejected(
                        code = 401,
                        message = result.detail?.takeIf { it.isNotBlank() } ?: "Incorrect or expired code",
                        detail = result.detail,
                    )
                } else {
                    result
                }
                logger.error(LogStage.AUTH, "Sign-in failed: ${mapped.message}", detail = mapped.detail)
                mapped
            }
        }
    }

    /** Confirms the stored token is still good, and refreshes the cached profile. */
    suspend fun refreshProfile(): ApiResult<Unit> =
        when (val result = safeApiCall { api.me() }) {
            is ApiResult.Success -> {
                tokenStore.token?.let { token ->
                    tokenStore.saveSession(token, result.data.toSession(result.data.phone))
                }
                ApiResult.Success(Unit)
            }

            is ApiResult.Failure -> result
        }

    /**
     * Ends the session on the server, then wipes it locally.
     *
     * The local wipe happens in a `finally`-style unconditional step on purpose: if the
     * network call fails, the rep still asked to sign out, and trapping them signed in on a
     * shared handset because the server was unreachable would be both confusing and unsafe.
     * A stale server session expires on its own; a token left on the device does not.
     */
    suspend fun logout() {
        logger.info(LogStage.AUTH, "Signing out")

        // Before the bearer token goes: pushes for this rep must stop reaching a handset
        // someone else may pick up next. Failures are ignored — see PushRegistration.
        runCatching { push.get().onSignedOut() }

        when (val result = safeApiCall { api.logout() }) {
            is ApiResult.Success -> logger.success(LogStage.AUTH, "Session ended on the server")

            is ApiResult.Failure -> logger.warn(
                LogStage.AUTH,
                "Could not reach the server to end the session: ${result.message}",
                detail = "Signing out on this device anyway.",
            )
        }

        tokenStore.clear()

        // The next rep on this handset must not inherit this one's lead list, nor have
        // their calls matched against it. The pending upload queue is deliberately left
        // alone - it is tagged by owner and resumes when its own rep signs back in.
        workScheduler.cancelAll()
        CallMonitorService.stop(context)
        // The next rep on this handset must not have their calls matched against, or
        // silently ignored because of, the previous rep's cached answers.
        lookupCache.clear()
        settings.resetCallWatermark()

        logger.info(
            LogStage.AUTH,
            "Signed out - token, profile and cached lead lookups cleared from this device",
        )
    }

    fun refreshAuthState() = tokenStore.refreshAuthState()

    private fun UserDto?.toSession(fallbackPhone: String?): SecureTokenStore.Session =
        SecureTokenStore.Session(
            userId = this?.id.orEmpty(),
            fullName = this?.fullName,
            phone = this?.phone?.takeIf { it.isNotBlank() } ?: fallbackPhone,
            email = this?.email,
            role = this?.role,
            orgId = this?.orgId,
            teamId = this?.teamId,
        )

    /**
     * Digits only. The backend matches on the bare 10-digit number, so a leading +91 or the
     * spaces a rep typed would fail the lookup.
     */
    private fun normalizePhone(raw: String): String = PhoneNumbers.apiFormat(raw)
}
