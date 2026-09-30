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

    /**
     * Per-type extras: `duration_seconds` and `direction` on a logged call, `junk_category`
     * on a junking, `fields` on a details update, and the various `*_id`s.
     *
     * Deliberately an untyped map. The documentation is explicit that this has a different
     * shape for every event type and that new types appear without warning, so a fixed
     * class here would be a parse failure waiting for the next backend release — and on
     * this route a parse failure costs the whole journey, not one field.
     */
    val meta: Map<String, Any?>? = null,
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

    /**
     * The line of detail under the date.
     *
     * Built from whatever the event actually carries, in this order: the outcome it moved
     * to, the server's own prose, and then the facts in [meta] that a rep would otherwise
     * have to open the call to find. Joined with a middle dot so a logged call reads
     * "Connected · 0m 16s · asked for pricing" on one line instead of needing three.
     */
    val resolvedText: String?
        get() {
            val prose = listOf(description, detail, message, note)
                .firstOrNull { !it.isNullOrBlank() }
                ?.trim()

            val parts = buildList {
                // For a call the outcome is the headline, and `detail` is the note beside
                // it — so both belong here rather than one replacing the other.
                if (isCall()) {
                    to?.takeIf { it.isNotBlank() }?.let { add(it.humanise()) }
                    durationText()?.let { add(it) }
                    directionText()?.let { add(it) }
                    prose?.let { add(it) }
                } else {
                    add(prose ?: transition())
                    metaExtra()?.let { add(it) }
                }
            }.filterNot { it.isNullOrBlank() }

            return parts.joinToString(" · ").takeIf { it.isNotBlank() }
        }

    private fun isCall(): Boolean =
        resolvedType?.lowercase().orEmpty().let { it.startsWith("call_") || it == "call" }

    /** `duration_seconds` rendered the way the calls list renders it. */
    private fun durationText(): String? {
        val seconds = metaNumber("duration_seconds")?.toInt() ?: return null
        if (seconds <= 0) return null
        return "${seconds / 60}m ${seconds % 60}s"
    }

    private fun directionText(): String? =
        (meta?.get("direction") as? String)?.trim()?.takeIf { it.isNotEmpty() }?.humanise()

    /**
     * The one fact from [meta] worth a line, per event type.
     *
     * Only the documented keys, and only where the event would otherwise say nothing
     * useful: a junking with no reason, or an update that does not name what changed.
     */
    private fun metaExtra(): String? {
        val word = resolvedType?.lowercase().orEmpty()
        return when {
            word.contains("junk") -> flattenJson(meta?.get("junk_category"))?.humanise()
            word.contains("details_updated") -> flattenJson(meta?.get("fields"))
            else -> null
        }
    }

    /** The note a `note_added` event refers to, where the server recorded one. */
    val noteId: String?
        get() = (meta?.get("note_id") as? String)?.trim()?.takeIf { it.isNotEmpty() }

    /** The call a `call_logged` event refers to, where the server recorded one. */
    val callId: String?
        get() = (meta?.get("call_id") as? String)?.trim()?.takeIf { it.isNotEmpty() }

    /** The call's outcome. On this route it is the event's `to`. */
    val outcome: String?
        get() = to?.trim()?.takeIf { it.isNotEmpty() && isCall() }

    val durationSeconds: Int?
        get() = metaNumber("duration_seconds")?.toInt()

    /** A meta value that should be a number, whichever JSON type it arrived as. */
    private fun metaNumber(key: String): Double? = when (val raw = meta?.get(key)) {
        is Number -> raw.toDouble()
        is String -> raw.trim().toDoubleOrNull()
        else -> null
    }

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
    noteId = noteId,
    callId = callId,
    outcome = outcome,
    durationSeconds = durationSeconds,
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
                var meta: Map<String, Any?>? = null

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

                        // Kept as it arrives. Everything else on the event is flattened to
                        // text, but meta's values are read by key and a number has to stay
                        // a number for `duration_seconds` to be usable.
                        "meta" -> {
                            @Suppress("UNCHECKED_CAST")
                            meta = reader.readJsonValue() as? Map<String, Any?>
                        }

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
                    meta = meta,
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
