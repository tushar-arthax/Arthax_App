package com.example.arthax.domain.model

/**
 * Server enum for POST /api/calls/ `call_status`: connected | missed | rejected | not_answered.
 * This is what physically happened to the call.
 */
enum class CallStatus(val api: String) {
    CONNECTED("connected"),
    MISSED("missed"),
    REJECTED("rejected"),
    NOT_ANSWERED("not_answered"),
}

/**
 * Server enum for `outcome`: what the call meant for the deal.
 *
 * The app can only observe the first two automatically — whether the call connected. The
 * rest are judgement calls a rep would set in the CRM, so they are listed here for
 * completeness and for when a disposition picker is added, but never guessed at.
 */
enum class CallOutcome(val api: String, val label: String) {
    CONNECTED("connected", "Connected"),
    NOT_PICKED("not_picked", "Not picked up"),
    CALLBACK("callback", "Callback requested"),
    WRONG_NUMBER("wrong_number", "Wrong number"),
    NOT_INTERESTED("not_interested", "Not interested"),
    VOICEMAIL("voicemail", "Voicemail"),
    FOLLOW_UP("follow_up", "Follow up"),
    DEMO_SCHEDULED("demo_scheduled", "Demo scheduled"),
    REJECTED("rejected", "Rejected"),
    ;

    companion object {
        /** The two outcomes the device can determine on its own, with no rep input. */
        fun fromConnected(connected: Boolean): CallOutcome = if (connected) CONNECTED else NOT_PICKED
    }
}
