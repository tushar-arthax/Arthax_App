package ai.arthax.app.data.remote.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * POST /api/mobile/sync-health — one heartbeat.
 *
 * Field names mirror `MobileSyncHealthIn` on the backend exactly; the dashboard is built
 * against that schema, so a renamed key here is a column that silently reads as zero.
 * Every timestamp is ISO-8601 UTC with an explicit `Z`, formatted by [ApiTime.format].
 */
@JsonClass(generateAdapter = true)
data class MobileSyncHealthRequest(
    @Json(name = "device_id") val deviceId: String,
    @Json(name = "manufacturer") val manufacturer: String? = null,
    @Json(name = "model") val model: String? = null,
    @Json(name = "android_version") val androidVersion: String? = null,
    @Json(name = "app_version") val appVersion: String? = null,
    @Json(name = "app_version_code") val appVersionCode: Int? = null,
    @Json(name = "sent_at") val sentAt: String? = null,
    @Json(name = "observer_last_tick_at") val observerLastTickAt: String? = null,
    @Json(name = "last_call_posted_at") val lastCallPostedAt: String? = null,
    @Json(name = "last_upload_at") val lastUploadAt: String? = null,
    @Json(name = "calls_seen_24h") val callsSeen24h: Int = 0,
    @Json(name = "calls_posted_24h") val callsPosted24h: Int = 0,
    @Json(name = "calls_unmatched") val callsUnmatched: Int = 0,
    @Json(name = "uploads_pending") val uploadsPending: Int = 0,
    @Json(name = "files_waiting_for_call_log") val filesWaitingForCallLog: Int = 0,
    @Json(name = "review_queue") val reviewQueue: Int = 0,
    @Json(name = "errors_24h") val errors24h: Int = 0,
    @Json(name = "upload_blocked_until") val uploadBlockedUntil: String? = null,
    @Json(name = "auth_blocked_until") val authBlockedUntil: String? = null,
    @Json(name = "recorder_folder_found") val recorderFolderFound: Boolean = false,
    @Json(name = "permissions") val permissions: Map<String, Boolean>? = null,
    @Json(name = "last_error") val lastError: String? = null,
)

/** `{"ok": true, "config_version": 3}` — the version lets the app skip a config fetch. */
@JsonClass(generateAdapter = true)
data class SyncHealthAckDto(
    @Json(name = "ok") val ok: Boolean = true,
    @Json(name = "config_version") val configVersion: Int? = null,
)

/**
 * GET /api/mobile/config envelope. `config` is modelled loosely on purpose: every field is
 * optional and the typed [ai.arthax.app.core.MobileConfig] fills in the shipped
 * default for anything missing, so a newer or older backend can never make the app throw.
 */
@JsonClass(generateAdapter = true)
data class MobileConfigResponseDto(
    @Json(name = "version") val version: Int,
    @Json(name = "updated_at") val updatedAt: String? = null,
    @Json(name = "config") val config: MobileConfigDto? = null,
)

@JsonClass(generateAdapter = true)
data class MobileConfigDto(
    @Json(name = "lookback_hours") val lookbackHours: Int? = null,
    @Json(name = "sync_interval_minutes") val syncIntervalMinutes: Int? = null,
    @Json(name = "heartbeat_interval_minutes") val heartbeatIntervalMinutes: Int? = null,
    @Json(name = "ring_allowance_sec") val ringAllowanceSec: Int? = null,
    @Json(name = "max_duration_slack_sec") val maxDurationSlackSec: Int? = null,
    @Json(name = "unmatched_retention_days") val unmatchedRetentionDays: Int? = null,
    @Json(name = "by_phone_recheck_hours") val byPhoneRecheckHours: Int? = null,
    @Json(name = "extra_folders") val extraFolders: List<String>? = null,
    @Json(name = "recorder_profiles") val recorderProfiles: List<RecorderProfileDto>? = null,
)

@JsonClass(generateAdapter = true)
data class RecorderProfileDto(
    /** A regex, matched case-insensitively against Build.MANUFACTURER. */
    @Json(name = "manufacturer_match") val manufacturerMatch: String? = null,
    /** "from_answer" or "from_dial" — whether the recorder's audio includes ringing. */
    @Json(name = "duration_semantics") val durationSemantics: String? = null,
    @Json(name = "extensions") val extensions: List<String>? = null,
    /** Relative to external storage, e.g. "Recordings/Call". */
    @Json(name = "folders") val folders: List<String>? = null,
    @Json(name = "filename_patterns") val filenamePatterns: List<FilenamePatternDto>? = null,
)

/** Parsed for completeness; this app matches recordings by time and number, not by name. */
@JsonClass(generateAdapter = true)
data class FilenamePatternDto(
    @Json(name = "regex") val regex: String? = null,
    @Json(name = "timestamp_format") val timestampFormat: String? = null,
)
