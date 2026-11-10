package id.coolmax.app.data.repository

import id.coolmax.app.data.preferences.CoolMaxPreferences
import id.coolmax.app.data.root.RootManager
import id.coolmax.app.data.sysfs.SysfsReader
import id.coolmax.app.data.sysfs.SysfsWriter
import id.coolmax.app.domain.model.ClusterConfig
import id.coolmax.app.domain.model.ClusterTier
import id.coolmax.app.domain.model.CpuCluster
import id.coolmax.app.domain.model.CpuProfile
import id.coolmax.app.domain.model.DeviceInfo
import id.coolmax.app.domain.model.EventLogEntry
import id.coolmax.app.domain.model.KernelSnapshot
import id.coolmax.app.domain.model.PresetType
import id.coolmax.app.domain.model.ThermalSensorReading
import id.coolmax.app.domain.model.TriggerType
import id.coolmax.app.domain.safety.SafeFrequencyPlanner
import id.coolmax.app.domain.safety.SysfsWriteOperation
import id.coolmax.app.domain.safety.ValidationPlanResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.UUID

/**
 * Central Repository coordinating root detection, sysfs reads/writes, snapshot capture/rollback,
 * bootloop protection (`pending_apply` + 2-minute stabilization timer), and thermal protection.
 */
