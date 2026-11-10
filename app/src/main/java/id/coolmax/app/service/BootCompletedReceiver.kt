package id.coolmax.app.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import id.coolmax.app.CoolMaxApplication
import id.coolmax.app.domain.model.TriggerType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Handles `ACTION_BOOT_COMPLETED` with strict anti-bootloop protection:
 * 1. Immediately checks if `pending_apply` flag is still set from before the reboot.
 *    If set -> enters SAFE MODE, disables all auto-apply toggles, restores initial snapshot, and stops.
 * 2. Otherwise, if Screen-Off or automatic triggers are enabled, waits a mandatory 60-second safety delay
 *    after `BOOT_COMPLETED` before starting [ScreenOffGuardService].
 *
 * The grace period is scheduled with an [AlarmManager] alarm instead of keeping the broadcast pending:
 * a manifest receiver must return within the broadcast timeout (~10 s), so holding `goAsync()` open for
 * 60 s made the system report "CoolMax isn't responding" on every boot (and on every APK update, when
 * the sticky BOOT_COMPLETED broadcast is redelivered).
 */
class BootCompletedReceiver : BroadcastReceiver() {

    companion object {
        const val BOOT_SAFETY_DELAY_MS = 60_000L // 60 seconds mandatory grace period after BOOT_COMPLETED

        /** Internal alarm action: the grace period elapsed, so the guard may start now. */
        const val ACTION_BOOT_GRACE_ELAPSED = "id.coolmax.app.ACTION_BOOT_GRACE_ELAPSED"

        private const val GRACE_REQUEST_CODE = 4101
    }

    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            ACTION_BOOT_GRACE_ELAPSED -> handleGraceElapsed(context)
            Intent.ACTION_BOOT_COMPLETED, "android.intent.action.QUICKBOOT_POWERON" -> handleBoot(context)
        }
    }

    private fun handleBoot(context: Context) {
        val app = context.applicationContext as? CoolMaxApplication ?: return

        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                // Safety path first: a leftover pending apply means the previous session may have bootlooped.
                if (app.preferences.checkAndEnterSafeModeIfPendingApplyLeftover()) {
                    app.cpuRepository.restoreSystemDefaultSnapshot(TriggerType.SAFE_MODE)
                    return@launch
                }

                if (shouldStartService(app)) {
                    scheduleGraceAlarm(context)
                }
            } finally {
                // Return the broadcast well inside the receiver timeout; the alarm now owns the delay.
                pendingResult.finish()
            }
        }
    }

    private suspend fun shouldStartService(app: CoolMaxApplication): Boolean {
        val screenOffSettings = app.preferences.screenOffSettingsFlow.first()
        val triggerSettings = app.preferences.triggerSettingsFlow.first()
        return screenOffSettings.enabled ||
            triggerSettings.batteryTriggerEnabled ||
            triggerSettings.scheduleTriggerEnabled ||
            triggerSettings.perAppTriggerEnabled ||
            triggerSettings.thermalGuardEnabled
    }

    /** Starts the guard 60 s after boot, so the user always has time to unlock and intervene. */
    private fun scheduleGraceAlarm(context: Context) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            GRACE_REQUEST_CODE,
            Intent(context, BootCompletedReceiver::class.java).setAction(ACTION_BOOT_GRACE_ELAPSED),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        runCatching {
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + BOOT_SAFETY_DELAY_MS,
                pendingIntent
            )
        }
    }

    private fun handleGraceElapsed(context: Context) {
        val app = context.applicationContext as? CoolMaxApplication ?: return
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                if (!app.preferences.safeModeActiveFlow.first()) {
                    ScreenOffGuardService.startServiceIfNeeded(context)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
