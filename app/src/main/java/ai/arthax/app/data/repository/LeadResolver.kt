package ai.arthax.app.data.repository

import ai.arthax.app.core.PhoneNumbers
import ai.arthax.app.data.local.prefs.SecureTokenStore
import ai.arthax.app.data.local.store.LeadLookupCache
import ai.arthax.app.data.remote.api.ApiResult
import ai.arthax.app.data.remote.api.ArthaxApi
import ai.arthax.app.data.remote.api.safeApiCall
import ai.arthax.app.data.remote.dto.LeadDto
import ai.arthax.app.domain.model.LogStage
import ai.arthax.app.domain.model.MatchSource
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Answers "does this phone number belong to one of our leads?".
 *
 * The CRM is asked every single time, and that is the point. The answer is live: a lead
 * added a minute before the call is found, and a lead deleted or reassigned in the CRM stops
 * being matched from that moment on. Nothing is ever bulk downloaded — one small request per
 * call, and it behaves identically against a CRM of a hundred leads or ten million.
 *
 * The question goes to `GET /api/leads/by-phone`, which looks across the whole organisation.
 * The lead *search* it replaced was scoped to the rep's own list, so a call to a colleague's
 * lead, or to one nobody had been assigned yet, came back "not a lead" and was thrown away
 * — recording, duration, outcome, gone. The search is kept only as a fallback for a backend
 * old enough not to have the endpoint at all.
 *
 * The local list is a fallback for one situation only: the CRM could not be reached.
 */
