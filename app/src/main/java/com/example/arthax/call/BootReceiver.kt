package com.example.arthax.call

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.arthax.work.WorkScheduler
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
        CallMonitorService.start(context, "the phone restarted")
        workScheduler.ensurePeriodicWork()
    }
}
