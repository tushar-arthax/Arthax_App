package ai.arthax.app.data.repository

import ai.arthax.app.data.remote.dto.ApiTime
import ai.arthax.app.data.remote.dto.NotificationDto
import ai.arthax.app.data.remote.dto.NotificationSummaryDto
import ai.arthax.app.domain.model.AppNotification
import ai.arthax.app.push.NotificationCategories

/** From the wire to the app. Kept apart from the repository so it is tested on the JVM. */
internal fun NotificationDto.toDomain(): AppNotification = AppNotification(
    id = id,
    title = title?.takeIf { it.isNotBlank() } ?: "Arthax",
    message = message.orEmpty(),
    notificationType = notificationType.orEmpty(),
    category = NotificationCategories.normalise(category),
    createdAtMillis = ApiTime.parseOrNull(createdAt),
    isRead = isRead ?: false,
    entityType = entityType?.takeIf { it.isNotBlank() },
    entityId = entityId?.takeIf { it.isNotBlank() },
)

internal fun NotificationSummaryDto.toDomain(): NotificationsRepository.Summary {
    val counts = mutableMapOf<String, Pair<Int, Int>>()
    NotificationCategories.ALL.forEach { counts[it] = 0 to 0 }
    byCategory.orEmpty().forEach { row ->
        val key = NotificationCategories.normalise(row.category)
        val (total, unread) = counts.getValue(key)
        counts[key] = (total + (row.total ?: 0)) to (unread + (row.unread ?: 0))
    }
    return NotificationsRepository.Summary(
        total = total ?: counts.values.sumOf { it.first },
        unread = unread ?: counts.values.sumOf { it.second },
        byCategory = counts,
    )
}
