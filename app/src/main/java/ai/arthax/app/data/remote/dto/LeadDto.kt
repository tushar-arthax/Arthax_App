package ai.arthax.app.data.remote.dto

import ai.arthax.app.domain.model.Lead
import ai.arthax.app.domain.model.LeadTemperature
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * GET /api/leads/ — paginated envelope.
 *
 * `items` and `total` are deliberately left non-nullable, unlike the call and meeting
 * envelopes which had to be loosened after a real null came back. This one is read on the
 * call-matching path by [ai.arthax.app.data.repository.LeadResolver], it has been in
 * production unchanged, and making it nullable would force a change there for a null the
 * server has never sent.
 */
@JsonClass(generateAdapter = true)
data class LeadPageDto(
    @Json(name = "items") val items: List<LeadDto> = emptyList(),
    @Json(name = "total") val total: Int = 0,
    @Json(name = "page") val page: Int? = null,
    @Json(name = "size") val size: Int? = null,
    @Json(name = "pages") val pages: Int? = null,
)

/**
 * A lead, as returned by the list, the detail route and every write that echoes one back.
 *
 * **Additive only.** [ai.arthax.app.data.repository.LeadResolver] reads `id`, `name`,
 * `phone`, `isJunk`, `assignedTo` and `createdAt` off this type on the call-matching path,
 * so those six must never be renamed, removed or made required-in-a-new-way. Everything
 * added since is nullable with a default and cannot change how a call is matched.
 *
 * `touches`, `messages_history`, `calls_history` and `demographics` come back on the detail
 * route and are deliberately not modelled: the backend documents them only as empty arrays,
 * so their shape is unknown. The lead screen shows call history from `GET /api/calls/`
 * filtered by lead instead — an endpoint whose shape is known and already parsed.
 */
@JsonClass(generateAdapter = true)
data class LeadDto(
    @Json(name = "id") val id: String,
    @Json(name = "name") val name: String,
    @Json(name = "phone") val phone: String? = null,
    @Json(name = "email") val email: String? = null,
    @Json(name = "company") val company: String? = null,
    @Json(name = "location") val location: String? = null,
    @Json(name = "notes") val notes: String? = null,
    @Json(name = "status") val status: String? = null,
    @Json(name = "assigned_to") val assignedTo: String? = null,
    @Json(name = "temperature") val temperature: String? = null,
    @Json(name = "source") val source: String? = null,
    @Json(name = "is_junk") val isJunk: Boolean = false,
    @Json(name = "rating") val rating: Int? = null,
    @Json(name = "created_at") val createdAt: String? = null,
    @Json(name = "last_contacted_date") val lastContactedDate: String? = null,
    @Json(name = "next_follow_up_date") val nextFollowUpDate: String? = null,

    // --- Added for the lead detail screen. All optional; none is on the matching path. ---

    @Json(name = "gst_no") val gstNo: String? = null,
    @Json(name = "occupation") val occupation: String? = null,

    /** Who the lead belongs to, already resolved to a name by the server. */
    @Json(name = "assigned_to_name") val assignedToName: String? = null,

    @Json(name = "ai_classification") val aiClassification: String? = null,
    @Json(name = "ai_classification_reason") val aiClassificationReason: String? = null,

    @Json(name = "junk_category") val junkCategory: String? = null,
    @Json(name = "is_revived") val isRevived: Boolean? = null,
    @Json(name = "is_duplicate") val isDuplicate: Boolean? = null,

    /** Detail route only. */
    @Json(name = "buyer_intent") val buyerIntent: String? = null,

    /**
     * Org-defined extra columns, shown read-only.
     *
     * `Map<String, Any>` because the values are genuinely mixed — the documented example
     * has a string and a number side by side — and Moshi's built-in `Any` adapter reads
     * each as String, Double, Boolean, List or Map as it finds them.
     */
    @Json(name = "custom_fields") val customFields: Map<String, Any>? = null,
)

fun LeadDto.toDomain(): Lead = Lead(
    id = id,
    name = name.trim().ifBlank { "Unnamed lead" },
    // The server returns both null and "" for optional text depending on how the lead was
    // created, so every one of these is normalised to null rather than checked twice later.
    phoneNumber = phone?.trim().orEmpty(),
    company = company?.trim()?.takeIf { it.isNotEmpty() },
    email = email?.trim()?.takeIf { it.isNotEmpty() },
    location = location?.trim()?.takeIf { it.isNotEmpty() },
    notes = notes?.trim()?.takeIf { it.isNotEmpty() },
    status = status?.trim()?.takeIf { it.isNotEmpty() },
    temperature = LeadTemperature.fromApi(temperature),
    rating = rating,
    lastContactedAt = ApiTime.parseOrNull(lastContactedDate),
    nextFollowUpAt = ApiTime.parseOrNull(nextFollowUpDate),

    gstNo = gstNo?.trim()?.takeIf { it.isNotEmpty() },
    occupation = occupation?.trim()?.takeIf { it.isNotEmpty() },
    source = source?.trim()?.takeIf { it.isNotEmpty() },
    assignedToName = assignedToName?.trim()?.takeIf { it.isNotEmpty() },
    aiClassification = aiClassification?.trim()?.takeIf { it.isNotEmpty() && it != "undetermined" },
    aiClassificationReason = aiClassificationReason?.trim()?.takeIf { it.isNotEmpty() },
    isJunk = isJunk,
    junkCategory = junkCategory?.trim()?.takeIf { it.isNotEmpty() },
    isDuplicate = isDuplicate == true,
    buyerIntent = buyerIntent?.trim()?.takeIf { it.isNotEmpty() },
    createdAt = ApiTime.parseOrNull(createdAt),
    // Flattened to text here so the UI never has to care that the map is heterogeneous.
    customFields = customFields.orEmpty()
        .mapNotNull { (key, value) ->
            val text = value.asDisplayText()
            if (text == null) null else key to text
        }
        .toMap(),
)

