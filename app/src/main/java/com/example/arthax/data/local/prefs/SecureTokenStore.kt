package com.example.arthax.data.local.prefs

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Bearer token and signed-in rep identity, in EncryptedSharedPreferences (AES256-GCM under
 * a Keystore-backed master key).
 *
 * Failure policy: if the encrypted store cannot be opened — Keystore key invalidated by a
 * lock-screen change, or a backup restored onto different hardware — we wipe it and force a
 * re-login. We deliberately do NOT fall back to plaintext: losing a session is cheap,
 * leaking a token that can read a customer's whole lead list is not. That is also why
 * android:allowBackup is false.
 */
class SecureTokenStore(private val context: Context) {

    private val prefs: SharedPreferences by lazy { openOrRecreate() }

    private val _authState = MutableStateFlow(AuthState.UNKNOWN)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    enum class AuthState { UNKNOWN, AUTHENTICATED, LOGGED_OUT }

    data class Session(
        val userId: String,
        val fullName: String?,
        val phone: String?,
        val email: String?,
        val role: String?,
        val orgId: String?,
        val teamId: String?,
    )

    private fun openOrRecreate(): SharedPreferences = try {
        create()
    } catch (t: Throwable) {
        Log.w(TAG, "Encrypted store unreadable, wiping and forcing re-login", t)
        context.deleteSharedPreferences(FILE_NAME)
        // A second failure is unrecoverable and should crash loudly rather than silently
        // downgrade to keeping a bearer token in the clear.
        create()
    }

    private fun create(): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    /** Read synchronously — the OkHttp interceptor needs it off the main thread with no suspend. */
    val token: String? get() = prefs.getString(KEY_TOKEN, null)

    val isLoggedIn: Boolean get() = !token.isNullOrBlank()

    val session: Session?
        get() {
            val id = prefs.getString(KEY_USER_ID, null) ?: return null
            return Session(
                userId = id,
                fullName = prefs.getString(KEY_FULL_NAME, null),
                phone = prefs.getString(KEY_PHONE, null),
                email = prefs.getString(KEY_EMAIL, null),
                role = prefs.getString(KEY_ROLE, null),
                orgId = prefs.getString(KEY_ORG_ID, null),
                teamId = prefs.getString(KEY_TEAM_ID, null),
            )
        }

    fun saveSession(token: String, session: Session) {
        prefs.edit()
            .putString(KEY_TOKEN, token)
            .putString(KEY_USER_ID, session.userId)
            .putString(KEY_FULL_NAME, session.fullName)
            .putString(KEY_PHONE, session.phone)
            .putString(KEY_EMAIL, session.email)
            .putString(KEY_ROLE, session.role)
            .putString(KEY_ORG_ID, session.orgId)
            .putString(KEY_TEAM_ID, session.teamId)
            .commit()
        _authState.value = AuthState.AUTHENTICATED
    }

    fun clear() {
        prefs.edit().clear().commit()
        _authState.value = AuthState.LOGGED_OUT
    }

    /** Seeds [authState] on startup, before the first frame. */
    fun refreshAuthState() {
        _authState.value = if (isLoggedIn) AuthState.AUTHENTICATED else AuthState.LOGGED_OUT
    }

    private companion object {
        const val TAG = "SecureTokenStore"
        const val FILE_NAME = "arthax_secure_session"
        const val KEY_TOKEN = "auth_token"
        const val KEY_USER_ID = "user_id"
        const val KEY_FULL_NAME = "full_name"
        const val KEY_PHONE = "phone"
        const val KEY_EMAIL = "email"
        const val KEY_ROLE = "role"
        const val KEY_ORG_ID = "org_id"
        const val KEY_TEAM_ID = "team_id"
    }
}
