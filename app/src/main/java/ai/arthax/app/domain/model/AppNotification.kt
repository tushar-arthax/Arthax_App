package ai.arthax.app.domain.model

import ai.arthax.app.push.NotificationCategories
import ai.arthax.app.push.NotificationTarget

/** One CRM notification, as the web bell and the Notifications screen show it. */
data class AppNotification(
    val id: String,
    val title: String,
    val message: String,
    /** `alert`, `action_required`, `reminder`, `information`, ... — how urgent it reads. */
    val notificationType: String,
    /** One of [NotificationCategories.ALL]. */
    val category: String,
    val createdAtMillis: Long?,
    val isRead: Boolean,
    val entityType: String?,
    val entityId: String?,
) {
    val target: NotificationTarget get() = NotificationTarget.of(entityType, entityId)

    val isUrgent: Boolean get() = notificationType in URGENT_TYPES

    companion object {
        val URGENT_TYPES = setOf("alert", "action_required")
    }
}
