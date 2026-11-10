package id.coolmax.app.domain.safety

import id.coolmax.app.domain.model.ClusterConfig
import id.coolmax.app.domain.model.CpuCluster
import id.coolmax.app.domain.model.PresetType
import kotlin.math.roundToLong

/**
 * Represents a single validated sysfs write operation produced by [SafeFrequencyPlanner].
 */
data class SysfsWriteOperation(
    val path: String,
    val value: String,
    val description: String
)

/**
 * Result of validating and planning a cluster configuration change.
 */
sealed class ValidationPlanResult {
    data class Valid(
        val operations: List<SysfsWriteOperation>,
        val warnings: List<String> = emptyList()
    ) : ValidationPlanResult()

    data class Rejected(
        val errorReason: String
    ) : ValidationPlanResult()
}

/**
 * Enforces all safety invariants required by CoolMax:
 * 1. Rejects frequencies or governors not in the cluster's available lists.
 * 2. Enforces `minFreqKhz <= maxFreqKhz`.
 * 3. Enforces safe floor: `maxFreqKhz` MUST NOT be below 30% of cluster hardware maximum (`cpuinfoMaxFreqKhz`),
 *    and emits an explicit warning if `maxFreqKhz` is below 50% of hardware maximum.
 * 4. Prevents `cpu0` from ever being offlined, and requires at least 1 core in every cluster to remain online.
 * 5. Enforces safe write ordering to prevent kernel EINVAL rejections:
 *    - If new_min > current_max (or when raising frequencies): raise `scaling_max_freq` FIRST, then `scaling_min_freq`.
 *    - If new_max < current_min (or when lowering frequencies): lower `scaling_min_freq` FIRST, then `scaling_max_freq`.
 */
object SafeFrequencyPlanner {

    const val MIN_ALLOWED_MAX_FREQ_RATIO = 0.30
    const val WARNING_MAX_FREQ_RATIO = 0.50

    private val SAFE_GOVERNOR_REGEX = Regex("^[a-zA-Z0-9_-]+$")
    private val SAFE_SYSFS_PATH_REGEX = Regex("^/sys/devices/system/cpu/[a-zA-Z0-9_/.-]+$")

    fun isValidSysfsCpuPath(path: String): Boolean {
        return SAFE_SYSFS_PATH_REGEX.matches(path) && !path.contains("..")
    }

