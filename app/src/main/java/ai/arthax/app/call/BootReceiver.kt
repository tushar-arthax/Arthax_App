package ai.arthax.app.call

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import ai.arthax.app.work.WorkScheduler
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Catches up after a restart.
 *
 * A phone that reboots between a call and the next time Arthax is opened would otherwise
 * leave that call sitting unprocessed. The reconcile is watermark-driven, so this simply
 * picks up wherever the last one stopped.
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var workScheduler: WorkScheduler

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        // A receiver that throws takes the whole process down at boot, on every boot. Both
        // calls are safety nets, so a failure here is logged and nothing more: the app
        // opening later re-runs each of them anyway.
        runCatching {
            CallMonitorService.start(context, "the phone restarted")
            workScheduler.ensurePeriodicWork()
        }.onFailure { Log.e(TAG, "Post-boot catch-up could not be started", it) }
    }

    private companion object {
        const val TAG = "ArthaxBoot"
    }
}