class CpuRepository(
    val rootManager: RootManager,
    val sysfsReader: SysfsReader,
    val sysfsWriter: SysfsWriter,
    val preferences: CoolMaxPreferences
) {
    private val repoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var stabilizationJob: Job? = null

    // Rate-limit / dedupe state for HIGH_TEMPERATURE events (perubahan.md §3.2: 94 repeated events)
    @Volatile
    private var lastThermalBlockLogMs: Long = 0L

    @Volatile
    private var lastThermalBlockKey: String = ""

    companion object {
        const val STABILIZATION_WINDOW_MS = 120_000L // 2 minutes stabilization before clearing pending_apply
        const val THERMAL_BLOCK_LOG_INTERVAL_MS = 120_000L // Log at most one block event per 2 min per temp band
        const val THERMAL_TEMP_BAND_C = 5.0f // Temp bucket for dedup keys

        /**
         * Triggers exempt from the thermal guard: they are cooling/restoring by definition and must
         * NEVER be blocked by the guard (fixes §3.2 bug where the cooling fallback was blocked by
         * the very same guard that triggered it).
         */
        private val THERMAL_EXEMPT_TRIGGERS = setOf(
            TriggerType.SAFE_MODE,
            TriggerType.SYSTEM_DEFAULT,
            TriggerType.HIGH_TEMPERATURE,
            TriggerType.SCREEN_ON,
            TriggerType.SCREEN_OFF
        )
    }

    data class ApplyOutcome(
        val isSuccess: Boolean,
        val overriddenByThermalOrHal: Boolean,
        val warnings: List<String>,
        val errorMessage: String? = null,
        val executedCommands: List<String> = emptyList()
    )

    /** Result of a governor-only write (Feature: governor dropdown & per-app governor). */
    data class GovernorApplyOutcome(
        val isSuccess: Boolean,
        val message: String,
        val verifiedByPolicy: Map<Int, String> = emptyMap(),
        val skippedPolicies: List<Int> = emptyList()
    )

    /**
     * Reads live clusters from sysfs, or returns realistic simulated clusters when running in Dry-Run mode
     * on an unrooted device so developers/testers can still inspect and test the UI.
     */
    suspend fun getClusters(allowSimulatedFallbackInDryRun: Boolean = true): List<CpuCluster> {
        val realClusters = sysfsReader.readClusters()
        if (realClusters.isNotEmpty()) {
            // Capture initial snapshot on first run
            val snapshot = captureSnapshotFromClusters(realClusters, "Initial System Default Snapshot")
            preferences.saveInitialSnapshotIfNeeded(snapshot)
            return realClusters
        }

        val isDryRun = preferences.dryRunModeFlow.first()
        if (allowSimulatedFallbackInDryRun && isDryRun) {
            val simulated = buildSimulatedClusters()
            val snapshot = captureSnapshotFromClusters(simulated, "Simulated Default Snapshot")
            preferences.saveInitialSnapshotIfNeeded(snapshot)
            return simulated
        }
        return emptyList()
    }

    suspend fun getThermalReadings(): List<ThermalSensorReading> {
        return sysfsReader.readThermalSensors()
    }

    suspend fun getMaxCpuTemperatureCelsius(): Float? {
        return getThermalReadings().maxOfOrNull { it.tempCelsius }
    }

    suspend fun getDeviceInfo(): DeviceInfo {
        val clusters = getClusters()
        val totalCores = clusters.flatMap { it.relatedCpus }.distinct().size.takeIf { it > 0 }
            ?: Runtime.getRuntime().availableProcessors()
        val govs = clusters.flatMap { it.availableGovernors }.distinct().sorted()
        return rootManager.readDeviceInfo(
            clusterCount = clusters.size,
            totalCores = totalCores,
            availableGovernors = govs
        )
    }

    fun captureSnapshotFromClusters(clusters: List<CpuCluster>, label: String): KernelSnapshot {
        val map = clusters.associate { cluster ->
            val offline = cluster.relatedCpus.filter { it !in cluster.onlineCpus && it != 0 }
            cluster.policyId to ClusterConfig(
                policyId = cluster.policyId,
                minFreqKhz = cluster.minFreqKhz,
                maxFreqKhz = cluster.maxFreqKhz,
                governor = cluster.activeGovernor,
                offlineCores = offline
            )
        }
        return KernelSnapshot(
            capturedAtEpochMs = System.currentTimeMillis(),
            label = label,
            clusterConfigs = map
        )
    }

    /**
     * Validates and applies a map of [ClusterConfig]s across clusters:
     * 1. Checks thermal threshold first (refuses performance boost if temp > limit).
     * 2. Saves current live values to `previous_snapshot` for Undo support.
     * 3. Sets `pending_apply = true` before touching sysfs (bootloop protection).
     * 4. Executes ordered sysfs writes via [SysfsWriter] with 3s timeout, 1x retry, and read-back verification.
     * 5. Starts 2-minute stabilization timer to clear `pending_apply`.
     * 6. Records structured [EventLogEntry].
     */
    suspend fun applyClusterConfigs(
        configs: Map<Int, ClusterConfig>,
        trigger: TriggerType,
        profileName: String,
        saveAsScreenOnBaseline: Boolean = false,
        /** Automatic thermal steps keep their own baseline, so they must not overwrite Undo. */
        saveUndoSnapshot: Boolean = true,
        /** Thermal steps are continuously re-asserted; the bootloop guard is unnecessary churn there. */
        useBootloopGuard: Boolean = true
    ): ApplyOutcome {
        val dryRun = preferences.dryRunModeFlow.first()
        val clusters = getClusters(allowSimulatedFallbackInDryRun = true)
        if (clusters.isEmpty()) {
            return ApplyOutcome(
                isSuccess = false,
                overriddenByThermalOrHal = false,
                warnings = emptyList(),
                errorMessage = "No CPU clusters detected on this device."
            )
        }

        // Check thermal protection: only block profiles that would RAISE the ceiling. Cooling profiles
        // (target max <= current max) must always be allowed, otherwise the guard blocks the very
        // fallback meant to cool the device (perubahan.md §3.2).
        val triggerSettings = preferences.triggerSettingsFlow.first()
        val maxTemp = getMaxCpuTemperatureCelsius()
        val clusterById = clusters.associateBy { it.policyId }
        val isCoolingProfile = configs.all { (policyId, target) ->
            val live = clusterById[policyId] ?: return@all true
            target.maxFreqKhz <= live.maxFreqKhz
        }
        if (maxTemp != null && maxTemp > triggerSettings.thermalLimitCelsius &&
            trigger !in THERMAL_EXEMPT_TRIGGERS && !isCoolingProfile
        ) {
            val msg = "Blocked profile '$profileName' because CPU temperature (${String.format(Locale.US, "%.1f", maxTemp)}°C) " +
                "exceeds thermal safety limit (${String.format(Locale.US, "%.1f", triggerSettings.thermalLimitCelsius)}°C)."
            logThermalBlockOnce(trigger = trigger, profileName = profileName, maxTemp = maxTemp, message = msg)
            return ApplyOutcome(
                isSuccess = false,
                overriddenByThermalOrHal = false,
                warnings = emptyList(),
                errorMessage = msg
            )
        }

        // Plan and validate every cluster before executing any write
        val allOperations = mutableListOf<SysfsWriteOperation>()
        val allWarnings = mutableListOf<String>()

        for ((policyId, targetConfig) in configs) {
            val cluster = clusterById[policyId] ?: continue
            when (val plan = SafeFrequencyPlanner.validateAndPlan(cluster, targetConfig)) {
                is ValidationPlanResult.Rejected -> {
                    logEvent(
                        trigger = trigger,
                        profileName = profileName,
                        success = false,
                        message = "Validation rejected for policy$policyId: ${plan.errorReason}"
                    )
                    return ApplyOutcome(
                        isSuccess = false,
                        overriddenByThermalOrHal = false,
                        warnings = allWarnings,
                        errorMessage = plan.errorReason
                    )
                }
                is ValidationPlanResult.Valid -> {
                    allOperations.addAll(plan.operations)
                    allWarnings.addAll(plan.warnings)
                }
            }
        }

        // Save current state as previous snapshot for Undo
        if (saveUndoSnapshot) {
            val preApplySnapshot = captureSnapshotFromClusters(clusters, "Pre-$profileName (${trigger.displayLabelEn})")
            preferences.savePreviousSnapshot(preApplySnapshot)
        }
        if (saveAsScreenOnBaseline) {
            preferences.saveScreenOnConfigs(configs)
        }

        // Set bootloop protection flag BEFORE writing to sysfs
        if (useBootloopGuard) {
            preferences.setPendingApply(true)
        }

        // Execute operations via SysfsWriter
        val writeResult = sysfsWriter.executeOperations(operations = allOperations, dryRun = dryRun)

        // Start 2-minute stabilization timer: if the device stays alive for 2 minutes, clear pending_apply
        if (useBootloopGuard) {
            stabilizationJob?.cancel()
            stabilizationJob = repoScope.launch {
                delay(STABILIZATION_WINDOW_MS)
                preferences.setPendingApply(false)
            }
        }

        val readBackSummary = if (writeResult.allSucceeded && !dryRun) {
            verifyAppliedValues(configs)
        } else emptyList()

        val statusMessage = if (writeResult.allSucceeded) {
            if (dryRun) {
                "[DRY-RUN] Simulated '$profileName' via ${trigger.displayLabelEn} (${writeResult.executedCommands.size} commands)."
            } else {
                val summary = if (readBackSummary.isEmpty()) "verified read-back" else readBackSummary.joinToString(", ")
                "Applied '$profileName' via ${trigger.displayLabelEn}; live state: $summary."
            }
        } else {
            writeResult.failedOperations.joinToString("; ")
        }

        if (writeResult.allSucceeded) {
            // A successful apply ends the thermal episode: the next block should be logged again.
            lastThermalBlockKey = ""
            lastThermalBlockLogMs = 0L
        }

        logEvent(
            trigger = trigger,
            profileName = profileName,
            success = writeResult.allSucceeded,
            message = statusMessage,
            commands = writeResult.executedCommands
        )

        return ApplyOutcome(
            isSuccess = writeResult.allSucceeded,
            overriddenByThermalOrHal = writeResult.overriddenBySystem,
            warnings = allWarnings,
            errorMessage = if (writeResult.allSucceeded) null else statusMessage,
            executedCommands = writeResult.executedCommands
        )
    }

    /**
     * Restores the previous snapshot ("Undo" button or 15-second confirmation timeout rollback).
     */
    suspend fun undoLastApply(trigger: TriggerType = TriggerType.MANUAL): ApplyOutcome {
        val prev = preferences.previousSnapshotFlow.first()
            ?: preferences.initialSnapshotFlow.first()
            ?: return ApplyOutcome(
                isSuccess = false,
                overriddenByThermalOrHal = false,
                warnings = emptyList(),
                errorMessage = "No previous snapshot available to undo."
            )
        val liveClusters = sysfsReader.readClusters()
        val outcome = applyClusterConfigs(
            configs = normalizeSnapshotConfigs(prev.clusterConfigs, liveClusters),
            trigger = trigger,
            profileName = "Undo (${prev.label})"
        )
        preferences.setPendingApply(false)
        return outcome
    }

    /**
     * Restores the initial system default snapshot captured on first run.
     */
    suspend fun restoreSystemDefaultSnapshot(trigger: TriggerType = TriggerType.SYSTEM_DEFAULT): ApplyOutcome {
        val initial = preferences.initialSnapshotFlow.first()
        if (initial != null && initial.clusterConfigs.isNotEmpty()) {
            val liveClusters = sysfsReader.readClusters()
            val outcome = applyClusterConfigs(
                configs = normalizeSnapshotConfigs(initial.clusterConfigs, liveClusters),
                trigger = trigger,
                profileName = "System Default Snapshot"
            )
            preferences.setPendingApply(false)
            return outcome
        }

        // If no initial snapshot exists yet, reset each cluster to its hardware cpuinfo_min..cpuinfo_max and schedutil
        val clusters = getClusters()
        val fallbackConfigs = clusters.associate { cluster ->
            val minF = cluster.availableFrequenciesKhz.firstOrNull() ?: cluster.cpuinfoMinFreqKhz
            val maxF = cluster.availableFrequenciesKhz.lastOrNull() ?: cluster.cpuinfoMaxFreqKhz
            val gov = cluster.availableGovernors.firstOrNull { it == "schedutil" }
                ?: cluster.availableGovernors.firstOrNull()
                ?: cluster.activeGovernor
            cluster.policyId to ClusterConfig(
                policyId = cluster.policyId,
                minFreqKhz = minF,
                maxFreqKhz = maxF,
                governor = gov,
                offlineCores = emptyList()
            )
        }
        val outcome = applyClusterConfigs(
            configs = fallbackConfigs,
            trigger = trigger,
            profileName = "Hardware Default (Min–Max)"
        )
        preferences.setPendingApply(false)
        return outcome
    }

    /**
     * Writes one governor to every cluster that exposes it (Feature: governor dropdown / per-app).
     * The write is verified by re-reading `scaling_governor`, and unsupported clusters are reported
     * instead of being written blindly.
     */
    suspend fun applyGovernor(
        governor: String,
        trigger: TriggerType,
        profileName: String,
        capturePerAppBaseline: Boolean = false,
        /** Restrict the write to specific policies; null means "every cluster that supports it". */
        policyIds: Set<Int>? = null
    ): GovernorApplyOutcome {
        val allClusters = sysfsReader.readClusters()
        if (allClusters.isEmpty()) {
            return GovernorApplyOutcome(false, "No CPU clusters detected on this device.")
        }
        val clusters = policyIds?.let { ids -> allClusters.filter { it.policyId in ids } } ?: allClusters
        val targets = clusters
            .filter { governor in it.availableGovernors }
            .associate { it.policyId to governor }
        if (targets.isEmpty()) {
            val available = allClusters.flatMap { it.availableGovernors }.distinct().sorted()
            val message = "Governor '$governor' is not available on any cluster. Available: ${available.joinToString()}"
            logEvent(trigger = trigger, profileName = profileName, success = false, message = message)
            return GovernorApplyOutcome(false, message)
        }
        if (capturePerAppBaseline) {
            // Remember the global governors so leaving the app can restore them exactly.
            preferences.savePerAppBaseline(allClusters.associate { it.policyId to it.activeGovernor })
        }
        val skipped = clusters.map { it.policyId }.filterNot { it in targets.keys }
        return executeGovernorWrites(targets, trigger, profileName, skipped)
    }

    /** Restores the governors captured before the first per-app governor apply. */
    suspend fun restoreGlobalGovernors(trigger: TriggerType, profileName: String): GovernorApplyOutcome {
        val baseline = preferences.perAppBaselineFlow.first()
            .ifEmpty { preferences.screenOnConfigsFlow.first().mapValues { it.value.governor } }
            .ifEmpty { preferences.initialSnapshotFlow.first()?.clusterConfigs?.mapValues { it.value.governor }.orEmpty() }
        if (baseline.isEmpty()) {
            return GovernorApplyOutcome(false, "No global governor baseline available to restore.")
        }
        val outcome = executeGovernorWrites(baseline, trigger, profileName, emptyList())
        if (outcome.isSuccess) preferences.savePerAppBaseline(emptyMap())
        return outcome
    }

    private suspend fun executeGovernorWrites(
        governorsByPolicy: Map<Int, String>,
        trigger: TriggerType,
        profileName: String,
        skippedPolicies: List<Int>
    ): GovernorApplyOutcome {
        val dryRun = preferences.dryRunModeFlow.first()
        val clusters = sysfsReader.readClusters().associateBy { it.policyId }
        val operations = governorsByPolicy.mapNotNull { (policyId, governor) ->
            val cluster = clusters[policyId] ?: return@mapNotNull null
            SysfsWriteOperation(
                path = "${cluster.policyPath.trimEnd('/')}/scaling_governor",
                value = governor,
                description = "Set policy$policyId governor to $governor"
            )
        }
        if (operations.isEmpty()) {
            return GovernorApplyOutcome(false, "No writable governor node found.")
        }

        val result = sysfsWriter.executeOperations(operations, dryRun = dryRun)
        val verified = if (dryRun || !result.allSucceeded) {
            governorsByPolicy
        } else {
            sysfsReader.readClusters().associate { it.policyId to it.activeGovernor }
                .filterKeys { it in governorsByPolicy.keys }
        }
        val mismatched = verified.filter { (policyId, actual) -> governorsByPolicy[policyId] != actual }
        val message = when {
            !result.allSucceeded -> result.failedOperations.joinToString("; ")
            dryRun -> "[DRY-RUN] Simulated '$profileName' (${operations.size} governor writes)."
            mismatched.isNotEmpty() -> "Governor write was overridden: " +
                mismatched.entries.joinToString { (policyId, actual) -> "policy$policyId read '$actual'" }
            else -> "Applied '$profileName'; verified: " +
                verified.entries.sortedBy { it.key }.joinToString { (policyId, governor) -> "policy$policyId=$governor" } +
                if (skippedPolicies.isNotEmpty()) " (not available on policy${skippedPolicies.sorted().joinToString()})" else ""
        }
        val success = result.allSucceeded && mismatched.isEmpty()
        logEvent(
            trigger = trigger,
            profileName = profileName,
            success = result.allSucceeded && mismatched.isEmpty(),
            message = message,
            commands = result.executedCommands
        )
        return GovernorApplyOutcome(
            isSuccess = success,
            message = message,
            verifiedByPolicy = verified,
            skippedPolicies = skippedPolicies
        )
    }

    /**
     * Applies one thermal step-down/step-up ceiling per cluster. Keeps the live governor, minimum and
     * offline-core state untouched and never writes a frequency outside `scaling_available_frequencies`.
     */
    suspend fun applyThermalCeilings(
        targetMaxByPolicy: Map<Int, Long>,
        maximumCelsius: Float
    ): ApplyOutcome {
        val clusters = sysfsReader.readClusters().associateBy { it.policyId }
        val configs = targetMaxByPolicy.mapNotNull { (policyId, targetMax) ->
            val cluster = clusters[policyId] ?: return@mapNotNull null
            val snapped = cluster.availableFrequenciesKhz.filter { it <= targetMax }.maxOrNull()
                ?: cluster.availableFrequenciesKhz.minOrNull()
                ?: cluster.maxFreqKhz
            val min = minOf(cluster.minFreqKhz, snapped)
            policyId to ClusterConfig(
                policyId = policyId,
                minFreqKhz = min,
                maxFreqKhz = snapped,
                governor = cluster.activeGovernor,
                offlineCores = cluster.relatedCpus.filter { it !in cluster.onlineCpus && it != 0 }
            )
        }.toMap()
        if (configs.isEmpty()) {
            return ApplyOutcome(false, false, emptyList(), "No matching clusters for thermal throttling.")
        }
        return applyClusterConfigs(
            configs = configs,
            trigger = TriggerType.HIGH_TEMPERATURE,
            profileName = "Thermal Throttle (${String.format(Locale.US, "%.1f", maximumCelsius)}°C)",
            saveUndoSnapshot = false,
            useBootloopGuard = false
        )
    }

    /**
     * Resolves the target per-cluster configuration for the currently configured Screen-Off profile.
     */
    suspend fun resolveScreenOffClusterConfigs(): Map<Int, ClusterConfig> {
        val settings = preferences.screenOffSettingsFlow.first()
        val clusters = getClusters()
        return when (settings.preset) {
            PresetType.BATTERY_SAVER, PresetType.BALANCED -> {
                clusters.associate { cluster ->
                    cluster.policyId to SafeFrequencyPlanner.buildPresetConfigForCluster(cluster, settings.preset)
                }
            }
            PresetType.CUSTOM -> {
                clusters.associate { cluster ->
                    val custom = settings.customClusterConfigs[cluster.policyId]
                    cluster.policyId to (custom ?: SafeFrequencyPlanner.buildPresetConfigForCluster(cluster, PresetType.BALANCED))
                }
            }
        }
    }

    /**
     * Logs at most one `HIGH_TEMPERATURE` block per temperature band per [THERMAL_BLOCK_LOG_INTERVAL_MS]
     * so a device idling at 60–73 °C no longer fills the log with 90+ duplicate entries
     * (perubahan.md §3.2).
     */
    private suspend fun logThermalBlockOnce(
        trigger: TriggerType,
        profileName: String,
        maxTemp: Float,
        message: String
    ) {
        val band = (maxTemp / THERMAL_TEMP_BAND_C).toInt()
        val key = "$profileName|$band"
        val now = System.currentTimeMillis()
        val shouldLog = key != lastThermalBlockKey || now - lastThermalBlockLogMs >= THERMAL_BLOCK_LOG_INTERVAL_MS
        if (!shouldLog) return
        lastThermalBlockKey = key
        lastThermalBlockLogMs = now
        logEvent(trigger = TriggerType.HIGH_TEMPERATURE, profileName = profileName, success = false, message = message)
    }

    /**
     * Re-reads every touched cluster and describes the live min/max/governor, so the event log proves
     * the applied values actually stuck instead of only echoing the requested numbers.
     */
    private suspend fun verifyAppliedValues(configs: Map<Int, ClusterConfig>): List<String> {
        val live = sysfsReader.readClusters().associateBy { it.policyId }
        return configs.keys.sorted().mapNotNull { policyId ->
            val cluster = live[policyId] ?: return@mapNotNull null
            "policy$policyId ${cluster.minFreqKhz}-${cluster.maxFreqKhz} kHz ${cluster.activeGovernor}"
        }
    }

    /**
     * Repairs snapshots captured from a governor-pinned kernel (`min == max == cpuinfo_max`, an artefact
     * of the old apply order), and clamps every value to the live hardware range. Without this, restoring
     * the "system default" snapshot would keep the CPU pinned at its maximum frequency.
     */
    fun normalizeSnapshotConfigs(
        configs: Map<Int, ClusterConfig>,
        liveClusters: List<CpuCluster>
    ): Map<Int, ClusterConfig> {
        val liveById = liveClusters.associateBy { it.policyId }
        return configs.mapValues { (policyId, config) ->
            val live = liveById[policyId] ?: return@mapValues config
            val hwMin = live.availableFrequenciesKhz.firstOrNull()?.coerceAtLeast(live.cpuinfoMinFreqKhz)
                ?: live.cpuinfoMinFreqKhz
            val hwMax = live.cpuinfoMaxFreqKhz.takeIf { it > 0L }
                ?: live.availableFrequenciesKhz.lastOrNull()
                ?: config.maxFreqKhz
            val pinned = config.minFreqKhz == config.maxFreqKhz && config.maxFreqKhz >= hwMax
            val min = if (pinned) hwMin else config.minFreqKhz.coerceIn(hwMin, hwMax)
            val max = config.maxFreqKhz.coerceIn(hwMin, hwMax)
            val governor = config.governor.takeIf { it.isNotBlank() && it in live.availableGovernors }
                ?: live.availableGovernors.firstOrNull { it == "schedutil" }
                ?: live.activeGovernor
            config.copy(
                minFreqKhz = min,
                maxFreqKhz = max.coerceAtLeast(min),
                governor = governor
            )
        }
    }

    private suspend fun logEvent(
        trigger: TriggerType,
        profileName: String,
        success: Boolean,
        message: String,
        commands: List<String> = emptyList()
    ) {
        preferences.appendEventLog(
            EventLogEntry(
                id = UUID.randomUUID().toString(),
                timestampEpochMs = System.currentTimeMillis(),
                trigger = trigger,
                profileName = profileName,
                success = success,
                message = message,
                commandsExecuted = commands
            )
        )
    }

    /**
     * Provides realistic 3-cluster (Little/Big/Prime) hardware data when Dry-Run Simulation Mode is toggled ON
     * on an emulator or unrooted environment.
     */
    private fun buildSimulatedClusters(): List<CpuCluster> {
        return listOf(
            CpuCluster(
                policyId = 0,
                policyPath = "/sys/devices/system/cpu/cpufreq/policy0",
                tier = ClusterTier.LITTLE,
                affectedCpus = listOf(0, 1, 2, 3),
                relatedCpus = listOf(0, 1, 2, 3),
                onlineCpus = listOf(0, 1, 2, 3),
                currentFreqKhz = 1113600L,
                minFreqKhz = 307200L,
                maxFreqKhz = 1804800L,
                cpuinfoMinFreqKhz = 307200L,
                cpuinfoMaxFreqKhz = 1804800L,
                activeGovernor = "schedutil",
                availableFrequenciesKhz = listOf(307200L, 614400L, 902400L, 1113600L, 1401600L, 1804800L),
                availableGovernors = listOf("schedutil", "powersave", "performance", "conservative")
            ),
            CpuCluster(
                policyId = 4,
                policyPath = "/sys/devices/system/cpu/cpufreq/policy4",
                tier = ClusterTier.BIG,
                affectedCpus = listOf(4, 5, 6),
                relatedCpus = listOf(4, 5, 6),
                onlineCpus = listOf(4, 5, 6),
                currentFreqKhz = 1497600L,
                minFreqKhz = 633600L,
                maxFreqKhz = 2419200L,
                cpuinfoMinFreqKhz = 633600L,
                cpuinfoMaxFreqKhz = 2419200L,
                activeGovernor = "schedutil",
                availableFrequenciesKhz = listOf(633600L, 960000L, 1286400L, 1497600L, 1881600L, 2112000L, 2419200L),
                availableGovernors = listOf("schedutil", "powersave", "performance")
            ),
            CpuCluster(
                policyId = 7,
                policyPath = "/sys/devices/system/cpu/cpufreq/policy7",
                tier = ClusterTier.PRIME,
                affectedCpus = listOf(7),
                relatedCpus = listOf(7),
                onlineCpus = listOf(7),
                currentFreqKhz = 1728000L,
                minFreqKhz = 806400L,
                maxFreqKhz = 2841600L,
                cpuinfoMinFreqKhz = 806400L,
                cpuinfoMaxFreqKhz = 2841600L,
                activeGovernor = "schedutil",
                availableFrequenciesKhz = listOf(806400L, 1190400L, 1497600L, 1728000L, 2188800L, 2496000L, 2841600L),
                availableGovernors = listOf("schedutil", "powersave", "performance")
            )
        )
    }
}
