package ai.arthax.app.ui.common

import ai.arthax.app.data.remote.api.ApiResult

/**
 * Turns a failure into something worth showing a rep.
 *
 * Deliberately here rather than in `safeApiCall`. The upload and call-create paths read the
 * server's own `detail` text to decide whether a recording is permanently rejected or worth
 * retrying, so rewriting those strings at the source would change which calls get retried.
 * Nothing on that path uses this function: it exists purely to decide what appears on the
 * lead screens.
 *
 * The rules come from the API's documented error table. Two of them matter most, because
 * they are the ones that hit some reps and not others and look identical from the outside:
 * a lead reassigned away from a rep answers 403, and a deleted lead answers 404. Both used
 * to surface as the server's own wording, which explains neither.
 */
fun ApiResult.Failure.humanMessage(): String = when (this) {

    is ApiResult.Failure.Unauthorized -> when {
        detail.mentions("another device") ->
            "You signed in on another phone. Please sign in again."

        // "NO_ACTIVE_PLAN: your organisation..." — the code is for the log, not the rep.
        detail.mentions("NO_ACTIVE_PLAN") || detail.mentions("WORKSPACE_READ_ONLY") ->
            detail.afterCode() ?: message.safe()

        detail.mentions("Not authorized to view") || detail.mentions("Not authorized to update") ->
            "This lead is no longer assigned to you."

        else -> message.safe()
    }

    is ApiResult.Failure.Rejected -> when {
        code == 404 || detail.mentions("Lead not found") -> "This lead was deleted."
        code == 413 -> "That was too large for the server to accept."
        else -> message.safe()
    }

    // A crashed server answers plain text and a restarting one answers nginx's HTML. Neither
    // is anything a rep can act on, and the HTML must never reach the screen.
    is ApiResult.Failure.Server -> "Something went wrong. Please try again."

    is ApiResult.Failure.Throttled -> "The server is busy. Please try again in a moment."

    is ApiResult.Failure.Network -> message.safe()

    // Kept specific on purpose. This is the bucket a response we could not read lands in,
    // and the parser's own words are the only thing that makes such a failure diagnosable.
    is ApiResult.Failure.Unexpected ->
        listOfNotNull(message.safe(), detail?.safe()?.takeIf { it != message })
            .distinct()
            .joinToString(" — ")

    else -> message.safe()
}

private fun String?.mentions(word: String): Boolean =
    this != null && contains(word, ignoreCase = true)

/** The half of "CODE: a sentence" that a person should read. */
private fun String?.afterCode(): String? =
    this?.substringAfter(':', missingDelimiterValue = "")?.trim()?.takeIf { it.isNotEmpty() }

/**
 * Anything that looks like markup or a stack trace, replaced.
 *
 * A 502 from nginx is an HTML page and a crash is a Java trace; both arrive as the error
 * body and both used to be shown verbatim.
 */
private fun String?.safe(): String {
    val text = this?.trim().orEmpty()
    val unusable = text.isEmpty() ||
        text.startsWith("<") ||
        text.contains("<html", ignoreCase = true) ||
        text.contains("Exception:") ||
        text.contains("Traceback")

    return if (unusable) "Something went wrong. Please try again." else text
}
