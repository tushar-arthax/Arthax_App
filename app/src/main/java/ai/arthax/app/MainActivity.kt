package ai.arthax.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import ai.arthax.app.call.CallMonitorService
import ai.arthax.app.data.local.prefs.SecureTokenStore
import ai.arthax.app.data.repository.RemoteConfigRepository
import ai.arthax.app.data.repository.SyncHealthReporter
import ai.arthax.app.di.ApplicationScope
import ai.arthax.app.push.DialRequest
import ai.arthax.app.push.DialRequestPlacer
import ai.arthax.app.push.DialRequests
import ai.arthax.app.ui.navigation.MainTab
import ai.arthax.app.ui.navigation.NavRequest
import ai.arthax.app.ui.navigation.NavRequests
import ai.arthax.app.work.WorkScheduler
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import ai.arthax.app.ui.navigation.ArthaxApp
import ai.arthax.app.ui.navigation.DialConfirmSheet
import ai.arthax.app.ui.theme.ArthaxTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var workScheduler: WorkScheduler

    @Inject lateinit var tokenStore: SecureTokenStore

    @Inject lateinit var healthReporter: SyncHealthReporter

    @Inject lateinit var remoteConfig: RemoteConfigRepository

    @Inject lateinit var navRequests: NavRequests

    @Inject lateinit var dialRequests: DialRequests

    @Inject lateinit var dialPlacer: DialRequestPlacer

    @Inject @ApplicationScope lateinit var appScope: CoroutineScope

    /** A CRM call request that arrived while this screen was up; shown as a sheet. */
    private var pendingDial by mutableStateOf<DialRequest?>(null)

    /**
     * Checks for missed calls every time the app comes to the foreground.
     *
     * The equivalent call in Application.onCreate only runs when the *process* starts, so a
     * process that stayed alive — the normal case — never re-checked. That is why granting
     * the call log permission and returning to the app appeared to do nothing: the app had
     * already decided, minutes earlier, that it had no permission.
     *
     * This also covers the phone whose PHONE_STATE broadcast never arrives: whatever was
     * missed in the background is picked up the moment the rep opens the app.
     */
    override fun onResume() {
        super.onResume()

        // Restarted on every resume rather than only at process start: the permission may
        // have just been granted, and on aggressive OEMs the service may have been killed
        // while the app was away. Starting an already-running service simply triggers a
        // catch-up check, so this is cheap and idempotent.
        if (tokenStore.isLoggedIn) {
            CallMonitorService.start(this, "app opened")

            // The heartbeat, when the last one is older than its interval, and a config
            // check on the same schedule. Off the activity's lifetime on purpose: the rep
            // rotating the phone must not cancel a request halfway.
            appScope.launch {
                runCatching {
                    if (!healthReporter.sendIfDue()) remoteConfig.refreshIfStale()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        handleDeepLink(intent)

        // Only while started: a request that lands with the app in the background is a
        // notification's job, and DialRequests.offer() sees no subscriber then.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                dialRequests.requests.collect { pendingDial = it }
            }
        }

        setContent {
            ArthaxTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    ArthaxApp(
                        navRequests = navRequests,
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background)
                            .windowInsetsPadding(WindowInsets.systemBars),
                    )

                    pendingDial?.let { request ->
                        DialConfirmSheet(
                            request = request,
                            onCall = {
                                pendingDial = null
                                // App scope, not the activity's: the dialler coming to the
                                // front stops this activity, and the call must still be placed.
                                appScope.launch { runCatching { dialPlacer.place(request) } }
                            },
                            onDismiss = { pendingDial = null },
                        )
                    }
                }
            }
        }
    }

    /** singleTask launch mode: a notification tap on a running app lands here. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLink(intent)
    }

    private fun handleDeepLink(intent: Intent?) {
        intent ?: return
        val tab = MainTab.fromExtra(intent.getStringExtra(EXTRA_OPEN_TAB))
        val leadId = intent.getStringExtra(EXTRA_LEAD_ID)?.takeIf { it.isNotBlank() }
        val search = intent.getStringExtra(EXTRA_SEARCH)?.takeIf { it.isNotBlank() }
        if (tab == null && leadId == null && search == null) return

        navRequests.post(NavRequest(tab = tab ?: MainTab.LEADS, leadId = leadId, search = search))

        // Consumed: a rotation must not re-apply the same jump.
        intent.removeExtra(EXTRA_OPEN_TAB)
        intent.removeExtra(EXTRA_LEAD_ID)
        intent.removeExtra(EXTRA_SEARCH)
    }

    companion object {
        const val EXTRA_OPEN_TAB = "open_tab"
        const val EXTRA_LEAD_ID = "lead_id"
        const val EXTRA_SEARCH = "search"

        /** An intent that opens the app on [tab], optionally focused on a lead. */
        fun openIntent(
            context: Context,
            tab: MainTab? = null,
            leadId: String? = null,
            search: String? = null,
        ): Intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .apply {
                tab?.let { putExtra(EXTRA_OPEN_TAB, it.extra) }
                leadId?.let { putExtra(EXTRA_LEAD_ID, it) }
                search?.let { putExtra(EXTRA_SEARCH, it) }
            }
    }
}