    /**
     * Validates a target [ClusterConfig] against the live [CpuCluster] hardware capabilities
     * and generates an ordered list of [SysfsWriteOperation]s.
     */
    fun validateAndPlan(
        cluster: CpuCluster,
        target: ClusterConfig
    ): ValidationPlanResult {
        val warnings = mutableListOf<String>()

        // 1. Validate policy path
        val basePath = cluster.policyPath.trimEnd('/')
        if (!isValidSysfsCpuPath(basePath)) {
            return ValidationPlanResult.Rejected("Invalid sysfs policy path: $basePath")
        }

        // 2. Validate min <= max
        if (target.minFreqKhz > target.maxFreqKhz) {
            return ValidationPlanResult.Rejected(
                "Minimum frequency (${target.minFreqKhz} kHz) cannot exceed maximum frequency (${target.maxFreqKhz} kHz)."
            )
        }

        // 3. Validate frequencies against available list
        if (cluster.availableFrequenciesKhz.isEmpty()) {
            return ValidationPlanResult.Rejected("Cluster policy${cluster.policyId} has no available frequencies.")
        }
        if (target.minFreqKhz !in cluster.availableFrequenciesKhz) {
            return ValidationPlanResult.Rejected(
                "Minimum frequency ${target.minFreqKhz} kHz is not supported by policy${cluster.policyId}."
            )
        }
        if (target.maxFreqKhz !in cluster.availableFrequenciesKhz) {
            return ValidationPlanResult.Rejected(
                "Maximum frequency ${target.maxFreqKhz} kHz is not supported by policy${cluster.policyId}."
            )
        }

        // 4. Enforce 30% hard floor & 50% warning threshold on maxFreqKhz
        val hardwareMax = cluster.availableFrequenciesKhz.maxOrNull() ?: cluster.cpuinfoMaxFreqKhz
        if (hardwareMax > 0L) {
            val ratio = target.maxFreqKhz.toDouble() / hardwareMax.toDouble()
            // Allow if the lowest available hardware frequency above 30% is chosen, or strictly check 30%
            if (ratio < MIN_ALLOWED_MAX_FREQ_RATIO) {
                return ValidationPlanResult.Rejected(
                    "Unsafe max frequency (${target.maxFreqKhz} kHz is ${(ratio * 100).roundToLong()}% of max $hardwareMax kHz). " +
                        "Safety policy prohibits setting max frequency below 30% of cluster maximum."
                )
            }
            if (ratio < WARNING_MAX_FREQ_RATIO) {
                warnings.add(
                    "Warning: Max frequency for policy${cluster.policyId} (${target.maxFreqKhz} kHz) is below 50% of hardware maximum ($hardwareMax kHz)."
                )
            }
        }

        // 5. Validate governor against available governors
        if (!SAFE_GOVERNOR_REGEX.matches(target.governor)) {
            return ValidationPlanResult.Rejected("Invalid governor name format: ${target.governor}")
        }
        if (cluster.availableGovernors.isNotEmpty() && target.governor !in cluster.availableGovernors) {
            return ValidationPlanResult.Rejected(
                "Governor '${target.governor}' is not available on policy${cluster.policyId}. Available: ${cluster.availableGovernors.joinToString()}"
            )
        }

        // 6. Validate core online/offline rules:
        //    - cpu0 MUST NEVER be offlined
        //    - All offlineCores must belong to cluster.relatedCpus
        //    - At least 1 core in the cluster must remain online
        if (0 in target.offlineCores) {
            return ValidationPlanResult.Rejected("Safety violation: cpu0 cannot be offlined.")
        }
        val clusterCores = cluster.relatedCpus.ifEmpty { cluster.affectedCpus }
        val invalidCores = target.offlineCores.filter { it !in clusterCores }
        if (invalidCores.isNotEmpty()) {
            return ValidationPlanResult.Rejected(
                "Cores $invalidCores do not belong to cluster policy${cluster.policyId} ($clusterCores)."
            )
        }
        val remainingOnlineCount = clusterCores.count { it !in target.offlineCores }
        if (clusterCores.isNotEmpty() && remainingOnlineCount < 1) {
            return ValidationPlanResult.Rejected(
                "Safety violation: At least 1 core must remain online in cluster policy${cluster.policyId}."
            )
        }

        // 7. Determine safe write ordering for governor + scaling_min_freq + scaling_max_freq
        val ops = mutableListOf<SysfsWriteOperation>()
        val minPath = "$basePath/scaling_min_freq"
        val maxPath = "$basePath/scaling_max_freq"
        val govPath = "$basePath/scaling_governor"

        // Safe write sequence (perubahan.md §3.7 - clamp prevention):
        // Step 1: switch governor FIRST (neutralizes `performance` min==max pinning before touching freqs)
        if (target.governor != cluster.activeGovernor) {
            ops.add(
                SysfsWriteOperation(
                    path = govPath,
                    value = target.governor,
                    description = "Set policy${cluster.policyId} governor to ${target.governor} (governor first to prevent min==max clamp)"
                )
            )
        }

        // Step 2: ordered frequency writes
        // - When raising frequencies (or when target.maxFreqKhz >= cluster.maxFreqKhz), raise max FIRST, then set min.
        // - When lowering frequencies (target.maxFreqKhz < cluster.maxFreqKhz), lower min FIRST, then lower max.
        val raiseMaxFirst = target.maxFreqKhz >= cluster.maxFreqKhz
        if (raiseMaxFirst) {
            ops.add(
                SysfsWriteOperation(
                    path = maxPath,
                    value = target.maxFreqKhz.toString(),
                    description = "Set policy${cluster.policyId} max freq to ${target.maxFreqKhz} kHz (raise max first)"
                )
            )
            ops.add(
                SysfsWriteOperation(
                    path = minPath,
                    value = target.minFreqKhz.toString(),
                    description = "Set policy${cluster.policyId} min freq to ${target.minFreqKhz} kHz"
                )
            )
        } else {
            ops.add(
                SysfsWriteOperation(
                    path = minPath,
                    value = target.minFreqKhz.toString(),
                    description = "Set policy${cluster.policyId} min freq to ${target.minFreqKhz} kHz (lower min first)"
                )
            )
            ops.add(
                SysfsWriteOperation(
                    path = maxPath,
                    value = target.maxFreqKhz.toString(),
                    description = "Set policy${cluster.policyId} max freq to ${target.maxFreqKhz} kHz"
                )
            )
        }

        // Ensure the target governor is still applied afterwards (idempotent when already written in step 1)
        if (target.governor == cluster.activeGovernor) {
            ops.add(
                SysfsWriteOperation(
                    path = govPath,
                    value = target.governor,
                    description = "Set policy${cluster.policyId} governor to ${target.governor}"
                )
            )
        }

        // Core online/offline states (excluding cpu0 which is always kept online and untouched)
        for (cpuId in clusterCores.sorted()) {
            if (cpuId == 0) continue
            val shouldBeOnline = cpuId !in target.offlineCores
            val onlineValue = if (shouldBeOnline) "1" else "0"
            ops.add(
                SysfsWriteOperation(
                    path = "/sys/devices/system/cpu/cpu$cpuId/online",
                    value = onlineValue,
                    description = "Set cpu$cpuId online=$onlineValue"
                )
            )
        }

        return ValidationPlanResult.Valid(operations = ops, warnings = warnings)
    }

