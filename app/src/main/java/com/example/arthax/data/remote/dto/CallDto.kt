package com.example.arthax.data.remote.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * POST /api/calls/ — creates the call record. Every field is optional server-side, but we
 * always send lead_id, the outcome pair and the duration, because a call record with no
 * lead is useless to the CRM.
 */
@JsonClass(generateAdapter = true)
data class CallCreateRequest(
    @Json(name = "lead_id") val leadId: String,
    @Json(name = "outcome") val outcome: String,
    @Json(name = "call_status") val callStatus: String,
    @Json(name = "direction") val direction: String,
    @Json(name = "duration_seconds") val durationSeconds: Int,
    @Json(name = "notes") val notes: String? = null,
    @Json(name = "created_at") val createdAt: String? = null,
    /**
     * Our own idempotency handle. Generated once per call on the device and reused across
     * retries so a flaky network cannot create the same call twice in the CRM.
     */
    @Json(name = "external_id") val externalId: String? = null,
    /**
     * How the phone decided which lead this was: "click_to_call", "by_phone" or
     * "lead_cache". Optional server-side; older backends ignore it.
     */
    @Json(name = "match_source") val matchSource: String? = null,
)

/**
 * POST /api/calls/ response. The AI assessment block is large and irrelevant to this app —
 * only [id] is actually needed, to address the recording upload.
 */
@JsonClass(generateAdapter = true)
data class CallResponseDto(
    @Json(name = "id") val id: String,
    @Json(name = "lead_id") val leadId: String? = null,
    @Json(name = "agent_id") val agentId: String? = null,
    @Json(name = "call_status") val callStatus: String? = null,
    @Json(name = "outcome") val outcome: String? = null,
    @Json(name = "duration_seconds") val durationSeconds: Int = 0,
    @Json(name = "recording_url") val recordingUrl: String? = null,
    @Json(name = "external_id") val externalId: String? = null,
    @Json(name = "call_time") val callTime: String? = null,
    @Json(name = "created_at") val createdAt: String? = null,
)

/**
 * POST /api/calls/{id}/upload-recording response.
 *
 * The spec says this returns a bare "string"; it does not. Verified on staging it returns
 * this object, and `skipped` matters — the server can accept the request without storing
 * a new recording.
 */
@JsonClass(generateAdapter = true)
data class UploadRecordingResponse(
    @Json(name = "recording_url") val recordingUrl: String? = null,
    @Json(name = "message") val message: String? = null,
    @Json(name = "skipped") val skipped: Boolean = false,
)
