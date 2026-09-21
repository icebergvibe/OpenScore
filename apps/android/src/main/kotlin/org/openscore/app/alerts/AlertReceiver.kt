package org.openscore.app.alerts

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** A poll may need a few requests, so the work is bounded well inside the broadcast budget. */
private const val WORK_TIMEOUT_MS = 20_000L

private fun BroadcastReceiver.work(app: Context, block: suspend () -> Unit) {
    val result = goAsync()
    CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
        try {
            // A pass that ran out of time never reached the point of arming the next alarm, and
            // an unarmed chain is dead until the app is next opened; so the queue as it stands
            // is re-armed from here.
            if (withTimeoutOrNull(WORK_TIMEOUT_MS) { block() } == null) AlertScheduler.rearm(app)
        } finally {
            result.finish()
        }
    }
}

/**
 * Wakes for a due alert or the daily rebuild.
 *
 * goAsync plus a timeout rather than WorkManager: WorkManager would add its own boot receiver,
 * a wake lock and a content provider to the manifest, which is a poor trade for a job that runs
 * a few times a day and is allowed to miss one. Nothing here needs Google Play services.
 */
class AlertReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        when (intent.action) {
            AlertScheduler.ACTION_FIRE -> work(app) { AlertScheduler.fire(app) }
            AlertScheduler.ACTION_REFRESH -> work(app) { AlertScheduler.refresh(app) }
        }
    }
}

/** Alarms do not survive a reboot or an app update, so the queue is rebuilt from what is stored. */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && intent.action == AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED) {
            // Granting the special access does not restore alarms that were already scheduled as
            // inexact. Replace them from the durable queue immediately.
            if (AlertScheduler.canSchedulePrecisely(app)) {
                AlertScheduler.rearm(app)
                work(app) { AlertScheduler.deliverOverdue(app) }
            }
            return
        }
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        work(app) { AlertScheduler.refresh(app) }
    }
}
