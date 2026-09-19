package ai.arthax.app.push

import ai.arthax.app.domain.model.MatchSource
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/** "Please call this lead" — from a colleague in the CRM or from a lead notification. */
data class DialRequest(
    val leadId: String,
    val leadName: String,
    val phone: String,
    /** Who asked, for the confirmation; blank when nobody in particular did. */
    val requestedBy: String = "",
    val requestId: String? = null,
    /** What the CRM is told about how the call started. */
    val source: MatchSource = MatchSource.WEB,
)

/**
 * Dial requests that arrived while the app is on screen.
 *
 * A notification for a request the rep is looking at the app for is the wrong shape: they
 * would have to reach up to the shade for something the screen in front of them could ask.
 * So while [ai.arthax.app.MainActivity] is started it collects this flow and shows a
 * confirm sheet instead; when nothing is collecting, the push handler posts the
 * notification. No replay, on purpose — a request that found no screen must become a
 * notification, not a sheet that pops up an hour later.
 */
@Singleton
class DialRequests @Inject constructor() {

    private val _requests = MutableSharedFlow<DialRequest>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val requests: SharedFlow<DialRequest> = _requests.asSharedFlow()

    /** True when a screen took it; false means "post a notification instead". */
    fun offer(request: DialRequest): Boolean =
        _requests.subscriptionCount.value > 0 && _requests.tryEmit(request)
}