    /**
     * Generates a safe preset [ClusterConfig] for a given [CpuCluster] and [PresetType].
     * Guarantees the chosen max frequency is >= 35% of hardware max (above the 30% safety floor).
     */
    fun buildPresetConfigForCluster(cluster: CpuCluster, preset: PresetType): ClusterConfig {
        val freqs = cluster.availableFrequenciesKhz.sorted()
        val minAvail = freqs.firstOrNull() ?: cluster.cpuinfoMinFreqKhz
        val maxAvail = freqs.lastOrNull() ?: cluster.cpuinfoMaxFreqKhz

        val chosenMax = when (preset) {
            PresetType.BATTERY_SAVER -> {
                // Pick the lowest available frequency that is at least 50% of hardware max
                // (or >= 35% if no step exists between 35% and 50%)
                val target50 = (maxAvail * 0.50).roundToLong()
                val target35 = (maxAvail * 0.35).roundToLong()
                freqs.firstOrNull { it >= target50 }
                    ?: freqs.firstOrNull { it >= target35 }
                    ?: maxAvail
            }
            PresetType.BALANCED -> {
                val target70 = (maxAvail * 0.72).roundToLong()
                freqs.lastOrNull { it <= target70 && it >= (maxAvail * 0.50).roundToLong() }
                    ?: freqs.firstOrNull { it >= (maxAvail * 0.50).roundToLong() }
                    ?: maxAvail
            }
            PresetType.CUSTOM -> {
                cluster.maxFreqKhz.takeIf { it in freqs } ?: maxAvail
            }
        }

        val chosenGovernor = when (preset) {
            PresetType.BATTERY_SAVER -> {
                cluster.availableGovernors.firstOrNull { it == "powersave" }
                    ?: cluster.availableGovernors.firstOrNull { it == "schedutil" }
                    ?: cluster.availableGovernors.firstOrNull { it == "conservative" }
                    ?: cluster.activeGovernor
            }
            PresetType.BALANCED, PresetType.CUSTOM -> {
                cluster.availableGovernors.firstOrNull { it == "schedutil" }
                    ?: cluster.availableGovernors.firstOrNull { it == "interactive" }
                    ?: cluster.activeGovernor
            }
        }

        // For BATTERY_SAVER on multi-core non-little clusters, optionally offline the upper half of cores
        // while keeping cpu0 and at least 2 cores online.
        val offlineCores = if (preset == PresetType.BATTERY_SAVER && cluster.relatedCpus.size >= 4) {
            val nonZero = cluster.relatedCpus.filter { it != 0 }.sorted()
            // Keep at least half online
            nonZero.takeLast(nonZero.size / 2)
        } else {
            emptyList()
        }

        return ClusterConfig(
            policyId = cluster.policyId,
            minFreqKhz = minAvail,
            maxFreqKhz = chosenMax.coerceAtLeast(minAvail),
            governor = chosenGovernor,
            offlineCores = offlineCores
        )
    }
}
