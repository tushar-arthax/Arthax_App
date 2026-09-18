package com.example.arthax.data.remote.api

import com.example.arthax.core.ApiConfig
import com.example.arthax.data.remote.dto.CallCreateRequest
import com.example.arthax.data.remote.dto.CallResponseDto
import com.example.arthax.data.remote.dto.FcmTokenRequest
import com.example.arthax.data.remote.dto.LeadDto
import com.example.arthax.data.remote.dto.LeadPageDto
import com.example.arthax.data.remote.dto.MobileConfigResponseDto
import com.example.arthax.data.remote.dto.MobileSyncHealthRequest
import com.example.arthax.data.remote.dto.SyncHealthAckDto
import com.example.arthax.data.remote.dto.LoginRequest
import com.example.arthax.data.remote.dto.SendOtpRequest
import com.example.arthax.data.remote.dto.SendOtpResponse
import com.example.arthax.data.remote.dto.TokenResponse
import com.example.arthax.data.remote.dto.UploadRecordingResponse
import com.example.arthax.data.remote.dto.UserDto
import okhttp3.MultipartBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Multipart
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * The Arthax backend, as verified against staging.
 *
 * Everything returns `Response<T>` rather than a bare body so the repository layer can
 * tell 401 from 422 from 500 — which decides whether a failed upload is retried, dropped,
 * or triggers a re-login.
 */
interface ArthaxApi {

    @POST(ApiConfig.Paths.SEND_OTP)
    suspend fun sendOtp(@Body body: SendOtpRequest): Response<SendOtpResponse>

    @POST(ApiConfig.Paths.LOGIN)
    suspend fun login(@Body body: LoginRequest): Response<TokenResponse>

    /**
     * Ends the server-side session. Returns a bare JSON string, so the body is taken as raw
     * ResponseBody rather than run through Moshi for a value we do not use.
     */
    @POST(ApiConfig.Paths.LOGOUT)
    suspend fun logout(): Response<ResponseBody>

    @GET(ApiConfig.Paths.ME)
    suspend fun me(): Response<UserDto>

    @PATCH(ApiConfig.Paths.FCM_TOKEN)
    suspend fun updateFcmToken(@Body body: FcmTokenRequest): Response<Unit>

    /**
     * Leads for the signed-in rep.
     *
     * `isJunk` is nullable and deliberately not defaulted to false. The dialling list passes
     * false, because a junk lead should never be offered to call. Call *matching* passes
     * null, because a call the rep already made to a junk lead still belongs in the CRM -
     * filtering it out there silently discards a real call.
     */
    @GET(ApiConfig.Paths.LEADS)
    suspend fun getLeads(
        @Query("skip") skip: Int = 0,
        @Query("limit") limit: Int = ApiConfig.LEADS_PAGE_SIZE,
        @Query("is_junk") isJunk: Boolean? = null,
        @Query("search") search: String? = null,
        @Query("status") status: String? = null,
    ): Response<LeadPageDto>

    /**
     * "Who is this number?" — across the whole organisation, not just the rep's own list.
     *
     * The search above is scoped to the rep's leads, so a call to a colleague's lead or an
     * unassigned one came back empty and the call was thrown away. This answers for any
     * lead in the org: 200 with the lead, or 404 with detail "Lead not found for this phone".
     * Junk leads are returned too, with `is_junk` set, because a call that happened is a
     * call that happened.
     */
    @GET(ApiConfig.Paths.LEAD_BY_PHONE)
    suspend fun getLeadByPhone(@Query("phone") phone: String): Response<LeadDto>

    /** Step 1 of logging a call: create the record and get its id back. */
    @POST(ApiConfig.Paths.CALLS)
    suspend fun createCall(@Body body: CallCreateRequest): Response<CallResponseDto>

    /**
     * Step 2: attach the audio to the call created above.
     *
     * The server transcodes with FFmpeg and rejects anything it cannot decode, so this
     * must only ever be handed a complete, finished recording file.
     */
    @Multipart
    @POST(ApiConfig.Paths.UPLOAD_RECORDING)
    suspend fun uploadRecording(
        @Path("call_id") callId: String,
        @Part file: MultipartBody.Part,
    ): Response<UploadRecordingResponse>

    /** Hourly heartbeat for the fleet dashboard. The ack carries the current config version. */
    @POST(ApiConfig.Paths.SYNC_HEALTH)
    suspend fun postSyncHealth(@Body body: MobileSyncHealthRequest): Response<SyncHealthAckDto>

    /**
     * Server-driven tunables. Sent with `If-None-Match: "<version>"`, the server answers
     * 304 when nothing has changed — so the caller must look at the code before the body.
     */
    @GET(ApiConfig.Paths.MOBILE_CONFIG)
    suspend fun getMobileConfig(
        @Header("If-None-Match") ifNoneMatch: String? = null,
    ): Response<MobileConfigResponseDto>
}
