package ai.arthax.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import ai.arthax.app.call.CallMonitorService
import ai.arthax.app.data.local.prefs.SecureTokenStore
import ai.arthax.app.data.repository.RemoteConfigRepository
import ai.arthax.app.data.repository.SyncHealthReporter
import ai.arthax.app.di.ApplicationScope
import ai.arthax.app.work.WorkScheduler
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
import ai.arthax.app.ui.theme.ArthaxTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var workScheduler: WorkScheduler

    @Inject lateinit var tokenStore: SecureTokenStore

    @Inject lateinit var healthReporter: SyncHealthReporter

    @Inject lateinit var remoteConfig: RemoteConfigRepository

    @Inject @ApplicationScope lateinit var appScope: CoroutineScope

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

        setContent {
            ArthaxTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    ArthaxApp(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background)
                            .windowInsetsPadding(WindowInsets.systemBars),
                    )
                }
            }
        }
    }
}
