package ai.arthax.app.domain.model

/** Server field `direction` on POST /api/calls/. */
enum class CallDirection(val api: String) {
    OUTBOUND("outbound"),
    INBOUND("inbound"),
}
