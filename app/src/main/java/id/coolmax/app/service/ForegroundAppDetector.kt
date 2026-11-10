package id.coolmax.app.service

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Process

/**
 * Detects the currently active foreground application using the official Android SDK
 * `UsageStatsManager` API (never hidden or reflection APIs).
 */
class ForegroundAppDetector(private val context: Context) {

    fun hasUsageStatsPermission(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        // unsafeCheckOpNoThrow() only exists from API 29; minSdk is 26, so Android 8 and 9 must take
        // the deprecated checkOpNoThrow() path or the call throws NoSuchMethodError.
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun getForegroundPackageName(): String? {
        if (!hasUsageStatsPermission()) return null
        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return null

        val endTime = System.currentTimeMillis()
        val beginTime = endTime - 60_000L
        val usageEvents = usageStatsManager.queryEvents(beginTime, endTime) ?: return null

        val event = UsageEvents.Event()
        var lastForegroundPkg: String? = null
        var lastTimestamp = 0L

        while (usageEvents.hasNextEvent()) {
            usageEvents.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED && event.timeStamp >= lastTimestamp) {
                lastTimestamp = event.timeStamp
                lastForegroundPkg = event.packageName
            }
        }
        return lastForegroundPkg
    }
}
