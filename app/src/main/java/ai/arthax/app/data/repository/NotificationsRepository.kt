package ai.arthax.app.data.repository

import ai.arthax.app.core.ApiConfig
import ai.arthax.app.data.remote.api.ApiResult
import ai.arthax.app.data.remote.api.ArthaxApi
import ai.arthax.app.data.remote.api.safeApiCall
import ai.arthax.app.data.remote.dto.MarkAllNotificationsReadRequest
import ai.arthax.app.domain.model.AppNotification
import ai.arthax.app.domain.model.LogStage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The CRM's notifications: the same rows, counts and read state as the web bell.
 *
 * Also holds the unread count the bottom bar's badge shows. It is refreshed whenever
 * anything here changes read state and whenever a push arrives, so the badge, the screen
 * and the web never disagree for long.
 */
@Singleton
class NotificationsRepository @Inject constructor(
    private val api: ArthaxApi,
    private val logger: EventLogger,
) {

    enum class ReadStatus(val api: String) { ALL("all"), UNREAD("unread"), READ("read") }

    data class Filters(
        val status: ReadStatus = ReadStatus.ALL,
        /** Null for every category. */
        val category: String? = null,
        /** `YYYY-MM-DD`, inclusive, IST. Null for no bound. */
        val dateFrom: String? = null,
        val dateTo: String? = null,
        val query: String = "",
    )

    data class Page(
        val items: List<AppNotification>,
        val total: Int,
        val page: Int,
        val hasMore: Boolean,
    )

    data class Summary(
        val total: Int,
        val unread: Int,
        /** category -> (total, unread). Every known category, zeros included. */
        val byCategory: Map<String, Pair<Int, Int>>,
    )

    private val _unread = MutableStateFlow(0)

    /** Unread notifications for the signed-in user, for the tab badge. */
    val unread: StateFlow<Int> = _unread.asStateFlow()

    suspend fun fetchPage(
        page: Int,
        filters: Filters,
        pageSize: Int = ApiConfig.NOTIFICATIONS_PAGE_SIZE,
    ): ApiResult<Page> {
        val result = safeApiCall {
            api.getNotificationFeed(
                page = page,
                pageSize = pageSize,
                status = filters.status.api.takeIf { filters.status != ReadStatus.ALL },
                category = filters.category,
                dateFrom = filters.dateFrom,
                dateTo = filters.dateTo,
                query = filters.query.trim().takeIf { it.isNotEmpty() },
            )
        }
        return when (result) {
            is ApiResult.Success -> {
                val body = result.data
                val items = body.items.orEmpty().map { it.toDomain() }
                val pages = body.pages ?: page
                ApiResult.Success(
                    Page(
                        items = items,
                        total = body.total ?: items.size,
                        page = page,
                        hasMore = items.isNotEmpty() && page < pages,
                    ),
                )
            }

            is ApiResult.Failure -> {
                logger.warn(LogStage.SYNC, "Could not load notifications: ${result.message}", detail = result.detail)
                result
            }
        }
    }

    /** Counts within an optional date range. Also refreshes the badge when unbounded. */
    suspend fun fetchSummary(dateFrom: String? = null, dateTo: String? = null): ApiResult<Summary> {
        val result = safeApiCall { api.getNotificationSummary(dateFrom, dateTo) }
        return when (result) {
            is ApiResult.Success -> {
                val summary = result.data.toDomain()
                if (dateFrom == null && dateTo == null) _unread.value = summary.unread
                ApiResult.Success(summary)
            }

            is ApiResult.Failure -> result
        }
    }

    /** Refreshes the badge. Quiet on failure: the badge simply keeps its last value. */
    suspend fun refreshUnread() {
        fetchSummary()
    }

    suspend fun markRead(notificationId: String): ApiResult<Unit> {
        val result = safeApiCall { api.markNotificationRead(notificationId) }
        return when (result) {
            is ApiResult.Success -> {
                refreshUnread()
                ApiResult.Success(Unit)
            }

            is ApiResult.Failure -> {
                logger.warn(LogStage.SYNC, "Could not mark a notification read: ${result.message}", detail = result.detail)
                result
            }
        }
    }

    /** Marks every unread notification matching [filters]' category and dates. Returns how many. */
    suspend fun markAllRead(filters: Filters): ApiResult<Int> {
        val request = MarkAllNotificationsReadRequest(
            category = filters.category?.let { listOf(it) },
            dateFrom = filters.dateFrom,
            dateTo = filters.dateTo,
        )
        return when (val result = safeApiCall { api.markAllNotificationsRead(request) }) {
            is ApiResult.Success -> {
                refreshUnread()
                ApiResult.Success(result.data.marked ?: 0)
            }

            is ApiResult.Failure -> {
                logger.warn(LogStage.SYNC, "Could not mark notifications read: ${result.message}", detail = result.detail)
                result
            }
        }
    }
}
