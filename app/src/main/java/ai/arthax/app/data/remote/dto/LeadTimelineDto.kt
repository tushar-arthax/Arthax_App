package ai.arthax.app.data.remote.dto

import ai.arthax.app.domain.model.LeadTimelineEvent
import com.squareup.moshi.Json
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonClass
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import java.lang.reflect.Type

/**
 * `GET /api/leads/{id}/timeline`.
 *
 * The backend's own schema declares this as `"string"`, and the shape below is the one its
 * description implies. Every field is therefore nullable and none is required — and the
 * screen treats a failure to parse as "no timeline available" rather than as an error,
 * because a guessed shape must never be able to break the lead screen around it.
 *
 * Several plausible field names are accepted for the same idea (`description` / `detail` /
 * `message`, `actor_name` / `actor` / `user_name`) so a small difference between this and
 * the real response degrades to a missing line rather than a blank event.
 */
data class LeadTimelineEventDto(
    @Json(name = "id") val id: String? = null,
    @Json(name = "event_type") val eventType: String? = null,
    @Json(name = "type") val type: String? = null,

    @Json(name = "occurred_at") val occurredAt: String? = null,
    @Json(name = "created_at") val createdAt: String? = null,
    @Json(name = "timestamp") val timestamp: String? = null,

    @Json(name = "actor_name") val actorName: String? = null,
    @Json(name = "actor") val actor: String? = null,
    @Json(name = "user_name") val userName: String? = null,

    @Json(name = "description") val description: String? = null,
    @Json(name = "detail") val detail: String? = null,
    @Json(name = "message") val message: String? = null,
    @Json(name = "note") val note: String? = null,

    // --- What the route actually carries the detail in. ---
    //
    // On the live response `detail` is null on every event and the substance is in this
    // transition pair instead: `to: "upload"` is where the lead came from, and a status
    // change is a `from`/`to` of two status names. Without these the rail draws a row of
    // correctly-dated cards with nothing written on them.

    @Json(name = "from") val from: String? = null,
    @Json(name = "to") val to: String? = null,

    /** The same idea for an assignment: who it moved from and to. */
    @Json(name = "from_user") val fromUser: String? = null,
    @Json(name = "to_user") val toUser: String? = null,

    @Json(name = "follow_up_state") val followUpState: String? = null,
) {
    /** The first of the several names the server might use for each idea. */
    val resolvedType: String? get() = eventType ?: type
    val resolvedAt: String? get() = occurredAt ?: createdAt ?: timestamp

    /**
     * Who did it. Falls back to the assignment's own target, because on an assignment event
     * `actor` is null and the person the row is about is [toUser].
     */
    val resolvedActor: String?
        get() = listOf(actorName, actor, userName, toUser, fromUser)
            .firstOrNull { !it.isNullOrBlank() }

    /** The line of detail under the date, written out if the server sent no prose for it. */
    val resolvedText: String?
        get() = listOf(description, detail, message, note)
            .firstOrNull { !it.isNullOrBlank() }
            ?: transition()

    /**
     * A `from`/`to` pair read as a sentence.
     *
     * Creation and attribution events carry only a `to`, and it is the source the lead came
     * in through rather than a state it moved to — so those read as "Source: UPLOAD" while
     * everything else reads as "Old → New".
     */
    private fun transition(): String? {
        val movedFrom = fromUser?.takeIf { it.isNotBlank() }
        val movedTo = toUser?.takeIf { it.isNotBlank() }
        if (movedTo != null) {
            return if (movedFrom != null) "$movedFrom → $movedTo" else movedTo
        }

        val before = from?.takeIf { it.isNotBlank() }
        val after = to?.takeIf { it.isNotBlank() }

        return when {
            before != null && after != null -> "${before.humanise()} → ${after.humanise()}"
            after != null && isArrival() -> "Source: ${after.trim().uppercase()}"
            after != null -> after.humanise()
            else -> followUpState?.takeIf { it.isNotBlank() }
                ?.let { "Follow-up: ${it.humanise()}" }
        }
    }

    /** Events where a lone `to` means where the lead came from, not where it went. */
    private fun isArrival(): Boolean =
        resolvedType?.lowercase().orEmpty().let { word ->
            word.contains("creat") || word.contains("ingest") || word.contains("import") ||
                word.contains("attribution") || word.contains("source")
        }

    private fun String.humanise(): String =
        trim().replace('_', ' ').replaceFirstChar { it.uppercase() }
}





/**
 * Reads one value as text, whatever the server actually put there.
 *
 * A timeline field declared as a string can arrive as a number, a boolean, an object or an
 * array: the route's schema says only "string" about the entire response, so none of it is
 * guaranteed. Anything readable is rendered; anything empty becomes null.
 */
