package id.coolmax.app.domain.trigger

import id.coolmax.app.domain.model.TriggerType
import org.junit.Assert.assertEquals
import org.junit.Test

class TriggerPriorityResolverTest {

    private fun baseInput() = TriggerEvaluationInput(
        isSafeModeActive = false,
        currentMaxTempCelsius = 36.0f,
        thermalThresholdCelsius = 45.0f,
        hasActiveManualOverride = false,
        isScreenOff = false,
        isScreenOffProfileEnabled = true,
        isCharging = false,
        skipScreenOffWhenCharging = true,
        isMediaPlaying = false,
        skipScreenOffWhenMediaPlaying = true,
        foregroundPackageName = null,
        perAppTriggerEnabled = false,
        configuredPerAppPackages = emptySet(),
        batteryTriggerEnabled = false,
        batteryLevelPercent = 80,
        batteryThresholdPercent = 20,
        applySaverOnCharging = false,
        scheduleTriggerEnabled = false,
        currentHour = 14,
        currentMinute = 0,
        scheduleStartHour = 23,
        scheduleStartMinute = 0,
        scheduleEndHour = 6,
        scheduleEndMinute = 0
    )

    @Test
    fun `Safe Mode overrides all other triggers including High Temperature and Manual`() {
        val input = baseInput().copy(
            isSafeModeActive = true,
            currentMaxTempCelsius = 55.0f,
            hasActiveManualOverride = true,
            isScreenOff = true
        )
        val res = TriggerPriorityResolver.resolve(input)
        assertEquals(TriggerType.SAFE_MODE, res.winningTrigger)
    }

    @Test
    fun `High Temperature overrides Manual, Per-App, Screen-Off, and Schedule`() {
        val input = baseInput().copy(
            currentMaxTempCelsius = 48.5f,
            thermalThresholdCelsius = 45.0f,
            hasActiveManualOverride = true
        )
        val res = TriggerPriorityResolver.resolve(input)
        assertEquals(TriggerType.HIGH_TEMPERATURE, res.winningTrigger)
    }

    @Test
    fun `Screen-Off trigger activates when screen is off and not excluded by charging or media`() {
        val activeScreenOff = baseInput().copy(
            isScreenOff = true,
            isScreenOffProfileEnabled = true,
            isCharging = false,
            isMediaPlaying = false
        )
        assertEquals(TriggerType.SCREEN_OFF, TriggerPriorityResolver.resolve(activeScreenOff).winningTrigger)

        // When charging and skipScreenOffWhenCharging = true, Screen-Off is skipped
        val skippedByCharging = activeScreenOff.copy(
            isCharging = true,
            skipScreenOffWhenCharging = true
        )
        assertEquals(TriggerType.SYSTEM_DEFAULT, TriggerPriorityResolver.resolve(skippedByCharging).winningTrigger)
    }

    @Test
    fun `overnight schedule window correctly matches 23_30 and 02_15 but not 12_00`() {
        val lateNight = baseInput().copy(
            scheduleTriggerEnabled = true,
            currentHour = 23,
            currentMinute = 30
        )
        assertEquals(TriggerType.SCHEDULE, TriggerPriorityResolver.resolve(lateNight).winningTrigger)

        val earlyMorning = baseInput().copy(
            scheduleTriggerEnabled = true,
            currentHour = 2,
            currentMinute = 15
        )
        assertEquals(TriggerType.SCHEDULE, TriggerPriorityResolver.resolve(earlyMorning).winningTrigger)

        val midday = baseInput().copy(
            scheduleTriggerEnabled = true,
            currentHour = 12,
            currentMinute = 0
        )
        assertEquals(TriggerType.SYSTEM_DEFAULT, TriggerPriorityResolver.resolve(midday).winningTrigger)
    }
}
