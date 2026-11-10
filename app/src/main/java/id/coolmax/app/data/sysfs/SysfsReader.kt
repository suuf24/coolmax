package id.coolmax.app.data.sysfs

import id.coolmax.app.data.root.RootManager
import id.coolmax.app.domain.model.ClusterTier
import id.coolmax.app.domain.model.CpuCluster
import id.coolmax.app.domain.model.ThermalSensorReading
import id.coolmax.app.domain.sysfs.SysfsParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Dynamically discovers and reads CPU clusters, frequencies, governors, core states,
 * time-in-state statistics, and thermal zones from `/sys`.
 *
 * Uses normal file reads where world-readable, and falls back to `libsu` root shell reads
 * when SELinux or file permissions (0400/0600) restrict access.
 */
class SysfsReader(
    private val rootManager: RootManager
) {
    companion object {
        const val CPUFREQ_POLICY_DIR = "/sys/devices/system/cpu/cpufreq"
        const val CPU_BASE_DIR = "/sys/devices/system/cpu"
        const val THERMAL_BASE_DIR = "/sys/class/thermal"

        private val POLICY_NAME_REGEX = Regex("^policy(\\d+)$")
        private val CPU_NAME_REGEX = Regex("^cpu(\\d+)$")
        private val THERMAL_ZONE_REGEX = Regex("^thermal_zone(\\d+)$")
    }

    /**
     * Reads a sysfs file safely: tries direct JVM file read first (fast, zero shell overhead),
     * then falls back to `cat <path>` via `libsu` if unreadable.
     */
    suspend fun readSysfsNode(path: String): String? = withContext(Dispatchers.IO) {
        try {
            val file = File(path)
            if (file.exists() && file.canRead()) {
                val text = file.readText().trim()
                if (text.isNotEmpty()) return@withContext text
            }
        } catch (_: Throwable) {
            // Fallback to root shell below
        }

        // Validate path before passing to root shell
        if (!path.startsWith("/sys/") || path.contains("..") || path.contains(";") || path.contains(" ")) {
            return@withContext null
        }
        val res = rootManager.execReadCommand("cat '$path' 2>/dev/null")
        if (res.isSuccess && res.stdout.isNotBlank()) {
            res.stdout.trim()
        } else {
            null
        }
    }

    /**
     * Lists directory entries under a `/sys` directory using File API with root `ls -1` fallback.
     */
    suspend fun listSysfsEntries(dirPath: String): List<String> = withContext(Dispatchers.IO) {
        try {
            val dir = File(dirPath)
            val children = dir.list()
            if (!children.isNullOrEmpty()) {
                return@withContext children.toList()
            }
        } catch (_: Throwable) {
            // Fallback to root shell
        }
        if (!dirPath.startsWith("/sys/") || dirPath.contains("..")) return@withContext emptyList()
        val res = rootManager.execReadCommand("ls -1 '$dirPath' 2>/dev/null")
        if (res.isSuccess && res.stdout.isNotBlank()) {
            res.stdout.lines().map { it.trim() }.filter { it.isNotEmpty() }
        } else {
            emptyList()
        }
    }

    /**
     * Reads the global list of online CPU cores from `/sys/devices/system/cpu/online`.
     */
    suspend fun readOnlineCpus(): List<Int> {
        val raw = readSysfsNode("$CPU_BASE_DIR/online")
        val parsed = SysfsParser.parseCpuList(raw)
        if (parsed.isNotEmpty()) return parsed

        // Fallback: inspect individual cpuX/online files
        val entries = listSysfsEntries(CPU_BASE_DIR)
        val online = mutableListOf<Int>()
        for (entry in entries) {
            val match = CPU_NAME_REGEX.matchEntire(entry) ?: continue
            val cpuId = match.groupValues[1].toIntOrNull() ?: continue
            if (cpuId == 0) {
                online.add(0)
            } else {
                val state = readSysfsNode("$CPU_BASE_DIR/$entry/online")
                if (state == "1") online.add(cpuId)
            }
        }
        return online.sorted()
    }

    /**
     * Reads all CPU clusters dynamically:
     * 1. Primary path: `/sys/devices/system/cpu/cpufreq/policy[0-9]+/`
     * 2. Fallback path: `/sys/devices/system/cpu/cpu<N>/cpufreq/`
     */
    suspend fun readClusters(): List<CpuCluster> = withContext(Dispatchers.IO) {
        val onlineCpus = readOnlineCpus()
        val policyEntries = listSysfsEntries(CPUFREQ_POLICY_DIR)
            .mapNotNull { name ->
                val match = POLICY_NAME_REGEX.matchEntire(name)
                match?.groupValues?.get(1)?.toIntOrNull()?.let { id -> id to "$CPUFREQ_POLICY_DIR/$name" }
            }
            .sortedBy { it.first }

        if (policyEntries.isNotEmpty()) {
            val rawClusters = mutableListOf<CpuCluster>()
            val maxFreqMap = mutableMapOf<Int, Long>()

            for ((policyId, policyPath) in policyEntries) {
                val relatedCpus = SysfsParser.parseCpuList(readSysfsNode("$policyPath/related_cpus"))
                val affectedCpus = SysfsParser.parseCpuList(readSysfsNode("$policyPath/affected_cpus"))
                    .ifEmpty { relatedCpus }
                val allClusterCpus = relatedCpus.ifEmpty { affectedCpus }.ifEmpty { listOf(policyId) }
                val clusterOnlineCpus = allClusterCpus.filter { it in onlineCpus }

                val cpuinfoMin = readSysfsNode("$policyPath/cpuinfo_min_freq")?.toLongOrNull() ?: 0L
                val cpuinfoMax = readSysfsNode("$policyPath/cpuinfo_max_freq")?.toLongOrNull() ?: 0L
                val availFreqsRaw = readSysfsNode("$policyPath/scaling_available_frequencies")
                val boostFreqsRaw = readSysfsNode("$policyPath/scaling_boost_frequencies")
                val timeInStateRaw = readSysfsNode("$policyPath/stats/time_in_state")

                val availFreqs = SysfsParser.resolveAvailableFrequencies(
                    scalingAvailableFreqsRaw = availFreqsRaw,
                    boostFreqsRaw = boostFreqsRaw,
                    timeInStateRaw = timeInStateRaw,
                    cpuinfoMinKhz = cpuinfoMin,
                    cpuinfoMaxKhz = cpuinfoMax
                )

                val resolvedMax = cpuinfoMax.takeIf { it > 0L } ?: availFreqs.maxOrNull() ?: 0L
                val resolvedMin = cpuinfoMin.takeIf { it > 0L } ?: availFreqs.minOrNull() ?: 0L
                val scalingMin = readSysfsNode("$policyPath/scaling_min_freq")?.toLongOrNull() ?: resolvedMin
                val scalingMax = readSysfsNode("$policyPath/scaling_max_freq")?.toLongOrNull() ?: resolvedMax
                val curFreq = readSysfsNode("$policyPath/scaling_cur_freq")?.toLongOrNull()
                    ?: readSysfsNode("$policyPath/cpuinfo_cur_freq")?.toLongOrNull()
                    ?: scalingMin

                val availGovs = SysfsParser.parseGovernors(
                    readSysfsNode("$policyPath/scaling_available_governors")
                )
                val activeGov = readSysfsNode("$policyPath/scaling_governor")
                    .orEmpty()
                    .ifEmpty { availGovs.firstOrNull() ?: "schedutil" }

                val timeInState = SysfsParser.parseTimeInState(timeInStateRaw)
                maxFreqMap[policyId] = resolvedMax

                rawClusters.add(
                    CpuCluster(
                        policyId = policyId,
                        policyPath = policyPath,
                        tier = ClusterTier.SINGLE,
                        affectedCpus = affectedCpus,
                        relatedCpus = allClusterCpus,
                        onlineCpus = clusterOnlineCpus,
                        currentFreqKhz = curFreq,
                        minFreqKhz = scalingMin,
                        maxFreqKhz = scalingMax,
                        cpuinfoMinFreqKhz = resolvedMin,
                        cpuinfoMaxFreqKhz = resolvedMax,
                        activeGovernor = activeGov,
                        availableFrequenciesKhz = availFreqs,
                        availableGovernors = availGovs,
                        timeInState = timeInState
                    )
                )
            }

            val tiers = SysfsParser.classifyClusterTiers(maxFreqMap)
            return@withContext rawClusters.map { cluster ->
                cluster.copy(tier = tiers[cluster.policyId] ?: ClusterTier.SINGLE)
            }
        }

        // Fallback to /sys/devices/system/cpu/cpu*/cpufreq/
        val cpuEntries = listSysfsEntries(CPU_BASE_DIR)
            .mapNotNull { name ->
                CPU_NAME_REGEX.matchEntire(name)?.groupValues?.get(1)?.toIntOrNull()?.let { id ->
                    id to "$CPU_BASE_DIR/$name/cpufreq"
                }
            }
            .sortedBy { it.first }

        val fallbackNodes = mutableListOf<SysfsParser.RawCpuNodeData>()
        for ((cpuId, cpufreqPath) in cpuEntries) {
            val curFreqRaw = readSysfsNode("$cpufreqPath/scaling_cur_freq")
                ?: readSysfsNode("$cpufreqPath/cpuinfo_cur_freq")
            val minFreqRaw = readSysfsNode("$cpufreqPath/scaling_min_freq")
            val maxFreqRaw = readSysfsNode("$cpufreqPath/scaling_max_freq")
            if (curFreqRaw == null && minFreqRaw == null && maxFreqRaw == null) continue

            fallbackNodes.add(
                SysfsParser.RawCpuNodeData(
                    cpuId = cpuId,
                    cpufreqPath = cpufreqPath,
                    relatedCpusRaw = readSysfsNode("$cpufreqPath/related_cpus"),
                    affectedCpusRaw = readSysfsNode("$cpufreqPath/affected_cpus"),
                    curFreqRaw = curFreqRaw,
                    minFreqRaw = minFreqRaw,
                    maxFreqRaw = maxFreqRaw,
                    cpuinfoMinRaw = readSysfsNode("$cpufreqPath/cpuinfo_min_freq"),
                    cpuinfoMaxRaw = readSysfsNode("$cpufreqPath/cpuinfo_max_freq"),
                    governorRaw = readSysfsNode("$cpufreqPath/scaling_governor"),
                    availableFreqsRaw = readSysfsNode("$cpufreqPath/scaling_available_frequencies"),
                    availableGovsRaw = readSysfsNode("$cpufreqPath/scaling_available_governors"),
                    timeInStateRaw = readSysfsNode("$cpufreqPath/stats/time_in_state")
                )
            )
        }

        SysfsParser.buildClustersFromFallbackCpuNodes(fallbackNodes, onlineCpus)
    }

    /**
     * Reads CPU/SoC thermal sensors from `/sys/class/thermal/thermal_zone*`.
     *
     * Zones are read directly first (world-readable zones are cheap). Only zones that are blocked by
     * SELinux/permissions are fetched through ONE root shell command instead of one `su` invocation per
     * file. On MT6785 many zones are 0400 root-only, which previously produced dozens of `avc denied`
     * lines and slow thermal polling every 10 seconds.
     */
    suspend fun readThermalSensors(): List<ThermalSensorReading> = withContext(Dispatchers.IO) {
        val zones = listSysfsEntries(THERMAL_BASE_DIR)
            .filter { THERMAL_ZONE_REGEX.matches(it) }
            .sorted()
        if (zones.isEmpty()) return@withContext emptyList()

        val readings = mutableListOf<ThermalSensorReading>()
        val unresolvedZones = mutableListOf<String>()

        for (zone in zones) {
            val zonePath = "$THERMAL_BASE_DIR/$zone"
            val type = readFileDirect("$zonePath/type")
            val tempRaw = readFileDirect("$zonePath/temp")
            val tempCelsius = SysfsParser.parseThermalTemperatureCelsius(tempRaw)
            if (tempCelsius != null) {
                readings.add(ThermalSensorReading(zoneName = zone, type = type ?: zone, tempCelsius = tempCelsius))
            } else {
                unresolvedZones.add(zone)
            }
        }

        if (unresolvedZones.isNotEmpty()) {
            for ((zone, data) in readThermalZonesViaRoot(unresolvedZones)) {
                readings.add(ThermalSensorReading(zoneName = zone, type = data.first, tempCelsius = data.second))
            }
        }

        SysfsParser.filterCpuThermalReadings(readings)
    }

    /** Direct file read without a root-shell fallback (thermal bulk path). */
    private fun readFileDirect(path: String): String? = try {
        val file = File(path)
        if (file.exists() && file.canRead()) file.readText().trim().ifEmpty { null } else null
    } catch (_: Throwable) {
        null
    }

    /**
     * Reads many thermal zones with a single root shell invocation, parsing `@@zone` markers so each
     * zone's `type` and `temp` lines stay paired.
     */
    private suspend fun readThermalZonesViaRoot(zones: List<String>): Map<String, Pair<String, Float>> {
        val cmd = zones.joinToString("; ") { zone ->
            "echo '@@$zone'; cat '$THERMAL_BASE_DIR/$zone/type' 2>/dev/null; cat '$THERMAL_BASE_DIR/$zone/temp' 2>/dev/null"
        }
        val res = rootManager.execReadCommand(cmd, timeoutMs = 5_000L)
        if (!res.isSuccess || res.stdout.isBlank()) return emptyMap()

        val parsed = mutableMapOf<String, Pair<String, Float>>()
        var currentZone: String? = null
        var currentType: String? = null
        for (rawLine in res.stdout.lines()) {
            val line = rawLine.trim()
            when {
                line.startsWith("@@") -> {
                    currentZone = line.removePrefix("@@")
                    currentType = null
                }
                line.isEmpty() || currentZone == null -> Unit
                currentType == null -> currentType = line
                else -> {
                    val celsius = SysfsParser.parseThermalTemperatureCelsius(line)
                    if (celsius != null) {
                        val zone = currentZone ?: continue
                        parsed[zone] = (currentType?.takeIf { it.isNotBlank() } ?: zone) to celsius
                    }
                    currentType = null
                }
            }
        }
        return parsed
    }
}
