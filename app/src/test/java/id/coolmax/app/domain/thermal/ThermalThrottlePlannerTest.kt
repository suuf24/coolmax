package id.coolmax.app.domain.thermal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThermalThrottlePlannerTest {

    private val freqs = listOf(500_000L, 1_000_000L, 1_500_000L, 2_000_000L)

    @Test
    fun `steps down one frequency step above the limit and captures the baseline`() {
        val decision = ThermalThrottlePlanner.decide(
            temperatureCelsius = 75f,
            limitCelsius = 70f,
            currentMaxByPolicy = mapOf(0 to 2_000_000L),
            frequenciesByPolicy = mapOf(0 to freqs),
            baselineMaxByPolicy = emptyMap()
        )
        assertEquals(ThermalAction.STEP_DOWN, decision.action)
        assertEquals(1_500_000L, decision.targetMaxByPolicy[0])
        assertEquals(2_000_000L, decision.baselineMaxByPolicy[0])
        assertTrue(decision.throttling)
    }

    @Test
    fun `holds inside the hysteresis band instead of flapping`() {
        val decision = ThermalThrottlePlanner.decide(
            temperatureCelsius = 68f, // limit 70 - 4 = 66, so still inside the band
            limitCelsius = 70f,
            currentMaxByPolicy = mapOf(0 to 1_500_000L),
            frequenciesByPolicy = mapOf(0 to freqs),
            baselineMaxByPolicy = mapOf(0 to 2_000_000L)
        )
        assertEquals(ThermalAction.IDLE, decision.action)
        assertTrue(decision.targetMaxByPolicy.isEmpty())
        assertEquals(2_000_000L, decision.baselineMaxByPolicy[0])
        assertTrue(decision.throttling)
    }

    @Test
    fun `steps up gradually below the hysteresis threshold`() {
        val decision = ThermalThrottlePlanner.decide(
            temperatureCelsius = 60f,
            limitCelsius = 70f,
            currentMaxByPolicy = mapOf(0 to 1_000_000L),
            frequenciesByPolicy = mapOf(0 to freqs),
            baselineMaxByPolicy = mapOf(0 to 2_000_000L)
        )
        assertEquals(ThermalAction.STEP_UP, decision.action)
        assertEquals(1_500_000L, decision.targetMaxByPolicy[0])
        assertEquals(2_000_000L, decision.baselineMaxByPolicy[0])
    }

    @Test
    fun `restores the baseline and ends the episode when the ceiling can go all the way back`() {
        val decision = ThermalThrottlePlanner.decide(
            temperatureCelsius = 60f,
            limitCelsius = 70f,
            currentMaxByPolicy = mapOf(0 to 1_500_000L),
            frequenciesByPolicy = mapOf(0 to freqs),
            baselineMaxByPolicy = mapOf(0 to 2_000_000L)
        )
        assertEquals(ThermalAction.RESTORE, decision.action)
        assertEquals(2_000_000L, decision.targetMaxByPolicy[0])
        assertTrue(decision.baselineMaxByPolicy.isEmpty())
        assertFalse(decision.throttling)
    }

    @Test
    fun `never throttles below the 30 percent hardware floor`() {
        // 30 % of 2 GHz = 600 MHz, so 1000 MHz is already the lowest allowed ceiling.
        val decision = ThermalThrottlePlanner.decide(
            temperatureCelsius = 90f,
            limitCelsius = 70f,
            currentMaxByPolicy = mapOf(0 to 1_000_000L),
            frequenciesByPolicy = mapOf(0 to freqs),
            baselineMaxByPolicy = mapOf(0 to 1_000_000L)
        )
        assertEquals(ThermalAction.IDLE, decision.action)
        assertTrue(decision.targetMaxByPolicy.isEmpty())
        assertTrue(decision.throttling)
    }

    @Test
    fun `throttles the hot cluster while a cluster already at the floor stays put`() {
        val decision = ThermalThrottlePlanner.decide(
            temperatureCelsius = 80f,
            limitCelsius = 70f,
            currentMaxByPolicy = mapOf(0 to 1_000_000L, 6 to 2_000_000L),
            frequenciesByPolicy = mapOf(0 to freqs, 6 to freqs),
            baselineMaxByPolicy = emptyMap()
        )
        assertEquals(ThermalAction.STEP_DOWN, decision.action)
        assertEquals(1_000_000L, decision.targetMaxByPolicy[0])
        assertEquals(1_500_000L, decision.targetMaxByPolicy[6])
        assertEquals(1_000_000L, decision.baselineMaxByPolicy[0])
        assertEquals(2_000_000L, decision.baselineMaxByPolicy[6])
    }

    @Test
    fun `unknown temperature never triggers a write`() {
        val decision = ThermalThrottlePlanner.decide(
            temperatureCelsius = null,
            limitCelsius = 70f,
            currentMaxByPolicy = mapOf(0 to 2_000_000L),
            frequenciesByPolicy = mapOf(0 to freqs),
            baselineMaxByPolicy = emptyMap()
        )
        assertEquals(ThermalAction.IDLE, decision.action)
        assertTrue(decision.targetMaxByPolicy.isEmpty())
    }

    @Test
    fun `limit is clamped into the supported safe range and high values are flagged`() {
        assertEquals(40f, ThermalThrottlePlanner.sanitizeLimit(20f))
        assertEquals(95f, ThermalThrottlePlanner.sanitizeLimit(120f))
        assertTrue(ThermalThrottlePlanner.isHighRisk(85f))
        assertFalse(ThermalThrottlePlanner.isHighRisk(84.9f))
    }
}
