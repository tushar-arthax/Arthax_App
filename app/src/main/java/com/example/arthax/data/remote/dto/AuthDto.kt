package com.example.arthax.data.remote.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Shapes verified against staging-api.arthax.ai, not against the docs — the two disagree
 * in a few places and the server wins. Notably send-otp returns an object with a message,
 * not the bare "string" the spec shows.
 */

@JsonClass(generateAdapter = true)
data class SendOtpRequest(
    @Json(name = "phone") val phone: String,
)

@JsonClass(generateAdapter = true)
data class SendOtpResponse(
    // Real response: {"message":"OTP sent successfully to your registered email address"}
    @Json(name = "message") val message: String? = null,
)

/**
 * The login endpoint accepts email/password as well as phone/otp, and takes the FCM token
 * inline. Sending it here saves a second round trip to PATCH /users/me/fcm-token.
 */
@JsonClass(generateAdapter = true)
data class LoginRequest(
    @Json(name = "phone") val phone: String,
    @Json(name = "otp") val otp: String,
    @Json(name = "fcm_token") val fcmToken: String? = null,
)

@JsonClass(generateAdapter = true)
data class TokenResponse(
    @Json(name = "access_token") val accessToken: String,
    @Json(name = "token_type") val tokenType: String? = null,
    @Json(name = "user") val user: UserDto? = null,
)

@JsonClass(generateAdapter = true)
data class UserDto(
    @Json(name = "id") val id: String,
    @Json(name = "email") val email: String? = null,
    @Json(name = "full_name") val fullName: String? = null,
    @Json(name = "phone") val phone: String? = null,
    @Json(name = "role") val role: String? = null,
    @Json(name = "team_id") val teamId: String? = null,
    @Json(name = "org_id") val orgId: String? = null,
    @Json(name = "is_active") val isActive: Boolean = true,
)

@JsonClass(generateAdapter = true)
data class FcmTokenRequest(
    @Json(name = "fcm_token") val fcmToken: String,
)