internal fun looseText(reader: JsonReader): String? = when (reader.peek()) {
    JsonReader.Token.NULL -> reader.nextNull<String>()

    JsonReader.Token.STRING, JsonReader.Token.NUMBER ->
        reader.nextString().trim().takeIf { it.isNotEmpty() }

    JsonReader.Token.BOOLEAN -> reader.nextBoolean().toString()

    else -> flattenJson(reader.readJsonValue())
}

/**
 * A JSON value rendered as a line a rep can read.
 *
 * An object that names itself reads as that name alone, since this API returns a person as
 * an id and a name elsewhere. Anything else is written out as its own fields with the nulls
 * left off, because a map rendered by toString on a card is worse than showing nothing.
 */
internal fun flattenJson(value: Any?): String? = when (value) {
    null -> null

    is Map<*, *> -> {
        val named = NAME_KEYS.firstNotNullOfOrNull { key -> value[key]?.let { flattenJson(it) } }

        named ?: value.entries
            .mapNotNull { (key, raw) ->
                flattenJson(raw)?.let { "${key.toString().replace('_', ' ')}: $it" }
            }
            .joinToString(", ")
            .takeIf { it.isNotBlank() }
    }

    is List<*> -> value.mapNotNull { flattenJson(it) }
        .joinToString(", ")
        .takeIf { it.isNotBlank() }

    else -> value.asDisplayText()
}

/** Keys an object uses to say what it is, most specific first. */
private val NAME_KEYS = listOf("name", "full_name", "label", "title", "value", "status")

/**
 * A timeline event as the screen needs it.
 *
 * Lives here, next to the wire type, rather than inline in the repository so it can be
 * tested against a real response body — the repository needs an [EventLogger] and its
 * stores, none of which exist in a JVM test, and this mapping is the part that was
 * silently turning a good response into an empty rail.
 *
 * @param fallbackId used when the route sends no id, which its own schema allows.
 */
fun LeadTimelineEventDto.toDomain(fallbackId: String): LeadTimelineEvent = LeadTimelineEvent(
    id = id?.takeIf { it.isNotBlank() } ?: fallbackId,
    type = resolvedType?.trim()?.takeIf { it.isNotEmpty() },
    occurredAtMillis = ApiTime.parseOrNull(resolvedAt),
    actor = resolvedActor?.trim()?.takeIf { it.isNotEmpty() },
    text = resolvedText?.trim()?.takeIf { it.isNotEmpty() },
)

/**
 * The timeline response, however the route chose to wrap it.
 *
 * The backend documents this route's schema only as `"string"`, so the envelope is as much
 * a guess as the events inside it. A bare array and an envelope are both entirely plausible
 * — the lead, call and meeting list routes on this same API all use `{"items": [...]}` —
 * and getting it wrong is not a wrong field but a completely blank section, because Moshi
 * rejects the whole body. [LeadTimelinePageAdapterFactory] therefore accepts either.
 */
data class LeadTimelinePage(
    val events: List<LeadTimelineEventDto>,
    /**
     * The cursor for the next, older page, or null when this is the end.
     *
     * This route pages by cursor rather than by offset: it answers with the timestamp and id
     * to pass back as `before` and `before_id`. `next_before` being null is the only honest
     * "that is all of it" — counting the rows against the limit guesses wrong whenever the
     * server trims a page.
     */
    val nextBefore: String? = null,
    val nextBeforeId: String? = null,
)

/**
 * Reads [LeadTimelinePage] from an array, or from an object with the events under any of
 * the usual keys.
 *
 * Registered on the shared Moshi. It answers for exactly one type and returns null for
 * everything else, so no other response — least of all a call or a recording — can reach it.
 */
object LeadTimelinePageAdapterFactory : JsonAdapter.Factory {

    /** Keys seen wrapping a list on APIs of this shape, in the order they are preferred. */
    private val ENVELOPE_KEYS = listOf("items", "events", "timeline", "results", "data")

