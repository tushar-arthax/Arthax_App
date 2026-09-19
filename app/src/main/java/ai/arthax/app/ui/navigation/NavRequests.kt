package ai.arthax.app.ui.navigation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Where a notification tap wants the app to go. */
data class NavRequest(
    val tab: MainTab,
    val leadId: String? = null,
    /** Prefilled into the leads search — the surest way to land on one lead. */
    val search: String? = null,
    /** Makes two identical requests distinct, so tapping the same notification twice works. */
    val nonce: Long = System.nanoTime(),
)

/**
 * The bridge from [ai.arthax.app.MainActivity]'s intent extras to the Compose tabs. A
 * StateFlow rather than an event, so a request that arrives before the main scaffold is
 * composed — a cold start from a notification — is still there when it is.
 */
@Singleton
class NavRequests @Inject constructor() {

    private val _pending = MutableStateFlow<NavRequest?>(null)
    val pending: StateFlow<NavRequest?> = _pending.asStateFlow()

    fun post(request: NavRequest) {
        _pending.value = request
    }
}
