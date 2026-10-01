package ai.arthax.app.core

import ai.arthax.app.BuildConfig

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

        const val LEAD_DETAIL = "api/leads/{lead_id}"
        const val LEAD_JUNK = "api/leads/{lead_id}/junk"
        const val LEAD_FOLLOW_UP = "api/leads/{lead_id}/follow-up"
        const val LEAD_FOLLOW_UP_COMPLETE = "api/leads/{lead_id}/follow-up/complete"

        /** Org-defined pick lists; neither is a fixed set. */
        const val LEAD_STATUSES = "api/leads/statuses"
        const val LEAD_SOURCES = "api/leads/sources"
        const val LEAD_CUSTOM_FIELDS = "api/leads/custom-fields"
        const val LEAD_TIMELINE = "api/leads/{lead_id}/timeline"
        const val SYNC_HEALTH = "api/mobile/sync-health"
        const val MOBILE_CONFIG = "api/mobile/config"
        const val CALLS = "api/calls/"
        const val UPLOAD_RECORDING = "api/calls/{call_id}/upload-recording"

        /** Read side of [CALLS]: one call with its transcript and AI assessment. */
        const val CALL_DETAIL = "api/calls/{call_id}"

        const val MEETINGS = "api/meetings/"
        const val MEETING_DETAIL = "api/meetings/{meeting_id}"

        /** Status-only update; the new status rides as a query parameter, not a body. */
        const val MEETING_STATUS = "api/meetings/status/{meeting_id}"
    }

    /** Default page size when listing leads. The server caps and defaults to 100. */
    const val LEADS_PAGE_SIZE = 100

    /**
     * Call history page size. Smaller than the lead page because each row carries an AI
     * assessment block, so the response is far heavier per item.
     */
    const val CALLS_PAGE_SIZE = 20

    const val MEETINGS_PAGE_SIZE = 50

    /**
     * Timeline page. That route takes a `limit` and no offset, so "load more" asks for a
     * bigger page rather than the next one — see [ApiConfig.TIMELINE_PAGE_STEP].
     */
    const val TIMELINE_PAGE_SIZE = 50
    const val TIMELINE_PAGE_STEP = 25
    const val TIMELINE_MAX = 200

    /** Where the privacy policy lives; linked from the disclosure and from Settings. */
    const val PRIVACY_POLICY_URL = "https://arthax.ai/privacy"
}
