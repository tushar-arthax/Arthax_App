package ai.arthax.app.data.remote.api

import ai.arthax.app.core.ApiConfig
import ai.arthax.app.data.remote.dto.CallCreateRequest
import ai.arthax.app.data.remote.dto.CallHistoryDto
import ai.arthax.app.data.remote.dto.CallHistoryPageDto
import ai.arthax.app.data.remote.dto.CallResponseDto
import ai.arthax.app.data.remote.dto.MeetingCreateRequest
import ai.arthax.app.data.remote.dto.MeetingDto
import ai.arthax.app.data.remote.dto.MeetingPageDto
import ai.arthax.app.data.remote.dto.MeetingUpdateRequest
import ai.arthax.app.data.remote.dto.FcmTokenRequest
import ai.arthax.app.data.remote.dto.CompleteFollowUpRequest
import ai.arthax.app.data.remote.dto.FollowUpRequest
import ai.arthax.app.data.remote.dto.LeadCreateRequest
import ai.arthax.app.data.remote.dto.LeadCustomFieldDto
import ai.arthax.app.data.remote.dto.LeadTimelinePage
import ai.arthax.app.data.remote.dto.LeadDto
import ai.arthax.app.data.remote.dto.LeadOptionDto
import ai.arthax.app.data.remote.dto.LeadPageDto
import ai.arthax.app.data.remote.dto.LeadUpdateRequest
import ai.arthax.app.data.remote.dto.MarkJunkRequest
import ai.arthax.app.data.remote.dto.MobileConfigResponseDto
import ai.arthax.app.data.remote.dto.MobileSyncHealthRequest
import ai.arthax.app.data.remote.dto.SyncHealthAckDto
import ai.arthax.app.data.remote.dto.LoginRequest
import ai.arthax.app.data.remote.dto.SendOtpRequest
import ai.arthax.app.data.remote.dto.SendOtpResponse
import ai.arthax.app.data.remote.dto.TokenResponse
import ai.arthax.app.data.remote.dto.UploadRecordingResponse
import ai.arthax.app.data.remote.dto.UserDto
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
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
        /**
         * Appended, not inserted, and defaulted to null so it is simply not sent. Every
         * existing caller — including [ai.arthax.app.data.repository.LeadResolver] on the
         * call-matching path — keeps producing the exact request it produced before.
         */
        @Query("junk_category") junkCategory: String? = null,
    ): Response<LeadPageDto>

    /** One lead in full: classification, custom fields, the lot. */
    @GET(ApiConfig.Paths.LEAD_DETAIL)
    suspend fun getLead(@Path("lead_id") leadId: String): Response<LeadDto>

    @POST(ApiConfig.Paths.LEADS)
    suspend fun createLead(@Body body: LeadCreateRequest): Response<LeadDto>

    /** Partial update. Fields left null in the body are not sent, so they are not changed. */
    @PATCH(ApiConfig.Paths.LEAD_DETAIL)
    suspend fun updateLead(
        @Path("lead_id") leadId: String,
        @Body body: LeadUpdateRequest,
    ): Response<LeadDto>

    /**
     * These four answer with a bare JSON string rather than an object, so the body is taken
     * as raw [ResponseBody] — exactly as [logout] is — instead of being handed to Moshi for
     * a value nothing reads.
     */
    @DELETE(ApiConfig.Paths.LEAD_DETAIL)
    suspend fun deleteLead(@Path("lead_id") leadId: String): Response<ResponseBody>

    @POST(ApiConfig.Paths.LEAD_JUNK)
    suspend fun markLeadJunk(
        @Path("lead_id") leadId: String,
        @Body body: MarkJunkRequest,
    ): Response<ResponseBody>

    @POST(ApiConfig.Paths.LEAD_FOLLOW_UP)
    suspend fun setLeadFollowUp(
        @Path("lead_id") leadId: String,
        @Body body: FollowUpRequest,
    ): Response<ResponseBody>

    @POST(ApiConfig.Paths.LEAD_FOLLOW_UP_COMPLETE)
    suspend fun completeLeadFollowUp(
        @Path("lead_id") leadId: String,
        @Body body: CompleteFollowUpRequest,
    ): Response<ResponseBody>

    /**
     * The statuses and sources this organisation has defined. Both routes return the same
     * shape, and neither is a fixed set — which is why the filter chips are built from the
     * server's answer rather than from an enum in the app.
     */
    @GET(ApiConfig.Paths.LEAD_STATUSES)
    suspend fun getLeadStatuses(): Response<List<LeadOptionDto>>

    @GET(ApiConfig.Paths.LEAD_SOURCES)
    suspend fun getLeadSources(): Response<List<LeadOptionDto>>

    /** The extra columns this organisation has defined on a lead. */
    @GET(ApiConfig.Paths.LEAD_CUSTOM_FIELDS)
    suspend fun getLeadCustomFields(): Response<List<LeadCustomFieldDto>>

    /**
     * Everything that has happened to a lead, newest first.
     *
     * Paged by cursor, not by offset: the response carries `next_before`/`next_before_id`,
     * which go back as `before`/`before_id` to fetch the next, older page. A null cursor is
     * the end of the journey.
     */
    @GET(ApiConfig.Paths.LEAD_TIMELINE)
    suspend fun getLeadTimeline(
        @Path("lead_id") leadId: String,
        @Query("limit") limit: Int = ApiConfig.TIMELINE_PAGE_SIZE,
        /** Cursor into the next, older page. Both come straight back from the last one. */
        @Query("before") before: String? = null,
        @Query("before_id") beforeId: String? = null,
    ): Response<LeadTimelinePage>

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

    /**
     * The rep's call history, as the CRM holds it.
     *
     * Read-only, and entirely separate from [createCall] above even though both address
     * `api/calls/`. Nothing on this path can affect a call being delivered.
     *
     * `agentId` is what scopes the list to the signed-in rep: the endpoint is org-wide, so
     * omitting it would show a rep their colleagues' calls. `includeTranscript` is false for
     * the list — transcripts are long, and the list only needs to know one exists — and the
     * detail call below fetches the full thing.
     */
    @GET(ApiConfig.Paths.CALLS)
    suspend fun getCalls(
        @Query("agent_id") agentId: String? = null,
        @Query("lead_id") leadId: String? = null,
        @Query("outcome") outcome: String? = null,
        @Query("search") search: String? = null,
        @Query("date_from") dateFrom: String? = null,
        @Query("date_to") dateTo: String? = null,
        @Query("skip") skip: Int = 0,
        @Query("limit") limit: Int = ApiConfig.CALLS_PAGE_SIZE,
        @Query("include_transcript") includeTranscript: Boolean = false,
    ): Response<CallHistoryPageDto>

    /** One call, with its transcript and the full AI assessment. */
    @GET(ApiConfig.Paths.CALL_DETAIL)
    suspend fun getCall(@Path("call_id") callId: String): Response<CallHistoryDto>

    /**
     * Meetings booked against leads.
     *
     * Scoped by `agentId` for the same reason as the calls list above.
     */
    @GET(ApiConfig.Paths.MEETINGS)
    suspend fun getMeetings(
        @Query("status") status: String? = null,
        @Query("search") search: String? = null,
        @Query("agent_id") agentId: String? = null,
        @Query("lead_id") leadId: String? = null,
        @Query("skip") skip: Int = 0,
        @Query("limit") limit: Int = ApiConfig.MEETINGS_PAGE_SIZE,
    ): Response<MeetingPageDto>

    @POST(ApiConfig.Paths.MEETINGS)
    suspend fun createMeeting(@Body body: MeetingCreateRequest): Response<MeetingDto>

    @GET(ApiConfig.Paths.MEETING_DETAIL)
    suspend fun getMeeting(@Path("meeting_id") meetingId: String): Response<MeetingDto>

    /** Partial update. Fields left null in the body are not sent, so they are not changed. */
    @PATCH(ApiConfig.Paths.MEETING_DETAIL)
    suspend fun updateMeeting(
        @Path("meeting_id") meetingId: String,
        @Body body: MeetingUpdateRequest,
    ): Response<MeetingDto>

    /**
     * Status-only update. The status travels as a query parameter, which is why this cannot
     * simply be [updateMeeting] with one field set.
     *
     * [body] exists solely to satisfy OkHttp, which refuses to build a PATCH whose body is
     * null — `method PATCH must have a request body`, thrown at request time rather than at
     * compile time, so without this the call would fail only once a rep actually tapped a
     * status. An empty JSON object is sent and the server ignores it.
     */
    @PATCH(ApiConfig.Paths.MEETING_STATUS)
    suspend fun updateMeetingStatus(
        @Path("meeting_id") meetingId: String,
        @Query("status") status: String,
        @Body body: RequestBody = EMPTY_JSON_BODY,
    ): Response<MeetingDto>

    companion object {
        /** See [updateMeetingStatus]. */
        val EMPTY_JSON_BODY: RequestBody =
            "{}".toRequestBody("application/json".toMediaType())
    }

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
