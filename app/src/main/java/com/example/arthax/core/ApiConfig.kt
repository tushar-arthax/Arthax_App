package com.example.arthax.core

import com.example.arthax.BuildConfig

/**
 * The one and only place the backend location is defined.
 *
 * The literal lives in app/build.gradle.kts as a per-build-type BuildConfig field, so
 * staging and production can never be confused at runtime, and nothing else in the app
 * ever writes a host name. Point a new environment here and the whole app follows.
 */
object ApiConfig {

    /** Root URL including the trailing slash Retrofit requires. */
    const val BASE_URL: String = BuildConfig.API_BASE_URL

    /** Shown on the Settings screen so a rep can tell support which backend they are on. */
    val environmentLabel: String = BuildConfig.API_ENVIRONMENT

    /**
     * Endpoint paths, relative to [BASE_URL]. Kept together so the API surface is
     * readable in one screen and a backend rename is a one-line change.
     */
    object Paths {
        const val SEND_OTP = "api/auth/mobile/send-otp"
        const val LOGIN = "api/auth/mobile/login"
        const val LOGOUT = "api/auth/logout"
        const val ME = "api/users/me"
        const val FCM_TOKEN = "api/users/me/fcm-token"
        const val LEADS = "api/leads/"

        /** Org-wide "who is this number" lookup; the list above is scoped to the rep. */
        const val LEAD_BY_PHONE = "api/leads/by-phone"
        const val SYNC_HEALTH = "api/mobile/sync-health"
        const val MOBILE_CONFIG = "api/mobile/config"
        const val CALLS = "api/calls/"
        const val UPLOAD_RECORDING = "api/calls/{call_id}/upload-recording"
    }

    /** Default page size when listing leads. The server caps and defaults to 100. */
    const val LEADS_PAGE_SIZE = 100

    /** Where the privacy policy lives; linked from the disclosure and from Settings. */
    const val PRIVACY_POLICY_URL = "https://arthax.ai/privacy"
}
