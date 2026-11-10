package id.coolmax.app.domain.sysfs

import id.coolmax.app.domain.model.ClusterTier
import id.coolmax.app.domain.model.CpuCluster
import id.coolmax.app.domain.model.ThermalSensorReading
import id.coolmax.app.domain.model.TimeInStateEntry

/**
 * Pure Kotlin, side-effect-free parser for Linux kernel cpufreq and thermal sysfs outputs.
 * Designed so all parsing logic can be deterministically unit-tested with fake sysfs trees.
 */
object SysfsParser {

    /**
     * Parses whitespace-separated frequencies in kHz from `scaling_available_frequencies`
     * or `scaling_boost_frequencies`, returning a sorted ascending list of unique positive values.
     */
    fun parseFrequencies(rawText: String?): List<Long> {
        if (rawText.isNullOrBlank()) return emptyList()
        return rawText
            .trim()
            .split(Regex("\\s+"))
            .mapNotNull { token -> token.trim().toLongOrNull() }
            .filter { it > 0L }
            .distinct()
            .sorted()
    }

    /**
     * Parses `stats/time_in_state` content where each line is `<freq_khz> <time_in_10ms_jiffies>`.
     * Also serves as a fallback source of available frequencies when `scaling_available_frequencies` is missing.
     */
    fun parseTimeInState(rawText: String?): List<TimeInStateEntry> {
        if (rawText.isNullOrBlank()) return emptyList()
        val entries = mutableListOf<TimeInStateEntry>()
        for (line in rawText.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            val parts = trimmed.split(Regex("\\s+"))
            if (parts.size >= 2) {
                val freq = parts[0].toLongOrNull()
                val jiffies = parts[1].toLongOrNull()
                if (freq != null && freq > 0L && jiffies != null && jiffies >= 0L) {
                    // Kernel time_in_state unit is user_hz ticks (10ms per tick on standard ARM/Android kernels)
                    entries.add(TimeInStateEntry(frequencyKhz = freq, timeMs = jiffies * 10L))
                }
            }
        }
        return entries.sortedBy { it.frequencyKhz }
    }

    /**
     * Extracts sorted available frequencies with automatic fallback:
     * 1. `scaling_available_frequencies` (+ optional `scaling_boost_frequencies`)
     * 2. `stats/time_in_state`
     * 3. Synthetic step list between `cpuinfo_min_freq` and `cpuinfo_max_freq`
     */
    fun resolveAvailableFrequencies(
        scalingAvailableFreqsRaw: String?,
        boostFreqsRaw: String? = null,
        timeInStateRaw: String? = null,
        cpuinfoMinKhz: Long? = null,
        cpuinfoMaxKhz: Long? = null
    ): List<Long> {
        val primary = parseFrequencies(scalingAvailableFreqsRaw)
        val boost = parseFrequencies(boostFreqsRaw)
        val combined = (primary + boost).distinct().sorted()
        if (combined.isNotEmpty()) return combined

        val fromTimeInState = parseTimeInState(timeInStateRaw).map { it.frequencyKhz }.distinct().sorted()
        if (fromTimeInState.isNotEmpty()) return fromTimeInState

        if (cpuinfoMinKhz != null && cpuinfoMaxKhz != null && cpuinfoMinKhz > 0 && cpuinfoMaxKhz >= cpuinfoMinKhz) {
            return if (cpuinfoMinKhz == cpuinfoMaxKhz) {
                listOf(cpuinfoMinKhz)
            } else {
                listOf(cpuinfoMinKhz, cpuinfoMaxKhz)
            }
        }
        return emptyList()
    }

