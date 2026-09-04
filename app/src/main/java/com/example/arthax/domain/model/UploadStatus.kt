package com.example.arthax.domain.model

enum class UploadStatus {
    /** Recording captured and copied locally, waiting for a network window. */
    PENDING,
    UPLOADING,
    SUCCESS,

    /** Retryable failure — WorkManager will come back to it. */
    RETRYING,

    /** Given up: server rejected it, or we exhausted the retry budget. Needs a human. */
    FAILED,
}
