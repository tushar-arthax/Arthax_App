package ai.arthax.app.data.remote.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * `GET /api/notifications/feed` — one page of the signed-in user's notifications.
 *
 * Everything but the id is nullable on purpose: an explicit `null` where a non-nullable
 * field was declared throws at parse time and empties a screen the server answered 200 for.
 */
@JsonClass(generateAdapter = true)
data class NotificationFeedDto(
    @Json(name = "items") val items: List<NotificationDto>? = null,
    @Json(name = "total") val total: Int? = null,
    @Json(name = "page") val page: Int? = null,
    @Json(name = "page_size") val pageSize: Int? = null,
    @Json(name = "pages") val pages: Int? = null,
)

@JsonClass(generateAdapter = true)
data class NotificationDto(
    @Json(name = "id") val id: String,
    @Json(name = "title") val title: String? = null,
    @Json(name = "message") val message: String? = null,
    @Json(name = "notification_type") val notificationType: String? = null,
    @Json(name = "category") val category: String? = null,
    /** Naive IST, like every timestamp this backend writes; read with [ApiTime]. */
    @Json(name = "created_at") val createdAt: String? = null,
    @Json(name = "is_read") val isRead: Boolean? = null,
    @Json(name = "entity_type") val entityType: String? = null,
    @Json(name = "entity_id") val entityId: String? = null,
)

/** `GET /api/notifications/summary`. */
@JsonClass(generateAdapter = true)
data class NotificationSummaryDto(
    @Json(name = "total") val total: Int? = null,
    @Json(name = "unread") val unread: Int? = null,
    @Json(name = "by_category") val byCategory: List<NotificationCategoryCountDto>? = null,
)

@JsonClass(generateAdapter = true)
data class NotificationCategoryCountDto(
    @Json(name = "category") val category: String? = null,
    @Json(name = "total") val total: Int? = null,
    @Json(name = "unread") val unread: Int? = null,
)

/**
 * `POST /api/notifications/read-all`. Null fields are left out of the JSON, which the
 * server reads as "no filter".
 */
@JsonClass(generateAdapter = true)
data class MarkAllNotificationsReadRequest(
    @Json(name = "category") val category: List<String>? = null,
    @Json(name = "date_from") val dateFrom: String? = null,
    @Json(name = "date_to") val dateTo: String? = null,
)

@JsonClass(generateAdapter = true)
data class MarkAllNotificationsReadResponse(
    @Json(name = "marked") val marked: Int? = null,
)