@Singleton
class LeadResolver @Inject constructor(
    private val api: ArthaxApi,
    private val cache: LeadLookupCache,
    private val tokenStore: SecureTokenStore,
    private val logger: EventLogger,
) {

    sealed interface Resolution {
        data class Lead(
            val id: String,
            val name: String,
            val source: MatchSource,
            /**
             * Reported, never acted on. A call already made to a junk lead is still a real
             * call and belongs in the CRM; filtering it out here would silently discard it.
             */
            val isJunk: Boolean = false,
        ) : Resolution {
            val fromCache: Boolean get() = source == MatchSource.LEAD_CACHE
        }

        /**
         * The CRM says this number is not a lead *right now*. The call is parked in the
         * unmatched list and asked about again later — never dropped, because a stranger
         * this morning is often a lead by the afternoon.
         *
         * Only ever a live answer. Nothing is parked on the strength of something remembered.
         */
        data object NotALead : Resolution

        /**
         * Could not be determined — offline, or the server errored. Deliberately distinct
         * from [NotALead]: a call we could not classify must be retried, never discarded,
         * or a genuine lead call would be lost the moment the rep walked into a lift.
         */
        data class Unavailable(val reason: String) : Resolution
    }

    /** What one `by-phone` round trip meant. Pure, so the mapping is pinned by a unit test. */
    sealed interface ByPhoneOutcome {
        data class Found(val lead: LeadDto) : ByPhoneOutcome

        /** The endpoint answered and said no. */
        data object NotALead : ByPhoneOutcome

        /** A 404 without the endpoint's own wording: an older backend that lacks the route. */
        data object EndpointMissing : ByPhoneOutcome

        data class Unavailable(val failure: ApiResult.Failure) : ByPhoneOutcome
    }

    suspend fun resolve(rawNumber: String?): Resolution {
        val key = PhoneNumbers.matchKey(rawNumber)
        // Withheld, unknown or malformed numbers cannot belong to anyone.
        if (key.isEmpty()) return Resolution.NotALead

        if (!tokenStore.isLoggedIn) return Resolution.Unavailable("not signed in")

        return when (val outcome = classifyByPhone(safeApiCall { api.getLeadByPhone(key) })) {
            is ByPhoneOutcome.Found -> accept(rawNumber, outcome.lead)
            ByPhoneOutcome.NotALead -> reject(rawNumber)
            ByPhoneOutcome.EndpointMissing -> resolveBySearch(rawNumber, key)
            is ByPhoneOutcome.Unavailable -> fallBack(rawNumber, outcome.failure)
        }
    }

    /**
     * The offline answer only — no network. Used on every reconcile pass for calls still
     * waiting for a lead, where a server round trip per row is rationed but a look at the
     * local list is free.
     */
    fun resolveFromCache(rawNumber: String?): Resolution.Lead? {
        val remembered = cache.peek(rawNumber)
        if (remembered.verdict != LeadLookupCache.Verdict.LEAD) return null
        return Resolution.Lead(
            id = remembered.leadId!!,
            name = remembered.leadName.orEmpty(),
            source = MatchSource.LEAD_CACHE,
        )
    }

    private suspend fun accept(rawNumber: String?, lead: LeadDto): Resolution {
        val name = lead.name.trim().ifBlank { "Unnamed lead" }
        cache.rememberLead(rawNumber, lead.id, name)
        if (lead.isJunk) {
            logger.warn(
                LogStage.SYNC,
                "This number belongs to a lead marked junk — the call is still logged",
                leadId = lead.id,
                leadName = name,
            )
        }
        return Resolution.Lead(lead.id, name, MatchSource.BY_PHONE, isJunk = lead.isJunk)
    }

    private suspend fun reject(rawNumber: String?): Resolution {
        // A live "no", and it overrides anything remembered. This is what makes a lead
        // deleted or reassigned in the CRM stop being matched at once, rather than going on
        // collecting calls from the offline list.
        cache.forget(rawNumber)
        return Resolution.NotALead
    }

    /**
     * The pre-`by-phone` path, for a backend that predates the endpoint. Scoped to the
     * rep's own leads, which is exactly the weakness the new route fixes — so this is a
     * last resort, not an alternative.
     */
    private suspend fun resolveBySearch(rawNumber: String?, key: String): Resolution {
        val result = safeApiCall {
            api.getLeads(
                skip = 0,
                limit = SEARCH_LIMIT,
                // Null, not false: a call already made to a lead later marked junk is still
                // a real call and belongs in the CRM. Verified against staging - filtering
                // on is_junk=false made such a call resolve to "not a lead" and vanish.
                isJunk = null,
                search = key,
                status = null,
            )
        }

        return when (result) {
            is ApiResult.Success -> {
                val match = pickMatch(result.data.items, key)
                if (match == null) reject(rawNumber) else accept(rawNumber, match)
            }

            is ApiResult.Failure -> fallBack(rawNumber, result)
        }
    }

    /**
     * What to do when the CRM could not be asked at all.
     *
     * A number already known to be a lead is still treated as one, because the recording is
     * the part that cannot be recovered later — OEM recorders prune their own folders, so
     * capturing the audio now against the last known lead beats waiting for a network and
     * finding the file gone. The call itself is still delivered to the CRM afterwards.
     *
     * Everything else is held, never guessed at. An outage must never look like "not a lead",
     * or a genuine call would be dropped the moment a rep stepped into a lift.
     */
    private fun fallBack(rawNumber: String?, failure: ApiResult.Failure): Resolution {
        val remembered = resolveFromCache(rawNumber)

        if (remembered != null) {
            logger.warn(
                LogStage.SYNC,
                "Matched a call from the offline list — the CRM could not be reached",
                leadId = remembered.id,
                leadName = remembered.name,
                detail = "The recording is being captured now rather than risked. " +
                    "Reason: ${failure.detail ?: failure.message}",
            )
            return remembered
        }

        logger.warn(
            LogStage.SYNC,
            "Could not check whether a call was to a lead: ${failure.message}",
            detail = "The call is kept and will be checked again as soon as there is a " +
                "connection. Reason: ${failure.detail ?: failure.message}",
        )
        return Resolution.Unavailable(failure.message)
    }

    /**
     * Chooses the lead a number really belongs to, from a *search* result.
     *
     * The endpoint is a fuzzy text search across several columns, so the first item is not
     * necessarily — or even usually — a phone match: a lead whose notes happen to contain
     * those digits ranks just as well. Taking `items.first()` would file a call against the
     * wrong customer, and nothing downstream would ever notice.
     *
     * So every candidate has its own phone number compared, and anything that does not match
     * is discarded no matter where it ranked.
     */
    private fun pickMatch(items: List<LeadDto>, key: String): LeadDto? {
        val phoneMatches = items.filter { PhoneNumbers.matchKey(it.phone) == key }

        if (phoneMatches.isEmpty()) return null
        if (phoneMatches.size == 1) return phoneMatches.single()

        // Duplicate leads on one number happen in real CRMs. Pick deterministically rather
        // than by search rank, so the same call always lands on the same lead: prefer one
        // assigned to this rep, then one not flagged junk, then the most recently created.
        val me = tokenStore.session?.userId

        val chosen = phoneMatches
            .sortedWith(
                compareByDescending<LeadDto> { me != null && it.assignedTo == me }
                    .thenByDescending { !it.isJunk }
                    .thenByDescending { it.createdAt.orEmpty() },
            )
            .first()

        logger.warn(
            LogStage.SYNC,
            "${phoneMatches.size} leads share this phone number",
            leadId = chosen.id,
            leadName = chosen.name,
            detail = "Filed against ${chosen.name}. Worth de-duplicating in the CRM.",
        )

        return chosen
    }

    companion object {
        /**
         * The endpoint's own wording for "no such lead". A 404 carrying anything else is a
         * route that does not exist, which is a very different answer.
         */
        const val NOT_FOUND_DETAIL = "Lead not found for this phone"

        /**
         * Small on purpose. We are looking for one exact phone match, not browsing; a large
         * page would only make the response heavier for no gain.
         */
        private const val SEARCH_LIMIT = 25

        /**
         * Maps a `by-phone` response onto what it means for the call.
         *
         * Only two answers are final: a lead, or the endpoint's own "not found". A 400 is
         * the server refusing a number too short to be anyone's, which is final too.
         * Everything else — a dead connection, a 5xx, a 401 while the session is being
         * re-established — is "ask again later", because a call must never be parked as
         * "not a lead" on the strength of an outage.
         */
        fun classifyByPhone(result: ApiResult<LeadDto>): ByPhoneOutcome = when (result) {
            is ApiResult.Success -> ByPhoneOutcome.Found(result.data)

            is ApiResult.Failure.Rejected -> when {
                result.code == 404 && result.detail?.trim() == NOT_FOUND_DETAIL -> ByPhoneOutcome.NotALead
                result.code == 404 -> ByPhoneOutcome.EndpointMissing
                result.code == 400 -> ByPhoneOutcome.NotALead
                else -> ByPhoneOutcome.Unavailable(result)
            }

            is ApiResult.Failure -> ByPhoneOutcome.Unavailable(result)
        }
    }
}
