package id.coolmax.app.domain.trigger

import id.coolmax.app.domain.model.TriggerType
import java.util.Locale

/**
 * Evaluates active system conditions and resolves which trigger wins according to the
 * strict documented priority hierarchy:
 *
 * `SAFE_MODE (100) > HIGH_TEMPERATURE (90) > MANUAL (80) > PER_APP (70) > SCREEN_OFF (60) > BATTERY_LOW (55) > SCHEDULE (50) > SYSTEM_DEFAULT (10)`
 */
data class TriggerEvaluationInput(
    val isSafeModeActive: Boolean,
    val currentMaxTempCelsius: Float?,
    val thermalThresholdCelsius: Float,
    val hasActiveManualOverride: Boolean,
    val isScreenOff: Boolean,
    val isScreenOffProfileEnabled: Boolean,
    val isCharging: Boolean,
    val skipScreenOffWhenCharging: Boolean,
    val isMediaPlaying: Boolean,
    val skipScreenOffWhenMediaPlaying: Boolean,
    val foregroundPackageName: String?,
    val perAppTriggerEnabled: Boolean,
    val configuredPerAppPackages: Set<String>,
    val batteryTriggerEnabled: Boolean,
    val batteryLevelPercent: Int,
    val batteryThresholdPercent: Int,
    val applySaverOnCharging: Boolean,
    val scheduleTriggerEnabled: Boolean,
    val currentHour: Int,
    val currentMinute: Int,
    val scheduleStartHour: Int,
    val scheduleStartMinute: Int,
    val scheduleEndHour: Int,
    val scheduleEndMinute: Int
)

data class TriggerResolution(
    val winningTrigger: TriggerType,
    val reason: String,
    val matchedAppPackage: String? = null
)

object TriggerPriorityResolver {

    fun resolve(input: TriggerEvaluationInput): TriggerResolution {
        // 1. Safe Mode (highest priority)
        if (input.isSafeModeActive) {
            return TriggerResolution(
                winningTrigger = TriggerType.SAFE_MODE,
                reason = "Safe Mode is active due to previous unfinished apply or bootloop guard."
            )
        }

        // 2. High Temperature Protection
        val temp = input.currentMaxTempCelsius
        if (temp != null && temp > input.thermalThresholdCelsius) {
            return TriggerResolution(
                winningTrigger = TriggerType.HIGH_TEMPERATURE,
                reason = "CPU temperature (${String.format(Locale.US, "%.1f", temp)}°C) exceeded safety threshold (${String.format(Locale.US, "%.1f", input.thermalThresholdCelsius)}°C)."
            )
        }

        // 3. Manual Override (when screen is on or user explicitly applied manual profile)
        if (input.hasActiveManualOverride && !input.isScreenOff) {
            return TriggerResolution(
                winningTrigger = TriggerType.MANUAL,
                reason = "User-confirmed manual frequency configuration is active."
            )
        }

        // 4. Per-App Profile (only relevant while screen is ON)
        if (!input.isScreenOff && input.perAppTriggerEnabled && !input.foregroundPackageName.isNullOrBlank()) {
            if (input.foregroundPackageName in input.configuredPerAppPackages) {
                return TriggerResolution(
                    winningTrigger = TriggerType.PER_APP,
                    reason = "Foreground app ${input.foregroundPackageName} matched per-app profile.",
                    matchedAppPackage = input.foregroundPackageName
                )
            }
        }

        // 5. Screen-Off Profile (core differentiating feature)
        if (input.isScreenOff && input.isScreenOffProfileEnabled) {
            val skippedByCharging = input.isCharging && input.skipScreenOffWhenCharging
            val skippedByMedia = input.isMediaPlaying && input.skipScreenOffWhenMediaPlaying
            if (!skippedByCharging && !skippedByMedia) {
                return TriggerResolution(
                    winningTrigger = TriggerType.SCREEN_OFF,
                    reason = "Screen is OFF and exclusions (charging/media) are not blocking."
                )
            }
        }

        // 6. Low Battery / Charging Saver Trigger
        if (input.batteryTriggerEnabled) {
            if (input.batteryLevelPercent in 1..input.batteryThresholdPercent) {
                return TriggerResolution(
                    winningTrigger = TriggerType.BATTERY_LOW,
                    reason = "Battery level (${input.batteryLevelPercent}%) is at or below ${input.batteryThresholdPercent}%."
                )
            }
            if (input.isCharging && input.applySaverOnCharging) {
                return TriggerResolution(
                    winningTrigger = TriggerType.BATTERY_LOW,
                    reason = "Device is charging with charging battery-saver trigger enabled."
                )
            }
        }

        // 7. Time Schedule Trigger
        if (input.scheduleTriggerEnabled && isTimeWithinWindow(
                currentHour = input.currentHour,
                currentMinute = input.currentMinute,
                startHour = input.scheduleStartHour,
                startMinute = input.scheduleStartMinute,
                endHour = input.scheduleEndHour,
                endMinute = input.scheduleEndMinute
            )
        ) {
            return TriggerResolution(
                winningTrigger = TriggerType.SCHEDULE,
                reason = "Current time (%02d:%02d) is within scheduled window (%02d:%02d–%02d:%02d).".format(
                    input.currentHour, input.currentMinute,
                    input.scheduleStartHour, input.scheduleStartMinute,
                    input.scheduleEndHour, input.scheduleEndMinute
                )
            )
        }

        // 8. Fallback to System Default / Screen-On Baseline
        return TriggerResolution(
            winningTrigger = TriggerType.SYSTEM_DEFAULT,
            reason = "Standard screen-on / baseline system profile active."
        )
    }

    /**
     * Evaluates whether `currentHour:currentMinute` falls within `[start, end)`,
     * supporting both same-day windows (e.g. 09:00–17:00) and overnight windows (e.g. 23:00–06:00).
     */
    fun isTimeWithinWindow(
        currentHour: Int,
        currentMinute: Int,
        startHour: Int,
        startMinute: Int,
        endHour: Int,
        endMinute: Int
    ): Boolean {
        val currentTotal = currentHour * 60 + currentMinute
        val startTotal = startHour * 60 + startMinute
        val endTotal = endHour * 60 + endMinute

        if (startTotal == endTotal) return false
        return if (startTotal < endTotal) {
            currentTotal in startTotal until endTotal
        } else {
            // Spans midnight (e.g. 23:00 to 06:00)
            currentTotal >= startTotal || currentTotal < endTotal
        }
    }
}
