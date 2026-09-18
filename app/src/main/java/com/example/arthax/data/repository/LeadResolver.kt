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
 * The CRM is asked every single time, and that is the point. The answer is live: a lead
 * added a minute before the call is found, and a lead deleted or reassigned in the CRM stops
 * being matched from that moment on. Nothing is ever bulk downloaded — the lead-search
 * endpoint is asked about one number — so this costs one small request per call and behaves
 * identically against a CRM of a hundred leads or ten million.
 *
 * The local list is a fallback for one situation only: the CRM could not be reached at all.
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

        /**
         * The CRM says this number is not a lead. The call is the rep's own business: no
         * recording is touched, nothing is uploaded, and the number is never written down.
         *
         * Only ever a live answer. Nothing is dropped on the strength of something
         * remembered, because a stranger an hour ago may be a lead now.
         */
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
        // Withheld, unknown or malformed numbers cannot belong to anyone.
        if (key.isEmpty()) return Resolution.NotALead

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
                    // A live "no", and it overrides anything remembered. This is what makes a
                    // lead deleted or reassigned in the CRM stop being matched at once,
                    // rather than going on collecting calls from the offline list.
                    cache.forget(rawNumber)
                    Resolution.NotALead
                } else {
                    val name = match.name.trim().ifBlank { "Unnamed lead" }
                    cache.rememberLead(rawNumber, match.id, name)
                    Resolution.Lead(match.id, name, fromCache = false)
                }
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
        val remembered = cache.peek(rawNumber)

        if (remembered.verdict == LeadLookupCache.Verdict.LEAD) {
            logger.warn(
                LogStage.SYNC,
                "Matched a call from the offline list — the CRM could not be reached",
                leadId = remembered.leadId,
                leadName = remembered.leadName,
                detail = "The recording is being captured now rather than risked. " +
                    "Reason: ${failure.detail ?: failure.message}",
            )
            return Resolution.Lead(
                id = remembered.leadId!!,
                name = remembered.leadName.orEmpty(),
                fromCache = true,
            )
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
