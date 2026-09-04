package com.example.arthax.data.remote.dto

import com.example.arthax.domain.model.Lead
import com.example.arthax.domain.model.LeadTemperature
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/** GET /api/leads/ — paginated envelope. */
@JsonClass(generateAdapter = true)
data class LeadPageDto(
    @Json(name = "items") val items: List<LeadDto> = emptyList(),
    @Json(name = "total") val total: Int = 0,
    @Json(name = "page") val page: Int = 0,
    @Json(name = "size") val size: Int = 0,
    @Json(name = "pages") val pages: Int = 0,
)

/**
 * Only the fields this app actually shows or sends are modelled. Moshi ignores unknown
 * keys, so the free-form `custom_fields` / `client_demographics` blobs and the AI
 * scoring fields are deliberately left off rather than modelled as loose maps that
 * would only add parsing failure modes.
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
)
