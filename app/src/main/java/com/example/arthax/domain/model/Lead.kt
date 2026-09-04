package com.example.arthax.domain.model

/** A lead as shown on the dialling list. */
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
) {
    val isCallable: Boolean get() = phoneNumber.isNotBlank()

    /** "contacted" -> "Contacted", for display. */
    val statusLabel: String?
        get() = status?.replace('_', ' ')?.replaceFirstChar { it.uppercase() }
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
