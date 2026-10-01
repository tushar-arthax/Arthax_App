package ai.arthax.app.data.repository

import ai.arthax.app.core.ApiConfig
import ai.arthax.app.data.remote.api.ApiResult
import ai.arthax.app.data.remote.api.ArthaxApi
import ai.arthax.app.data.remote.api.safeApiCall
import ai.arthax.app.data.remote.dto.ApiTime
import ai.arthax.app.data.remote.dto.CompleteFollowUpRequest
import ai.arthax.app.data.remote.dto.FollowUpRequest
import ai.arthax.app.data.remote.dto.LeadCreateRequest
import ai.arthax.app.data.remote.dto.LeadOptionDto
import ai.arthax.app.data.remote.dto.LeadUpdateRequest
import ai.arthax.app.data.remote.dto.MarkJunkRequest
import ai.arthax.app.data.remote.dto.toDomain
import ai.arthax.app.domain.model.JunkCategory
import ai.arthax.app.domain.model.Lead
import ai.arthax.app.domain.model.LeadCustomField
import ai.arthax.app.domain.model.LeadOption
import ai.arthax.app.domain.model.LeadTimelineEvent
import ai.arthax.app.domain.model.LogStage
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LeadsRepository @Inject constructor(
    private val api: ArthaxApi,
    private val logger: EventLogger,
) {

    /**
     * One page of a lead's journey, with the cursor for the next one.
     *
     * [hasMore] comes from the server's own cursor rather than from counting rows against
     * the limit: the two disagree whenever a page is trimmed, and guessing wrong either
     * hides the rest of the history or offers a "show earlier" that returns nothing.
     */
    data class TimelinePage(
        val events: List<LeadTimelineEvent>,
        val nextBefore: String?,
        val nextBeforeId: String?,
    ) {
        val hasMore: Boolean get() = nextBefore != null || nextBeforeId != null
    }

    data class Page(
        /** Callable leads only — rows with no phone number are dropped. */
        val leads: List<Lead>,
        val total: Int,
        /**
         * How many rows the *server* returned, before the phone-number filter.
         *
         * Paging must advance by this, not by [leads].size: `skip` is a server-side offset,
         * so advancing by the filtered count would re-request rows we already dropped and
         * loop on a page full of phone-less leads.
         */
        val fetchedCount: Int,
        val nextSkip: Int,
        val hasMore: Boolean,
    )

    /**
     * One page of the rep's leads, straight from the server — there is no local cache, so
     * what the rep sees is always what the CRM holds.
     *
     * Search is sent to the server rather than filtered on device, so it covers the whole
     * assigned list instead of only the pages already loaded.
     */
    suspend fun fetchLeads(
        skip: Int = 0,
        limit: Int = ApiConfig.LEADS_PAGE_SIZE,
        search: String? = null,
        status: String? = null,
        /**
         * False by default — the dialling list must never offer a junk lead — but the
         * Junk filter needs to ask for exactly those, so it can be overridden.
         */
        isJunk: Boolean? = false,
        /**
         * Keep rows with no phone number.
         *
         * The dialling list drops them, because a CALL button that cannot dial is worse
         * than an absent row. The lead *browser* keeps them: a lead whose number is missing
         * is precisely the one a rep needs to open and fix.
         */
        includeUncallable: Boolean = false,
    ): ApiResult<Page> {
        val query = search?.trim()?.takeIf { it.isNotEmpty() }
        val statusFilter = status?.trim()?.takeIf { it.isNotEmpty() }

        val result = safeApiCall {
            api.getLeads(
                skip = skip,
                limit = limit,
                isJunk = isJunk,
                search = query,
                status = statusFilter,
            )
        }

        return when (result) {
            is ApiResult.Success -> {
                val body = result.data
                val all = body.items.map { it.toDomain() }

                // A lead with no number cannot be dialled, and showing a dead CALL button is
                // worse than leaving the row out and saying why.
                val callable = if (includeUncallable) all else all.filter { it.isCallable }
                val dropped = all.size - callable.size
                if (dropped > 0) {
                    logger.warn(LogStage.SYNC, "$dropped lead(s) hidden — no phone number on the record")
                }

                val nextSkip = skip + all.size

                logger.info(
                    LogStage.SYNC,
                    buildString {
                        append("Loaded ${callable.size} lead(s)")
                        if (query != null) append(" matching \"$query\"")
                        if (skip > 0) append(" (page from $skip)")
                    },
                    detail = "Server reports ${body.total} total; fetched ${all.size} this page",
                )

                ApiResult.Success(
                    Page(
                        leads = callable,
                        total = body.total,
                        fetchedCount = all.size,
                        nextSkip = nextSkip,
                        // Trust the row count as well as the total: a server that reports a
                        // stale total must not make the list request the same page forever.
                        hasMore = all.isNotEmpty() && nextSkip < body.total,
                    ),
                )
            }

            is ApiResult.Failure -> {
                logger.error(LogStage.SYNC, "Could not load leads: ${result.message}", detail = result.detail)
                result
            }
        }
    }

    /** One lead in full — classification, custom fields, everything the list leaves out. */
    suspend fun fetchLead(leadId: String): ApiResult<Lead> =
        when (val result = safeApiCall { api.getLead(leadId) }) {
            is ApiResult.Success -> ApiResult.Success(result.data.toDomain())
            is ApiResult.Failure -> {
                logger.error(
                    LogStage.SYNC,
                    "Could not load the lead: ${result.message}",
                    leadId = leadId,
                    detail = result.detail,
                )
                result
            }
        }

    /**
     * Applies an edit and returns the server's own version of the lead.
     *
     * The response is used rather than the local edit, because the server normalises text,
     * re-runs classification and stamps `last_contacted_date` — so echoing back what was
     * typed would leave the screen subtly out of step with the CRM.
     */
    suspend fun updateLead(leadId: String, update: LeadUpdateRequest): ApiResult<Lead> =
        when (val result = safeApiCall { api.updateLead(leadId, update) }) {
            is ApiResult.Success -> {
                val lead = result.data.toDomain()
                logger.success(
                    LogStage.SYNC,
                    "Updated ${lead.name}",
                    leadId = lead.id,
                    leadName = lead.name,
                )
                ApiResult.Success(lead)
            }

            is ApiResult.Failure -> {
                logger.error(
                    LogStage.SYNC,
                    "Could not update the lead: ${result.message}",
                    leadId = leadId,
                    detail = result.detail,
                )
                result
            }
        }

    suspend fun createLead(request: LeadCreateRequest): ApiResult<Lead> =
        when (val result = safeApiCall { api.createLead(request) }) {
            is ApiResult.Success -> {
                val lead = result.data.toDomain()
                logger.success(
                    LogStage.SYNC,
                    "Added lead ${lead.name}",
                    leadId = lead.id,
                    leadName = lead.name,
                )
                ApiResult.Success(lead)
            }

            is ApiResult.Failure -> {
                logger.error(
                    LogStage.SYNC,
                    "Could not add the lead: ${result.message}",
                    detail = result.detail,
                )
                result
            }
        }

    /**
     * Deletes a lead from the CRM.
     *
     * Note what this does *not* touch: calls already logged against the lead stay in the
     * CRM, and anything still queued on this phone is delivered as normal. The delete is
     * the org's record of the person, not of the work done on them.
     */
    suspend fun deleteLead(leadId: String, leadName: String): ApiResult<Unit> =
        when (val result = safeApiCall { api.deleteLead(leadId) }) {
            is ApiResult.Success -> {
                result.data.close()
                logger.warn(LogStage.SYNC, "Deleted lead $leadName", leadId = leadId, leadName = leadName)
                ApiResult.Success(Unit)
            }

            is ApiResult.Failure -> {
                logger.error(
                    LogStage.SYNC,
                    "Could not delete the lead: ${result.message}",
                    leadId = leadId,
                    detail = result.detail,
                )
                result
            }
        }

    suspend fun markJunk(
        leadId: String,
        leadName: String,
        category: JunkCategory,
        reason: String?,
    ): ApiResult<Unit> {
        val request = MarkJunkRequest(
            junkCategory = category.api,
            junkReason = reason?.trim()?.takeIf { it.isNotEmpty() },
        )

        return when (val result = safeApiCall { api.markLeadJunk(leadId, request) }) {
            is ApiResult.Success -> {
                result.data.close()
                logger.warn(
                    LogStage.SYNC,
                    "Marked $leadName as junk — ${category.label.lowercase()}",
                    leadId = leadId,
                    leadName = leadName,
                    detail = reason,
                )
                ApiResult.Success(Unit)
            }

            is ApiResult.Failure -> {
                logger.error(
                    LogStage.SYNC,
                    "Could not mark the lead as junk: ${result.message}",
                    leadId = leadId,
                    detail = result.detail,
                )
                result
            }
        }
    }

    /**
     * Sets or moves the lead's next follow-up.
     *
     * The time is sent with an explicit UTC offset by [ApiTime], so a follow-up set for
     * 9 am does not arrive five and a half hours out.
     */
    suspend fun setFollowUp(
        leadId: String,
        leadName: String,
        scheduledAtMillis: Long,
        note: String?,
    ): ApiResult<Unit> {
        val request = FollowUpRequest(
            scheduledAt = ApiTime.format(scheduledAtMillis),
            note = note?.trim()?.takeIf { it.isNotEmpty() },
        )

        return when (val result = safeApiCall { api.setLeadFollowUp(leadId, request) }) {
            is ApiResult.Success -> {
                result.data.close()
                logger.success(
                    LogStage.SYNC,
                    "Follow-up set for $leadName",
                    leadId = leadId,
                    leadName = leadName,
                    detail = note,
                )
                ApiResult.Success(Unit)
            }

            is ApiResult.Failure -> {
                logger.error(
                    LogStage.SYNC,
                    "Could not set the follow-up: ${result.message}",
                    leadId = leadId,
                    detail = result.detail,
                )
                result
            }
        }
    }

    suspend fun completeFollowUp(
        leadId: String,
        leadName: String,
        outcome: String,
        note: String?,
    ): ApiResult<Unit> {
        val request = CompleteFollowUpRequest(
            outcome = outcome,
            outcomeNote = note?.trim()?.takeIf { it.isNotEmpty() },
        )

        return when (val result = safeApiCall { api.completeLeadFollowUp(leadId, request) }) {
            is ApiResult.Success -> {
                result.data.close()
                logger.success(
                    LogStage.SYNC,
                    "Follow-up completed for $leadName",
                    leadId = leadId,
                    leadName = leadName,
                    detail = note,
                )
                ApiResult.Success(Unit)
            }

            is ApiResult.Failure -> {
                logger.error(
                    LogStage.SYNC,
                    "Could not complete the follow-up: ${result.message}",
                    leadId = leadId,
                    detail = result.detail,
                )
                result
            }
        }
    }

    /**
     * The statuses this organisation has defined, for the filter row.
     *
     * Returns the failure rather than hiding it. These are set per org by its own admin, so
     * an empty row is either "this org defined none" or "the request failed" \u2014 and those
     * need to look different to whoever is trying to work out why the filters are missing.
     */
    suspend fun fetchStatuses(): ApiResult<List<LeadOption>> =
        fetchOptions("lead statuses") { api.getLeadStatuses() }

    suspend fun fetchSources(): ApiResult<List<LeadOption>> =
        fetchOptions("lead sources") { api.getLeadSources() }

    private suspend fun fetchOptions(
        what: String,
        call: suspend () -> retrofit2.Response<List<LeadOptionDto>>,
    ): ApiResult<List<LeadOption>> = when (val result = safeApiCall(call)) {
        is ApiResult.Success -> {
            // An entry with no usable text is dropped on its own. It cannot become a chip —
            // there would be nothing written on it and nothing to send as `?status=` — but
            // it must not cost the entries beside it, which is what used to happen one level
            // down when the DTO required its fields. See LeadOptionDto.
            val options = result.data.mapNotNull { dto ->
                dto.nameOrNull?.let { name ->
                    LeadOption(
                        // The id is identity only; nothing is ever sent back to the server
                        // with it, so a server that omits it still gets working chips.
                        id = dto.id?.takeIf { it.isNotBlank() } ?: name,
                        api = name,
                        isDefault = dto.isDefault == true,
                    )
                }
            }

            val dropped = result.data.size - options.size
            logger.info(
                LogStage.SYNC,
                "Loaded ${options.size} $what",
                detail = buildString {
                    append(
                        options.joinToString(", ") { it.api }
                            .ifBlank { "The server returned none." },
                    )
                    if (dropped > 0) append(" ($dropped with no name were skipped.)")
                },
            )
            ApiResult.Success(options)
        }

        is ApiResult.Failure -> {
            // Logged, not swallowed. This used to return an empty list on any failure, which
            // showed a rep an empty filter row with no way \u2014 for them or for support \u2014 to
            // find out whether the org has no statuses or the request simply failed.
            logger.error(
                LogStage.SYNC,
                "Could not load $what: ${result.message}",
                detail = result.detail,
            )
            result
        }
    }

    /** The extra columns this org has defined. Inactive ones are dropped. */
    suspend fun fetchCustomFields(): List<LeadCustomField> =
        when (val result = safeApiCall { api.getLeadCustomFields() }) {
            is ApiResult.Success -> result.data
                .filter { it.isActive != false }
                .map {
                    LeadCustomField(
                        id = it.id,
                        name = it.name.trim(),
                        type = it.fieldType?.trim()?.takeIf { t -> t.isNotEmpty() },
                        options = it.options.orEmpty().mapNotNull { o ->
                            o.trim().takeIf { t -> t.isNotEmpty() }
                        },
                    )
                }

            is ApiResult.Failure -> emptyList()
        }

    /**
     * Everything that has happened to a lead.
     *
     * Returns an empty list on any failure rather than an [ApiResult], because this route's
     * schema is documented only as `"string"` — the shape being parsed is inferred from its
     * description. A mismatch must degrade to "no timeline" and leave the rest of the lead
     * screen working, not surface as an error across it.
     */
    suspend fun fetchTimeline(
        leadId: String,
        limit: Int,
        before: String? = null,
        beforeId: String? = null,
    ): ApiResult<TimelinePage> =
        when (val result = safeApiCall { api.getLeadTimeline(leadId, limit, before, beforeId) }) {
            is ApiResult.Success -> {
                val events = result.data.events
                    // The route may not carry ids; the position is a stable enough key for
                    // a page that is only ever replaced or appended wholesale.
                    .mapIndexed { index, dto -> dto.toDomain(fallbackId = "$leadId-$index") }
                    .filterNot { it.isEmpty }

                logger.info(
                    LogStage.SYNC,
                    "Loaded ${events.size} timeline events",
                    leadId = leadId,
                    detail = if (result.data.events.size != events.size) {
                        "${result.data.events.size} returned, ${events.size} had anything to show."
                    } else {
                        null
                    },
                )
                ApiResult.Success(
                    TimelinePage(
                        events = events,
                        nextBefore = result.data.nextBefore?.takeIf { it.isNotBlank() },
                        nextBeforeId = result.data.nextBeforeId?.takeIf { it.isNotBlank() },
                    ),
                )
            }

            // Returned rather than flattened to an empty list. This used to answer a failure
            // with `emptyList()`, and because the journey rail draws nothing when it has no
            // events, a timeline that failed and a lead with no history were the same blank
            // space — with no way for the rep or for support to tell which.
            is ApiResult.Failure -> {
                logger.error(
                    LogStage.SYNC,
                    "Could not load the lead timeline: ${result.message}",
                    leadId = leadId,
                    detail = result.detail,
                )
                result
            }
        }
}
