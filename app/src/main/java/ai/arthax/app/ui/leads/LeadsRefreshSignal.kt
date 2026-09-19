package ai.arthax.app.ui.leads

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "The lead list changed on the server" — a nudge from a push to whichever LeadsViewModel
 * is alive. No replay, on purpose: a fresh screen loads on its own, and a nudge that lands
 * while no screen is collecting is covered by the reload on resume, so replaying it would
 * only fetch the same page twice.
 */
@Singleton
class LeadsRefreshSignal @Inject constructor() {

    private val _events = MutableSharedFlow<String>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val events: SharedFlow<String> = _events.asSharedFlow()

    fun request(reason: String) {
        _events.tryEmit(reason)
    }
}