/**
 * One custom-field value as a rep should see it.
 *
 * Moshi reads every JSON number as a [Double], so a plain `toString()` turns an employee
 * count of `120` into "120.0". A whole number is printed whole; everything else falls back
 * to its own representation.
 */
internal fun Any?.asDisplayText(): String? {
    val text = when (this) {
        null -> return null
        is Double -> if (this % 1.0 == 0.0 && isFinite()) toLong().toString() else toString()
        is Float -> if (this % 1.0f == 0.0f && isFinite()) toLong().toString() else toString()
        is List<*> -> joinToString(", ") { it.asDisplayText().orEmpty() }
        else -> toString()
    }
    return text.trim().takeIf { it.isNotEmpty() && it != "null" && it != "{}" && it != "[]" }
}

/**
 * One entry from `GET /api/leads/statuses` or `GET /api/leads/sources`. Same shape.
 *
 * Every field is nullable, and that is the whole point of this type.
 *
 * Both fields used to be required, which meant one odd element discarded all of them: Moshi
 * fails the *array*, not the entry, so `{"id": null}` or a missing `id` anywhere in the list
 * threw `JsonDataException` and the screen showed an empty filter row while the server had
 * answered 200. The reason never reached the rep either — a parse failure comes back from
 * `safeApiCall` as the generic "Something went wrong", so the only visible symptom was
 * statuses that would not load however many times Retry was pressed.
 *
 * Statuses are defined per organisation through `POST /api/leads/statuses`, so their exact
 * shape is the backend's to change and is not worth betting seven chips on. [name] is the
 * only field the app needs — it is what goes back as `?status=` — and [nameOrNull] is how to
 * read it.
 */
@JsonClass(generateAdapter = true)
data class LeadOptionDto(
    @Json(name = "id") val id: String? = null,
    @Json(name = "name") val name: String? = null,
    @Json(name = "is_default") val isDefault: Boolean? = null,

    /**
     * Spellings other than `name` seen on endpoints of this kind. Read only when `name` is
     * absent, so a server that sends `name` is unaffected by their presence.
     */
    @Json(name = "value") val value: String? = null,
    @Json(name = "status") val status: String? = null,
    @Json(name = "label") val label: String? = null,
) {
    /** The status text, whichever key it arrived under, or null if there is none to use. */
    val nameOrNull: String?
        get() = listOf(name, value, status, label)
            .firstOrNull { !it.isNullOrBlank() }
            ?.trim()
}

/**
 * PATCH /api/leads/{id}.
 *
 * Every field nullable so Moshi omits it: an instance carrying only `status` sends only
 * `status` and cannot blank the rest of the lead by accident.
 */
@JsonClass(generateAdapter = true)
data class LeadUpdateRequest(
    @Json(name = "name") val name: String? = null,
    @Json(name = "phone") val phone: String? = null,
    @Json(name = "email") val email: String? = null,
    @Json(name = "company") val company: String? = null,
    @Json(name = "location") val location: String? = null,
    @Json(name = "occupation") val occupation: String? = null,
    @Json(name = "gst_no") val gstNo: String? = null,
    @Json(name = "status") val status: String? = null,
    @Json(name = "temperature") val temperature: String? = null,
    @Json(name = "notes") val notes: String? = null,
    @Json(name = "rating") val rating: Int? = null,
    /** Only the org-defined columns the rep actually changed; the rest are left alone. */
    @Json(name = "custom_fields") val customFields: Map<String, String>? = null,
)

/** POST /api/leads/ */
@JsonClass(generateAdapter = true)
data class LeadCreateRequest(
    @Json(name = "name") val name: String,
    @Json(name = "phone") val phone: String,
    @Json(name = "email") val email: String? = null,
    @Json(name = "company") val company: String? = null,
    @Json(name = "location") val location: String? = null,
    @Json(name = "occupation") val occupation: String? = null,
    @Json(name = "source") val source: String? = null,
    @Json(name = "notes") val notes: String? = null,
    /** Let the server route it, rather than pinning it to whoever happened to add it. */
    @Json(name = "auto_assign") val autoAssign: Boolean = true,
)

/** POST /api/leads/{id}/junk */
@JsonClass(generateAdapter = true)
data class MarkJunkRequest(
    @Json(name = "junk_category") val junkCategory: String,
    @Json(name = "junk_reason") val junkReason: String? = null,
)

/** POST /api/leads/{id}/follow-up */
@JsonClass(generateAdapter = true)
data class FollowUpRequest(
    @Json(name = "scheduled_at") val scheduledAt: String,
    @Json(name = "note") val note: String? = null,
)

/** POST /api/leads/{id}/follow-up/complete */
@JsonClass(generateAdapter = true)
data class CompleteFollowUpRequest(
    @Json(name = "outcome") val outcome: String,
    @Json(name = "outcome_note") val outcomeNote: String? = null,
)
