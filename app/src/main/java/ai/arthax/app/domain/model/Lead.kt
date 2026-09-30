package ai.arthax.app.domain.model

/**
 * A lead as shown on the dialling list and the lead detail screen.
 *
 * Every field after [phoneNumber] carries a default, and that is load-bearing:
 * [ai.arthax.app.push.DialRequestPlacer] and the call-history screen build a `Lead` from
 * nothing but an id, a name and a number in order to place a call, and adding a required
 * field here would break the push-to-dial path.
 */
data class Lead(
    val id: String,
    val name: String,
    val phoneNumber: String,
    val company: String? = null,
    val email: String? = null,
    val location: String? = null,
    val notes: String? = null,
    /** Free-form on the server ("contacted", "new", ...), so kept as text rather than an enum. */
    val status: String? = null,
    val temperature: LeadTemperature = LeadTemperature.COLD,
    val rating: Int? = null,
    val lastContactedAt: Long? = null,
    val nextFollowUpAt: Long? = null,

    val gstNo: String? = null,
    val occupation: String? = null,
    val source: String? = null,
    /** Already resolved to a person's name by the server. */
    val assignedToName: String? = null,

    /** Null when the server said "undetermined" — an absent verdict, not a verdict of absence. */
    val aiClassification: String? = null,
    val aiClassificationReason: String? = null,

    val isJunk: Boolean = false,
    val junkCategory: String? = null,
    val isDuplicate: Boolean = false,
    val buyerIntent: String? = null,
    val createdAt: Long? = null,

    /** Org-defined extra columns, already flattened to text for display. */
    val customFields: Map<String, String> = emptyMap(),
) {
    val isCallable: Boolean get() = phoneNumber.isNotBlank()

    /** "contacted" -> "Contacted", for display. */
    val statusLabel: String?
        get() = status?.replace('_', ' ')?.replaceFirstChar { it.uppercase() }

    val sourceLabel: String?
        get() = source?.replace('_', ' ')?.replaceFirstChar { it.uppercase() }

    val junkCategoryLabel: String?
        get() = junkCategory?.replace('_', ' ')?.replaceFirstChar { it.uppercase() }
}

/** Server enum: cold | warm | hot. */
enum class LeadTemperature(val label: String) {
    COLD("Cold"),
    WARM("Warm"),
    HOT("Hot"),
    ;

    companion object {
        fun fromApi(raw: String?): LeadTemperature =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: COLD
    }
}

/**
 * A status or source the organisation has defined, from `GET /api/leads/statuses` and
 * `GET /api/leads/sources`.
 *
 * Both routes return the same shape, and neither is a fixed set — the CRM lets an org add
 * its own — which is why the filter chips and the edit picker are built from what the
 * server says rather than from an enum compiled into the app.
 */
data class LeadOption(
    val id: String,
    /** The value to send back to the API, e.g. `follow_up_pending`. */
    val api: String,
    val isDefault: Boolean = false,
) {
    /** "follow_up_pending" -> "Follow up pending". */
    val label: String
        get() = api.replace('_', ' ').trim().replaceFirstChar { it.uppercase() }
}

/**
 * Why a lead was marked junk.
 *
 * The server takes free text; this is the list the app offers, so a rep picks rather than
 * types and the categories stay consistent enough to be worth reporting on.
 */
enum class JunkCategory(val api: String, val label: String) {
    INVALID_NUMBER("invalid_number", "Invalid number"),
    NOT_INTERESTED("not_interested", "Not interested"),
    DUPLICATE("duplicate", "Duplicate"),
    SPAM("spam", "Spam"),
    WRONG_AUDIENCE("wrong_audience", "Wrong audience"),
    OTHER("other", "Other"),
}

/**
 * One thing that happened to a lead.
 *
 * Built from a route whose schema the backend documents only as `"string"`, so every field
 * is optional and an event with nothing but a time still renders as a dated marker rather
 * than as a blank row.
 */
data class LeadTimelineEvent(
    val id: String,
    /** Raw server key, e.g. `follow_up_scheduled`. Drives the icon and the colour. */
    val type: String?,
    val occurredAtMillis: Long?,
    val actor: String?,
    val text: String?,
) {
    /** "follow_up_scheduled" -> "Follow up scheduled". */
    val typeLabel: String
        get() = type?.replace('_', ' ')?.trim()?.replaceFirstChar { it.uppercase() } ?: "Activity"

    /** True when there is nothing here worth a row. */
    val isEmpty: Boolean
        get() = type.isNullOrBlank() && text.isNullOrBlank() && occurredAtMillis == null
}

/**
 * An extra column the organisation has defined on its leads.
 *
 * [options] is non-empty only for a `select`, where the edit sheet offers those values
 * instead of a free text box.
 */
data class LeadCustomField(
    val id: String,
    val name: String,
    val type: String?,
    val options: List<String>,
) {
    val isSelect: Boolean get() = options.isNotEmpty() || type.equals("select", ignoreCase = true)

    val isNumber: Boolean get() = type.equals("number", ignoreCase = true)

    /** "lead_source" -> "Lead source". */
    val label: String
        get() = name.replace('_', ' ').trim().replaceFirstChar { it.uppercase() }
}
