package com.example.arthax.domain.model

enum class LogLevel { INFO, SUCCESS, WARN, ERROR }

/** Which part of the pipeline emitted a log line — drives the filter chips on the Logs tab. */
enum class LogStage(val label: String) {
    AUTH("Auth"),
    SETUP("Setup"),
    CALL("Call"),
    DETECT("Detection"),

    /** Raw request/response tracing, so a backend fault can be pinned without a laptop. */
    NETWORK("Network"),
    SYNC("Sync"),
    ;

    companion object {
        fun fromNameOrNull(raw: String?) = entries.firstOrNull { it.name == raw }
    }
}
