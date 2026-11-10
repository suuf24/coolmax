package id.coolmax.app.domain.thermal

import id.coolmax.app.domain.safety.SafeFrequencyPlanner
import kotlin.math.roundToLong

/**
 * Tunable constants for the manual thermal limit (Feature: custom thermal limit).
 * All temperature values are in °C.
 */
object ThermalGuardConfig {
    const val MIN_LIMIT_CELSIUS = 40f
    const val MAX_LIMIT_CELSIUS = 95f
    const val DEFAULT_LIMIT_CELSIUS = 70f

    /** Above this value the UI shows an explicit "risky" warning. */
    const val HIGH_RISK_WARNING_CELSIUS = 85f

    /** Release margin: throttling only steps back up below `limit - hysteresis`. */
    const val HYSTERESIS_CELSIUS = 4f

    /** Polling interval while the screen is ON (2–5 s per spec). */
    const val SCREEN_ON_POLL_MS = 4_000L

    /** Cheaper polling while the screen is OFF. */
    const val SCREEN_OFF_POLL_MS = 15_000L

    /** Never throttle below the same 30 % floor the rest of the app enforces. */
    const val MIN_FLOOR_RATIO = SafeFrequencyPlanner.MIN_ALLOWED_MAX_FREQ_RATIO
}

enum class ThermalAction {
    /** No sysfs write needed for this tick. */
    IDLE,

    /** Temperature above the limit: lower the ceiling by one frequency step. */
    STEP_DOWN,

    /** Temperature safely below `limit - hysteresis`: raise the ceiling by one step. */
    STEP_UP,

    /** Back at the original ceiling: the thermal episode is over. */
    RESTORE
}

data class ThermalThrottleDecision(
    val action: ThermalAction,
    /** Desired max frequency per policy for this tick (empty for [ThermalAction.IDLE]). */
    val targetMaxByPolicy: Map<Int, Long> = emptyMap(),
    /** Baseline to persist; empty means "no active thermal episode". */
    val baselineMaxByPolicy: Map<Int, Long> = emptyMap(),
    /** True whenever a thermal episode is (or must stay) active. */
    val throttling: Boolean = baselineMaxByPolicy.isNotEmpty()
)

/**
 * Pure step-down/step-up planner for the manual thermal limit.
 *
 * Rules:
 * 1. `temp > limit` → capture the current ceilings as baseline (first time) and move every cluster
 *    one frequency step DOWN (never below 30 % of the cluster hardware maximum).
 * 2. `limit - hysteresis < temp <= limit` → hold (prevents flapping).
 * 3. `temp <= limit - hysteresis` → move one step UP towards the baseline; when the ceiling reaches
 *    the baseline the episode ends with [ThermalAction.RESTORE].
 * 4. Unknown temperature → IDLE (never react to a failed sensor read).
 */
object ThermalThrottlePlanner {

    fun decide(
        temperatureCelsius: Float?,
        limitCelsius: Float,
        currentMaxByPolicy: Map<Int, Long>,
        frequenciesByPolicy: Map<Int, List<Long>>,
        baselineMaxByPolicy: Map<Int, Long>
    ): ThermalThrottleDecision {
        if (temperatureCelsius == null || currentMaxByPolicy.isEmpty()) {
            return ThermalThrottleDecision(
                action = ThermalAction.IDLE,
                baselineMaxByPolicy = baselineMaxByPolicy
            )
        }

        val limit = limitCelsius.coerceIn(ThermalGuardConfig.MIN_LIMIT_CELSIUS, ThermalGuardConfig.MAX_LIMIT_CELSIUS)
        val releasing = temperatureCelsius <= limit - ThermalGuardConfig.HYSTERESIS_CELSIUS

        if (!releasing && temperatureCelsius > limit) {
            val baseline = baselineMaxByPolicy.ifEmpty { currentMaxByPolicy }
            val targets = currentMaxByPolicy.mapValues { (policyId, currentMax) ->
                nextLowerStep(currentMax, frequenciesByPolicy[policyId].orEmpty()) ?: currentMax
            }
            val anyLowered = targets.any { (policyId, target) -> target < (currentMaxByPolicy[policyId] ?: target) }
            return ThermalThrottleDecision(
                action = if (anyLowered) ThermalAction.STEP_DOWN else ThermalAction.IDLE,
                targetMaxByPolicy = if (anyLowered) targets else emptyMap(),
                baselineMaxByPolicy = baseline,
                throttling = true
            )
        }

        if (releasing && baselineMaxByPolicy.isNotEmpty()) {
            val targets = currentMaxByPolicy.mapValues { (policyId, currentMax) ->
                val ceiling = baselineMaxByPolicy[policyId] ?: currentMax
                nextHigherStep(currentMax, frequenciesByPolicy[policyId].orEmpty(), ceiling) ?: currentMax
            }
            val allRestored = currentMaxByPolicy.all { (policyId, currentMax) ->
                targets[policyId]?.let { it >= (baselineMaxByPolicy[policyId] ?: currentMax) } ?: true
            }
            return if (allRestored) {
                ThermalThrottleDecision(
                    action = ThermalAction.RESTORE,
                    targetMaxByPolicy = baselineMaxByPolicy.filterKeys { it in currentMaxByPolicy.keys },
                    baselineMaxByPolicy = emptyMap(),
                    throttling = false
                )
            } else {
                ThermalThrottleDecision(
                    action = ThermalAction.STEP_UP,
                    targetMaxByPolicy = targets,
                    baselineMaxByPolicy = baselineMaxByPolicy,
                    throttling = true
                )
            }
        }

        // Inside the hysteresis band (or no active episode): hold.
        return ThermalThrottleDecision(
            action = ThermalAction.IDLE,
            baselineMaxByPolicy = baselineMaxByPolicy
        )
    }

    /** Lowest ceiling the guard may ever apply: first step at/above 30 % of the cluster maximum. */
    fun floorMaxKhz(frequenciesKhz: List<Long>): Long? {
        val sorted = frequenciesKhz.filter { it > 0L }.distinct().sorted()
        if (sorted.isEmpty()) return null
        val hardwareMax = sorted.last()
        val floorTarget = (hardwareMax * ThermalGuardConfig.MIN_FLOOR_RATIO).roundToLong()
        return sorted.firstOrNull { it >= floorTarget } ?: sorted.last()
    }

    fun nextLowerStep(currentMaxKhz: Long, frequenciesKhz: List<Long>): Long? {
        val floor = floorMaxKhz(frequenciesKhz) ?: return null
        return frequenciesKhz.filter { it in floor..<currentMaxKhz }.maxOrNull()
    }

    fun nextHigherStep(currentMaxKhz: Long, frequenciesKhz: List<Long>, ceilingKhz: Long): Long? {
        val target = minOf(ceilingKhz, frequenciesKhz.filter { it > currentMaxKhz }.minOrNull() ?: ceilingKhz)
        return target.takeIf { it > currentMaxKhz }
    }

    /** Clamps a user-entered limit into the supported safe range. */
    fun sanitizeLimit(valueCelsius: Float): Float =
        valueCelsius.coerceIn(ThermalGuardConfig.MIN_LIMIT_CELSIUS, ThermalGuardConfig.MAX_LIMIT_CELSIUS)

    fun isHighRisk(valueCelsius: Float): Boolean = valueCelsius >= ThermalGuardConfig.HIGH_RISK_WARNING_CELSIUS
}