    override fun create(type: Type, annotations: Set<Annotation>, moshi: Moshi): JsonAdapter<*>? {
        if (Types.getRawType(type) != LeadTimelinePage::class.java) return null

        return object : JsonAdapter<LeadTimelinePage>() {

            override fun fromJson(reader: JsonReader): LeadTimelinePage =
                when (reader.peek()) {
                    JsonReader.Token.BEGIN_ARRAY -> LeadTimelinePage(readEvents(reader))

                    JsonReader.Token.BEGIN_OBJECT -> readEnvelope(reader)

                    // Including the literal `null` the route's own schema hints at. Not an
                    // error: an empty journey is a legitimate answer for a new lead.
                    else -> {
                        reader.skipValue()
                        LeadTimelinePage(emptyList())
                    }
                }

            private fun readEnvelope(reader: JsonReader): LeadTimelinePage {
                var best: List<LeadTimelineEventDto>? = null
                var bestRank = Int.MAX_VALUE
                var nextBefore: String? = null
                var nextBeforeId: String? = null

                reader.beginObject()
                while (reader.hasNext()) {
                    val name = reader.nextName()
                    val rank = ENVELOPE_KEYS.indexOf(name)

                    when {
                        rank >= 0 && rank < bestRank &&
                            reader.peek() == JsonReader.Token.BEGIN_ARRAY -> {
                            best = readEvents(reader)
                            bestRank = rank
                        }

                        name == "next_before" -> nextBefore = reader.nextStringOrNull()
                        name == "next_before_id" -> nextBeforeId = reader.nextStringOrNull()

                        else -> reader.skipValue()
                    }
                }
                reader.endObject()

                return LeadTimelinePage(
                    events = best.orEmpty(),
                    nextBefore = nextBefore,
                    nextBeforeId = nextBeforeId,
                )
            }

            /** The cursor fields are documented as strings but arrive null on the last page. */
            private fun JsonReader.nextStringOrNull(): String? =
                if (peek() == JsonReader.Token.NULL) {
                    nextNull<String>()
                } else {
                    nextString()
                }

            private fun readEvents(reader: JsonReader): List<LeadTimelineEventDto> {
                val events = mutableListOf<LeadTimelineEventDto>()
                reader.beginArray()
                while (reader.hasNext()) {
                    if (reader.peek() == JsonReader.Token.BEGIN_OBJECT) {
                        events += readEvent(reader)
                    } else {
                        // A bare string in the array is still an event of sorts, and
                        // certainly not a reason to reject the other eighteen.
                        val loose = looseText(reader)
                        if (loose != null) events += LeadTimelineEventDto(detail = loose)
                    }
                }
                reader.endArray()
                return events
            }

            /**
             * One event, read field by field rather than through a generated adapter.
             *
             * By hand because a generated adapter is strict about types, and strictness
             * here is fatal in a way it is nowhere else: Moshi rejects the whole *array* on
             * one bad field, so a single call event whose `detail` is an object instead of
             * a sentence takes the entire journey off the screen, reported as a parse error
             * on a response the server answered 200 to. Every field goes through
             * [looseText], which accepts whatever is actually there.
             */
            private fun readEvent(reader: JsonReader): LeadTimelineEventDto {
                var id: String? = null
                var eventType: String? = null
                var type: String? = null
                var occurredAt: String? = null
                var createdAt: String? = null
                var timestamp: String? = null
                var actorName: String? = null
                var actor: String? = null
                var userName: String? = null
                var description: String? = null
                var detail: String? = null
                var message: String? = null
                var note: String? = null
                var from: String? = null
                var to: String? = null
                var fromUser: String? = null
                var toUser: String? = null
                var followUpState: String? = null

                reader.beginObject()
                while (reader.hasNext()) {
                    when (reader.nextName()) {
                        "id" -> id = looseText(reader)
                        "event_type" -> eventType = looseText(reader)
                        "type" -> type = looseText(reader)
                        "occurred_at" -> occurredAt = looseText(reader)
                        "created_at" -> createdAt = looseText(reader)
                        "timestamp" -> timestamp = looseText(reader)
                        "actor_name" -> actorName = looseText(reader)
                        "actor" -> actor = looseText(reader)
                        "user_name" -> userName = looseText(reader)
                        "description" -> description = looseText(reader)
                        "detail" -> detail = looseText(reader)
                        "message" -> message = looseText(reader)
                        "note" -> note = looseText(reader)
                        "from" -> from = looseText(reader)
                        "to" -> to = looseText(reader)
                        "from_user" -> fromUser = looseText(reader)
                        "to_user" -> toUser = looseText(reader)
                        "follow_up_state" -> followUpState = looseText(reader)
                        else -> reader.skipValue()
                    }
                }
                reader.endObject()

                return LeadTimelineEventDto(
                    id = id,
                    eventType = eventType,
                    type = type,
                    occurredAt = occurredAt,
                    createdAt = createdAt,
                    timestamp = timestamp,
                    actorName = actorName,
                    actor = actor,
                    userName = userName,
                    description = description,
                    detail = detail,
                    message = message,
                    note = note,
                    from = from,
                    to = to,
                    fromUser = fromUser,
                    toUser = toUser,
                    followUpState = followUpState,
                )
            }

            override fun toJson(writer: JsonWriter, value: LeadTimelinePage?) =
                throw UnsupportedOperationException("The timeline is never sent.")
        }
    }
}

/**
 * `GET /api/leads/custom-fields` — the extra columns this organisation has defined.
 *
 * Read so the lead screen can label and order a lead's `custom_fields` the way the CRM
 * does, and so the edit sheet can offer a `select` field's own options instead of a free
 * text box.
 */
@JsonClass(generateAdapter = true)
data class LeadCustomFieldDto(
    @Json(name = "id") val id: String,
    @Json(name = "name") val name: String,
    /** "text", "select", "number", … — free-form, so it is matched loosely. */
    @Json(name = "field_type") val fieldType: String? = null,
    @Json(name = "options") val options: List<String>? = null,
    @Json(name = "is_active") val isActive: Boolean? = null,
)
