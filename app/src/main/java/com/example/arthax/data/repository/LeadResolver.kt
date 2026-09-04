package com.example.arthax.data.repository

import com.example.arthax.core.PhoneNumbers
import com.example.arthax.data.local.prefs.SecureTokenStore
import com.example.arthax.data.local.store.LeadLookupCache
import com.example.arthax.data.remote.api.ApiResult
import com.example.arthax.data.remote.api.ArthaxApi
import com.example.arthax.data.remote.api.safeApiCall
import com.example.arthax.data.remote.dto.LeadDto
import com.example.arthax.domain.model.LogStage
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Answers "does this phone number belong to one of our leads?".
 *
 * Cache first, server second. The server is asked with the lead-search endpoint one number
 * at a time, which is what lets this work against a CRM of any size — nothing is ever bulk
 * downloaded, and the cost of a call is at most one small request.
 */
@Singleton
class LeadResolver @Inject constructor(
    private val api: ArthaxApi,
    private val cache: LeadLookupCache,
    private val tokenStore: SecureTokenStore,
    private val logger: EventLogger,
) {

    sealed interface Resolution {
        data class Lead(val id: String, val name: String, val fromCache: Boolean) : Resolution

        /** Confirmed not a lead. The call is the rep's own business and is dropped. */
        data object NotALead : Resolution

        /**
         * Could not be determined — offline, or the server errored. Deliberately distinct
         * from [NotALead]: a call we could not classify must be retried, never discarded,
         * or a genuine lead call would be lost the moment the rep walked into a lift.
         */
        data class Unavailable(val reason: String) : Resolution
    }

    suspend fun resolve(rawNumber: String?): Resolution {
        val key = PhoneNumbers.matchKey(rawNumber)
        if (key.isEmpty()) return Resolution.NotALead

        val cached = cache.peek(rawNumber)
        when (cached.verdict) {
            LeadLookupCache.Verdict.LEAD ->
                return Resolution.Lead(cached.leadId!!, cached.leadName.orEmpty(), fromCache = true)

            LeadLookupCache.Verdict.NOT_A_LEAD -> return Resolution.NotALead

            LeadLookupCache.Verdict.UNKNOWN -> Unit // fall through and ask the server
        }

        if (!tokenStore.isLoggedIn) return Resolution.Unavailable("not signed in")

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

                if (match == null) {
                    cache.rememberNotALead(rawNumber)
                    Resolution.NotALead
                } else {
                    val name = match.name.trim().ifBlank { "Unnamed lead" }
                    cache.rememberLead(rawNumber, match.id, name)
                    Resolution.Lead(match.id, name, fromCache = false)
                }
            }

            is ApiResult.Failure -> {
                // Not cached: an outage must not be remembered as "not a lead" and cause the
                // call to be dropped for the next twelve hours.
                logger.warn(
                    LogStage.SYNC,
                    "Could not check whether a call was to a lead: ${result.message}",
                    detail = "The call is kept and will be checked again. " +
                        "Reason: ${result.detail ?: result.message}",
                )
                Resolution.Unavailable(result.message)
            }
        }
    }

    /**
     * Chooses the lead a number really belongs to.
     *
     * The endpoint is a fuzzy *text* search across several columns, so the first item is not
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

    private companion object {
        /**
         * Small on purpose. We are looking for one exact phone match, not browsing; a large
         * page would only make the response heavier for no gain.
         */
        const val SEARCH_LIMIT = 25
    }
}