    /**
     * Parses whitespace-separated governor names from `scaling_available_governors`.
     */
    fun parseGovernors(rawText: String?): List<String> {
        if (rawText.isNullOrBlank()) return emptyList()
        val validGovRegex = Regex("^[a-zA-Z0-9_-]+$")
        return rawText
            .trim()
            .split(Regex("\\s+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() && validGovRegex.matches(it) }
            .distinct()
    }

    /**
     * Parses CPU core lists in either space-separated format ("0 1 2 3") or range format ("0-3,6-7" or "0-3").
     */
    fun parseCpuList(rawText: String?): List<Int> {
        if (rawText.isNullOrBlank()) return emptyList()
        val result = mutableSetOf<Int>()
        val tokens = rawText.trim().split(Regex("[\\s,]+"))
        for (token in tokens) {
            if (token.isEmpty()) continue
            if (token.contains("-")) {
                val bounds = token.split("-", limit = 2)
                val start = bounds.getOrNull(0)?.toIntOrNull()
                val end = bounds.getOrNull(1)?.toIntOrNull()
                if (start != null && end != null && start >= 0 && end >= start && (end - start) <= 64) {
                    for (cpu in start..end) {
                        result.add(cpu)
                    }
                }
            } else {
                val single = token.toIntOrNull()
                if (single != null && single >= 0) {
                    result.add(single)
                }
            }
        }
        return result.sorted()
    }

    /**
     * Assigns ClusterTier (LITTLE, BIG, PRIME, or SINGLE) dynamically based on hardware max frequencies
     * across all discovered clusters, without hardcoding policy numbers.
     */
    fun classifyClusterTiers(clustersMaxFreqByPolicyId: Map<Int, Long>): Map<Int, ClusterTier> {
        if (clustersMaxFreqByPolicyId.isEmpty()) return emptyMap()
        if (clustersMaxFreqByPolicyId.size == 1) {
            val singlePolicyId = clustersMaxFreqByPolicyId.keys.first()
            return mapOf(singlePolicyId to ClusterTier.SINGLE)
        }
        val sortedPolicies = clustersMaxFreqByPolicyId.entries
            .sortedWith(compareBy<Map.Entry<Int, Long>> { it.value }.thenBy { it.key })

        val result = mutableMapOf<Int, ClusterTier>()
        when (sortedPolicies.size) {
            2 -> {
                result[sortedPolicies[0].key] = ClusterTier.LITTLE
                result[sortedPolicies[1].key] = ClusterTier.BIG
            }
            else -> {
                result[sortedPolicies.first().key] = ClusterTier.LITTLE
                for (i in 1 until sortedPolicies.size - 1) {
                    result[sortedPolicies[i].key] = ClusterTier.BIG
                }
                result[sortedPolicies.last().key] = ClusterTier.PRIME
            }
        }
        return result
    }

    /**
     * Groups individual CPU nodes (when `/sys/devices/system/cpu/cpufreq/policy*` is absent)
     * into logical clusters by `related_cpus` or `affected_cpus` or identical `cpuinfo_max_freq`.
     */
    data class RawCpuNodeData(
        val cpuId: Int,
        val cpufreqPath: String,
        val relatedCpusRaw: String?,
        val affectedCpusRaw: String?,
        val curFreqRaw: String?,
        val minFreqRaw: String?,
        val maxFreqRaw: String?,
        val cpuinfoMinRaw: String?,
        val cpuinfoMaxRaw: String?,
        val governorRaw: String?,
        val availableFreqsRaw: String?,
        val availableGovsRaw: String?,
        val timeInStateRaw: String?
    )

    fun buildClustersFromFallbackCpuNodes(
        nodes: List<RawCpuNodeData>,
        onlineCpus: List<Int>
    ): List<CpuCluster> {
        if (nodes.isEmpty()) return emptyList()
        // Group by related_cpus list if non-empty, otherwise by cpuinfo_max_freq
        val grouped = LinkedHashMap<String, MutableList<RawCpuNodeData>>()
        for (node in nodes.sortedBy { it.cpuId }) {
            val related = parseCpuList(node.relatedCpusRaw).ifEmpty {
                parseCpuList(node.affectedCpusRaw)
            }
            val key = if (related.isNotEmpty()) {
                "cpus:${related.joinToString(",")}"
            } else {
                "max:${node.cpuinfoMaxRaw?.trim() ?: node.maxFreqRaw?.trim() ?: node.cpuId.toString()}"
            }
            grouped.getOrPut(key) { mutableListOf() }.add(node)
        }

        val maxFreqMap = mutableMapOf<Int, Long>()
        val draftClusters = mutableListOf<CpuCluster>()

        for ((_, groupNodes) in grouped) {
            val representative = groupNodes.first()
            val policyId = representative.cpuId
            val relatedCpus = parseCpuList(representative.relatedCpusRaw)
                .ifEmpty { groupNodes.map { it.cpuId }.sorted() }
            val affectedCpus = parseCpuList(representative.affectedCpusRaw)
                .ifEmpty { relatedCpus }
            val clusterOnlineCpus = relatedCpus.filter { it in onlineCpus }
            val timeInState = parseTimeInState(representative.timeInStateRaw)
            val cpuinfoMin = representative.cpuinfoMinRaw?.trim()?.toLongOrNull() ?: 0L
            val cpuinfoMax = representative.cpuinfoMaxRaw?.trim()?.toLongOrNull() ?: 0L
            val availFreqs = resolveAvailableFrequencies(
                scalingAvailableFreqsRaw = representative.availableFreqsRaw,
                timeInStateRaw = representative.timeInStateRaw,
                cpuinfoMinKhz = cpuinfoMin,
                cpuinfoMaxKhz = cpuinfoMax
            )
            val resolvedMax = cpuinfoMax.takeIf { it > 0L } ?: availFreqs.maxOrNull() ?: 0L
            val resolvedMin = cpuinfoMin.takeIf { it > 0L } ?: availFreqs.minOrNull() ?: 0L
            val scalingMin = representative.minFreqRaw?.trim()?.toLongOrNull() ?: resolvedMin
            val scalingMax = representative.maxFreqRaw?.trim()?.toLongOrNull() ?: resolvedMax
            val curFreq = representative.curFreqRaw?.trim()?.toLongOrNull() ?: scalingMin
            val availGovs = parseGovernors(representative.availableGovsRaw)
            val curGov = representative.governorRaw?.trim().orEmpty().ifEmpty {
                availGovs.firstOrNull() ?: "schedutil"
            }

            maxFreqMap[policyId] = resolvedMax
            draftClusters.add(
                CpuCluster(
                    policyId = policyId,
                    policyPath = representative.cpufreqPath,
                    tier = ClusterTier.SINGLE,
                    affectedCpus = affectedCpus,
                    relatedCpus = relatedCpus,
                    onlineCpus = clusterOnlineCpus,
                    currentFreqKhz = curFreq,
                    minFreqKhz = scalingMin,
                    maxFreqKhz = scalingMax,
                    cpuinfoMinFreqKhz = resolvedMin,
                    cpuinfoMaxFreqKhz = resolvedMax,
                    activeGovernor = curGov,
                    availableFrequenciesKhz = availFreqs,
                    availableGovernors = availGovs,
                    timeInState = timeInState
                )
            )
        }

        val tiers = classifyClusterTiers(maxFreqMap)
        return draftClusters.map { cluster ->
            cluster.copy(tier = tiers[cluster.policyId] ?: ClusterTier.SINGLE)
        }.sortedBy { it.policyId }
    }

    /**
     * Parses thermal zone temperature from raw sysfs integer/float string.
     * Kernel thermal_zone temp is typically in millidegrees Celsius (e.g. "42500" -> 42.5°C)
     * or degrees Celsius (e.g. "42" -> 42.0°C). Rejects implausible sensor values outside [-20°C, 150°C].
     */
    fun parseThermalTemperatureCelsius(rawTemp: String?): Float? {
        if (rawTemp.isNullOrBlank()) return null
        val numeric = rawTemp.trim().toDoubleOrNull() ?: return null
        val celsius = when {
            numeric >= 1000.0 -> (numeric / 1000.0).toFloat()
            numeric <= -1000.0 -> (numeric / 1000.0).toFloat()
            else -> numeric.toFloat()
        }
        return if (celsius in -20.0f..150.0f) celsius else null
    }

    /**
     * Filters and ranks thermal sensors to identify CPU/SoC relevant thermal zones.
     */
    fun filterCpuThermalReadings(readings: List<ThermalSensorReading>): List<ThermalSensorReading> {
        val cpuKeywords = listOf("cpu", "soc", "tsens", "cluster", "big", "little", "prime", "ap", "therm", "skin", "xo")
        val matched = readings.filter { reading ->
            val lowerType = reading.type.lowercase()
            cpuKeywords.any { lowerType.contains(it) } && reading.tempCelsius in 5.0f..125.0f
        }
        return if (matched.isNotEmpty()) matched else readings.filter { it.tempCelsius in 5.0f..125.0f }
    }
}
