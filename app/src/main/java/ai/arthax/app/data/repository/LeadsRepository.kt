package ai.arthax.app.data.repository

import ai.arthax.app.core.ApiConfig
import ai.arthax.app.data.remote.api.ApiResult
import ai.arthax.app.data.remote.api.ArthaxApi
import ai.arthax.app.data.remote.api.safeApiCall
import ai.arthax.app.data.remote.dto.toDomain
import ai.arthax.app.domain.model.Lead
import ai.arthax.app.domain.model.LogStage
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LeadsRepository @Inject constructor(
    private val api: ArthaxApi,
    private val logger: EventLogger,
) {

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
    ): ApiResult<Page> {
        val query = search?.trim()?.takeIf { it.isNotEmpty() }
        val statusFilter = status?.trim()?.takeIf { it.isNotEmpty() }

        val result = safeApiCall {
            api.getLeads(
                skip = skip,
                limit = limit,
                // A junk lead should never be offered up to call.
                isJunk = false,
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
                val callable = all.filter { it.isCallable }
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
}
