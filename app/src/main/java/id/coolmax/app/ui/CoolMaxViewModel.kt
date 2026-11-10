package id.coolmax.app.ui

import android.app.Application
import android.content.Context
import android.os.PowerManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import id.coolmax.app.CoolMaxApplication
import id.coolmax.app.data.packages.InstalledAppInfo
import id.coolmax.app.data.packages.InstalledAppRepository
import id.coolmax.app.domain.model.ClusterConfig
import id.coolmax.app.domain.model.CpuCluster
import id.coolmax.app.domain.model.CpuProfile
import id.coolmax.app.domain.model.DeviceInfo
import id.coolmax.app.domain.model.EventLogEntry
import id.coolmax.app.domain.model.KernelSnapshot
import id.coolmax.app.domain.model.PresetType
import id.coolmax.app.domain.model.ScreenOffSettings
import id.coolmax.app.domain.model.ThermalSensorReading
import id.coolmax.app.domain.model.TriggerSettings
import id.coolmax.app.domain.model.TriggerType
import id.coolmax.app.domain.profile.ProfileJsonValidator
import id.coolmax.app.domain.safety.SafeFrequencyPlanner
import id.coolmax.app.domain.thermal.ThermalThrottlePlanner
import id.coolmax.app.domain.trigger.GovernorPriorityResolver
import id.coolmax.app.service.ForegroundAppDetector
import id.coolmax.app.service.ScreenOffGuardService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Historical point for real-time frequency & temperature charting while Dashboard is visible.
 */
data class RealtimeSample(
    val timestampMs: Long,
    val clusterFreqsKhz: Map<Int, Long>,
    val maxTempCelsius: Float
)

data class ManualConfirmationState(
    val remainingSeconds: Int,
    val appliedProfileName: String
)

data class UiBannerMessage(
    val text: String,
    val isError: Boolean = false,
    val isWarning: Boolean = false
)

class CoolMaxViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as CoolMaxApplication
    private val repository = app.cpuRepository
    private val preferences = app.preferences

    private val _isCheckingRoot = MutableStateFlow(true)
    val isCheckingRoot: StateFlow<Boolean> = _isCheckingRoot.asStateFlow()

    private val _isRootGranted = MutableStateFlow(false)
    val isRootGranted: StateFlow<Boolean> = _isRootGranted.asStateFlow()

    private val _clusters = MutableStateFlow<List<CpuCluster>>(emptyList())
    val clusters: StateFlow<List<CpuCluster>> = _clusters.asStateFlow()

    private val _thermalReadings = MutableStateFlow<List<ThermalSensorReading>>(emptyList())
    val thermalReadings: StateFlow<List<ThermalSensorReading>> = _thermalReadings.asStateFlow()

    private val _deviceInfo = MutableStateFlow<DeviceInfo?>(null)
    val deviceInfo: StateFlow<DeviceInfo?> = _deviceInfo.asStateFlow()

    private val _realtimeHistory = MutableStateFlow<List<RealtimeSample>>(emptyList())
    val realtimeHistory: StateFlow<List<RealtimeSample>> = _realtimeHistory.asStateFlow()

    // Draft edits on CPU Control screen (policyId -> ClusterConfig)
    private val _draftConfigs = MutableStateFlow<Map<Int, ClusterConfig>>(emptyMap())
    val draftConfigs: StateFlow<Map<Int, ClusterConfig>> = _draftConfigs.asStateFlow()

    // 15-second auto-revert confirmation state after Manual Apply
    private val _manualConfirmation = MutableStateFlow<ManualConfirmationState?>(null)
    val manualConfirmation: StateFlow<ManualConfirmationState?> = _manualConfirmation.asStateFlow()

    private val _bannerMessage = MutableStateFlow<UiBannerMessage?>(null)
    val bannerMessage: StateFlow<UiBannerMessage?> = _bannerMessage.asStateFlow()

    val screenOffSettings: StateFlow<ScreenOffSettings> = preferences.screenOffSettingsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ScreenOffSettings())

    val triggerSettings: StateFlow<TriggerSettings> = preferences.triggerSettingsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TriggerSettings())

    val savedProfiles: StateFlow<List<CpuProfile>> = preferences.savedProfilesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val eventLogs: StateFlow<List<EventLogEntry>> = preferences.eventLogsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val previousSnapshot: StateFlow<KernelSnapshot?> = preferences.previousSnapshotFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val safeModeActive: StateFlow<Boolean> = preferences.safeModeActiveFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val safeModeReason: StateFlow<String?> = preferences.safeModeReasonFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val dryRunMode: StateFlow<Boolean> = preferences.dryRunModeFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val installedAppsRepository = InstalledAppRepository(application)
    private val foregroundAppDetector = ForegroundAppDetector(application)

    private val _installedApps = MutableStateFlow<List<InstalledAppInfo>>(emptyList())
    val installedApps: StateFlow<List<InstalledAppInfo>> = _installedApps.asStateFlow()

    private val _isLoadingApps = MutableStateFlow(false)
    val isLoadingApps: StateFlow<Boolean> = _isLoadingApps.asStateFlow()

    private val _hasUsageStatsPermission = MutableStateFlow(false)
    val hasUsageStatsPermission: StateFlow<Boolean> = _hasUsageStatsPermission.asStateFlow()

    /** Live thermal throttle state written by [ScreenOffGuardService]. */
    val thermalThrottleActive: StateFlow<Boolean> = preferences.thermalThrottleActiveFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Highest temperature of the currently known sensors (null when unavailable). */
    val currentMaxTemperature: StateFlow<Float?> = _thermalReadings
        .map { readings -> readings.maxOfOrNull { it.tempCelsius } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Every governor exposed by any cluster, used by the dropdowns. */
    val governorOptions: StateFlow<List<String>> = _clusters
        .map { clusters -> clusters.flatMap { it.availableGovernors }.distinct().sorted() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private var liveMonitorJob: Job? = null
    private var manualConfirmJob: Job? = null

    init {
        checkRootAndLoadInitialData()
        reconcileScreenOffNetworkMode()
        reconcileScreenOffAppFreeze()
    }

    /**
     * Thaws every app still recorded as frozen when the app is opened while the screen is on (e.g. the
     * guard service was killed during a screen-off freeze).
     */
    private fun reconcileScreenOffAppFreeze() {
        viewModelScope.launch {
            val powerManager = app.getSystemService(Context.POWER_SERVICE) as? PowerManager
            runCatching {
                app.appFreezer.reconcile(isScreenOn = powerManager?.isInteractive ?: true)
            }
        }
    }

    /**
     * Repairs the 2G-only screen-off network state when the app is opened: an airplane-mode refresh that
     * was interrupted is cleared, and the default network mode is restored if the display is already on
     * (e.g. the guard service was killed while 2G-only was active).
     */
    private fun reconcileScreenOffNetworkMode() {
        viewModelScope.launch {
            val powerManager = app.getSystemService(Context.POWER_SERVICE) as? PowerManager
            runCatching {
                app.networkModeController.reconcile(isScreenOn = powerManager?.isInteractive ?: true)
            }
        }
    }

    /**
     * Checks root access via `libsu` (or allows Dry-Run Simulation Mode) and loads initial hardware state.
     */
    fun checkRootAndLoadInitialData() {
        viewModelScope.launch {
            _isCheckingRoot.value = true
            val rooted = repository.rootManager.checkRootAccess()
            val dryRun = preferences.dryRunModeFlow.first()
            _isRootGranted.value = rooted || dryRun
            _isCheckingRoot.value = false

            if (rooted || dryRun) {
                refreshAllOnce()
            }
        }
    }

    fun enableDryRunAndContinue() {
        viewModelScope.launch {
            preferences.setDryRunMode(true)
            checkRootAndLoadInitialData()
        }
    }

    fun setDryRunMode(enabled: Boolean) {
        viewModelScope.launch {
            preferences.setDryRunMode(enabled)
            checkRootAndLoadInitialData()
        }
    }

    /**
     * Refreshes CPU clusters, temperatures, and device info once.
     */
    fun refreshAllOnce() {
        viewModelScope.launch {
            val loadedClusters = repository.getClusters()
            _clusters.value = loadedClusters
            if (_draftConfigs.value.isEmpty() && loadedClusters.isNotEmpty()) {
                // A cold read right after the display wakes can catch MTK's transitional pinned state
                // (min == max == cpuinfo_max). Reuse the snapshot normalizer so the draft never starts
                // out pinned at the maximum frequency.
                val raw = loadedClusters.associate { c ->
                    c.policyId to ClusterConfig(
                        policyId = c.policyId,
                        minFreqKhz = c.minFreqKhz,
                        maxFreqKhz = c.maxFreqKhz,
                        governor = c.activeGovernor,
                        offlineCores = c.relatedCpus.filter { it !in c.onlineCpus && it != 0 }
                    )
                }
                _draftConfigs.value = repository.normalizeSnapshotConfigs(raw, loadedClusters)
            }
            val thermals = repository.getThermalReadings()
            _thermalReadings.value = thermals
            if (_deviceInfo.value == null) {
                _deviceInfo.value = repository.getDeviceInfo()
            }
        }
    }

    /**
     * Starts 1-second live polling ONLY when the UI is visible (`Lifecycle.State.STARTED`).
     * Automatically cancelled when the app goes into the background.
     */
    fun startLiveMonitoring() {
        if (liveMonitorJob?.isActive == true) return
        liveMonitorJob = viewModelScope.launch {
            while (isActive) {
                if (_isRootGranted.value) {
                    val liveClusters = repository.getClusters()
                    val thermals = repository.getThermalReadings()
                    _clusters.value = liveClusters
                    _thermalReadings.value = thermals

                    val sample = RealtimeSample(
                        timestampMs = System.currentTimeMillis(),
                        clusterFreqsKhz = liveClusters.associate { it.policyId to it.currentFreqKhz },
                        maxTempCelsius = thermals.maxOfOrNull { it.tempCelsius } ?: 0f
                    )
                    _realtimeHistory.update { history -> (history + sample).takeLast(30) }
                }
                delay(1_000L)
            }
        }
    }

    fun stopLiveMonitoring() {
        liveMonitorJob?.cancel()
        liveMonitorJob = null
    }

    fun updateDraftClusterConfig(policyId: Int, updater: (ClusterConfig) -> ClusterConfig) {
        _draftConfigs.update { current ->
            val existing = current[policyId] ?: return@update current
            current + (policyId to updater(existing))
        }
    }

    /**
     * Applies manual CPU configuration and starts the mandatory 15-second auto-revert confirmation countdown.
     * If the user does not press "Keep Changes" ("Pertahankan") within 15 seconds, the previous snapshot is restored.
     */
    fun applyDraftConfigsWith15sConfirmation() {
        viewModelScope.launch {
            val configs = _draftConfigs.value
            val outcome = repository.applyClusterConfigs(
                configs = configs,
                trigger = TriggerType.MANUAL,
                profileName = "Manual CPU Config",
                saveAsScreenOnBaseline = true
            )
            refreshAllOnce()

            if (!outcome.isSuccess) {
                _bannerMessage.value = UiBannerMessage(
                    text = outcome.errorMessage ?: "Failed to apply CPU frequencies.",
                    isError = true
                )
                return@launch
            }

            if (outcome.warnings.isNotEmpty()) {
                _bannerMessage.value = UiBannerMessage(
                    text = outcome.warnings.joinToString("\n"),
                    isWarning = true
                )
            } else {
                _bannerMessage.value = UiBannerMessage(
                    text = "Configuration applied & verified. Confirm within 15s to keep."
                )
            }

            // Start 15-second countdown
            manualConfirmJob?.cancel()
            manualConfirmJob = viewModelScope.launch {
                for (remaining in 15 downTo 1) {
                    _manualConfirmation.value = ManualConfirmationState(
                        remainingSeconds = remaining,
                        appliedProfileName = "Manual CPU Config"
                    )
                    delay(1_000L)
                }
                // Timeout reached without user confirmation -> auto-rollback!
                _manualConfirmation.value = null
                repository.undoLastApply()
                refreshAllOnce()
                _bannerMessage.value = UiBannerMessage(
                    text = "15s confirmation timed out, so the previous safe configuration was restored.",
                    isWarning = true
                )
            }
        }
    }

    /**
     * User clicked "Pertahankan" ("Keep Changes") on the 15-second confirmation dialog.
     */
    fun confirmKeepManualChanges() {
        manualConfirmJob?.cancel()
        manualConfirmJob = null
        _manualConfirmation.value = null
        _bannerMessage.value = UiBannerMessage(text = "Manual configuration confirmed and kept.")
    }

    /**
     * User clicked "Undo" or "Revert Now" on the 15-second dialog.
     */
    fun undoLastChange() {
        manualConfirmJob?.cancel()
        manualConfirmJob = null
        _manualConfirmation.value = null
        viewModelScope.launch {
            val outcome = repository.undoLastApply()
            refreshAllOnce()
            _bannerMessage.value = if (outcome.isSuccess) {
                UiBannerMessage(text = "Reverted to previous snapshot.")
            } else {
                UiBannerMessage(text = outcome.errorMessage ?: "Undo failed.", isError = true)
            }
        }
    }

    /**
     * Always-visible "Reset to System Default" action.
     */
    fun resetToSystemDefault() {
        manualConfirmJob?.cancel()
        manualConfirmJob = null
        _manualConfirmation.value = null
        viewModelScope.launch {
            val outcome = repository.restoreSystemDefaultSnapshot(TriggerType.SYSTEM_DEFAULT)
            refreshAllOnce()
            // Sync draft configs with restored cluster values
            _draftConfigs.value = _clusters.value.associate { c ->
                c.policyId to ClusterConfig(
                    policyId = c.policyId,
                    minFreqKhz = c.minFreqKhz,
                    maxFreqKhz = c.maxFreqKhz,
                    governor = c.activeGovernor,
                    offlineCores = emptyList()
                )
            }
            _bannerMessage.value = if (outcome.isSuccess) {
                UiBannerMessage(text = "Restored original system default frequencies and governors.")
            } else {
                UiBannerMessage(text = outcome.errorMessage ?: "Reset failed.", isError = true)
            }
        }
    }

    fun dismissSafeMode() {
        viewModelScope.launch {
            preferences.clearSafeMode()
            _bannerMessage.value = UiBannerMessage(text = "Safe Mode cleared. Auto-apply features remain OFF until re-enabled.")
        }
    }

    fun clearBannerMessage() {
        _bannerMessage.value = null
    }

    // --- Screen-Off Profile Management ---

    fun updateScreenOffSettings(updater: (ScreenOffSettings) -> ScreenOffSettings) {
        viewModelScope.launch {
            val current = screenOffSettings.value
            val updated = updater(current)
            // Populate default custom configs if switching to CUSTOM for the first time
            val withCustomPopulated = if (updated.preset == PresetType.CUSTOM && updated.customClusterConfigs.isEmpty()) {
                val defaultCustom = _clusters.value.associate { cluster ->
                    cluster.policyId to SafeFrequencyPlanner.buildPresetConfigForCluster(cluster, PresetType.BALANCED)
                }
                updated.copy(customClusterConfigs = defaultCustom)
            } else {
                updated
            }
            preferences.updateScreenOffSettings(withCustomPopulated)

            // Disabling the 2G switch (or the whole screen-off profile) must release the network mode
            // immediately instead of waiting for the next screen-on event.
            if (current.network2gOnScreenOff &&
                (!withCustomPopulated.network2gOnScreenOff || !withCustomPopulated.enabled)
            ) {
                runCatching { app.networkModeController.restoreDefaultIfActive() }
            }

            // Same for the app freeze: switching it (or the profile) off must thaw immediately.
            if (current.freezeAppsOnScreenOff &&
                (!withCustomPopulated.freezeAppsOnScreenOff || !withCustomPopulated.enabled)
            ) {
                runCatching { app.appFreezer.unfreezeAll() }
            }

            if (withCustomPopulated.enabled) {
                ScreenOffGuardService.startServiceIfNeeded(app)
            } else {
                val trig = triggerSettings.value
                if (!trig.batteryTriggerEnabled && !trig.scheduleTriggerEnabled && !trig.perAppTriggerEnabled) {
                    ScreenOffGuardService.stopService(app)
                }
            }
        }
    }

    fun updateTriggerSettings(updater: (TriggerSettings) -> TriggerSettings) {
        viewModelScope.launch {
            val updated = updater(triggerSettings.value)
            preferences.updateTriggerSettings(updated)
            val anyActive = screenOffSettings.value.enabled ||
                updated.batteryTriggerEnabled ||
                updated.scheduleTriggerEnabled ||
                updated.perAppTriggerEnabled ||
                updated.thermalGuardEnabled
            if (anyActive) {
                ScreenOffGuardService.startServiceIfNeeded(app)
            } else {
                ScreenOffGuardService.stopService(app)
            }
        }
    }

    // --- Saved Profiles CRUD + JSON Export/Import ---

    fun createProfileFromDraft(name: String, iconName: String = "speed") {
        viewModelScope.launch {
            val trimmed = name.trim().ifEmpty { "Custom Profile" }
            val newProfile = CpuProfile(
                id = UUID.randomUUID().toString(),
                name = trimmed,
                iconName = iconName,
                presetType = PresetType.CUSTOM,
                clusterConfigs = _draftConfigs.value
            )
            preferences.saveProfiles(savedProfiles.value + newProfile)
            _bannerMessage.value = UiBannerMessage(text = "Saved profile '$trimmed'.")
        }
    }

    fun duplicateProfile(profile: CpuProfile) {
        viewModelScope.launch {
            val copy = profile.copy(
                id = UUID.randomUUID().toString(),
                name = "${profile.name} (Copy)".take(50),
                createdAtEpochMs = System.currentTimeMillis()
            )
            preferences.saveProfiles(savedProfiles.value + copy)
        }
    }

    fun deleteProfile(profileId: String) {
        viewModelScope.launch {
            preferences.saveProfiles(savedProfiles.value.filterNot { it.id == profileId })
        }
    }

    fun applySavedProfile(profile: CpuProfile) {
        _draftConfigs.value = profile.clusterConfigs
        applyDraftConfigsWith15sConfirmation()
    }

    fun exportProfileJson(profile: CpuProfile): String {
        return ProfileJsonValidator.exportToJson(profile)
    }

    fun importProfileJson(rawJson: String) {
        viewModelScope.launch {
            when (val res = ProfileJsonValidator.validateAndImport(rawJson, _clusters.value)) {
                is ProfileJsonValidator.ImportResult.Invalid -> {
                    _bannerMessage.value = UiBannerMessage(
                        text = "Import rejected: ${res.error}",
                        isError = true
                    )
                }
                is ProfileJsonValidator.ImportResult.Success -> {
                    val imported = res.profile.copy(id = UUID.randomUUID().toString())
                    preferences.saveProfiles(savedProfiles.value + imported)
                    _bannerMessage.value = UiBannerMessage(
                        text = "Imported profile '${imported.name}' successfully.",
                        isWarning = res.warnings.isNotEmpty()
                    )
                }
            }
        }
    }

    // --- Governor dropdown (Feature 1) ---

    /**
     * Writes the chosen governor to that cluster immediately, then re-reads it to prove the kernel
     * accepted it. The draft stays in sync so a later "Apply" keeps the same governor.
     */
    fun selectGovernor(policyId: Int, governor: String) {
        val cluster = _clusters.value.firstOrNull { it.policyId == policyId }
        if (cluster != null && !GovernorPriorityResolver.isGovernorSupported(governor, cluster.availableGovernors)) {
            _bannerMessage.value = UiBannerMessage(
                text = "Governor '$governor' is not available on policy$policyId. Available: ${cluster.availableGovernors.joinToString()}",
                isError = true
            )
            return
        }
        updateDraftClusterConfig(policyId) { it.copy(governor = governor) }
        viewModelScope.launch {
            val outcome = repository.applyGovernor(
                governor = governor,
                trigger = TriggerType.MANUAL,
                profileName = "Governor policy$policyId",
                policyIds = setOf(policyId)
            )
            refreshAllOnce()
            _bannerMessage.value = UiBannerMessage(
                text = outcome.message,
                isError = !outcome.isSuccess
            )
        }
    }

    // --- Custom thermal limit (Feature 2) ---

    fun setThermalGuardEnabled(enabled: Boolean) {
        updateTriggerSettings { it.copy(thermalGuardEnabled = enabled) }
    }

    fun setThermalLimitCelsius(valueCelsius: Float) {
        updateTriggerSettings {
            it.copy(thermalLimitCelsius = ThermalThrottlePlanner.sanitizeLimit(valueCelsius))
        }
    }

    // --- Per-app governor (Feature 3) ---

    fun setPerAppTriggerEnabled(enabled: Boolean) {
        updateTriggerSettings { it.copy(perAppTriggerEnabled = enabled) }
        refreshUsageStatsPermission()
    }

    fun refreshUsageStatsPermission() {
        _hasUsageStatsPermission.value = foregroundAppDetector.hasUsageStatsPermission()
    }

    /** Loads launchable apps off the main thread and prunes rules for uninstalled packages. */
    fun loadInstalledApps(force: Boolean = false) {
        if (_isLoadingApps.value) return
        if (!force && _installedApps.value.isNotEmpty()) return
        viewModelScope.launch {
            _isLoadingApps.value = true
            val apps = installedAppsRepository.loadLaunchableApps()
            _installedApps.value = apps
            _isLoadingApps.value = false
            pruneUninstalledPerAppRules(apps)
            refreshUsageStatsPermission()
        }
    }

    fun setPerAppGovernor(packageName: String, governor: String) {
        if (!GovernorPriorityResolver.isGovernorSupported(governor, governorOptions.value)) {
            _bannerMessage.value = UiBannerMessage(
                text = "Governor '$governor' is not available in this kernel; the rule was not saved.",
                isError = true
            )
            return
        }
        updateTriggerSettings { it.copy(perAppGovernors = it.perAppGovernors + (packageName to governor)) }
        _bannerMessage.value = UiBannerMessage(
            text = "Per-app governor saved: $packageName -> $governor (applied when it comes to the foreground)."
        )
    }

    fun removePerAppGovernor(packageName: String) {
        updateTriggerSettings { it.copy(perAppGovernors = it.perAppGovernors - packageName) }
        _bannerMessage.value = UiBannerMessage(
            text = "Restored $packageName to the global governor."
        )
    }

    fun restoreAllPerAppGovernors() {
        updateTriggerSettings { it.copy(perAppGovernors = emptyMap()) }
        _bannerMessage.value = UiBannerMessage(text = "All per-app rules removed; global governor applies everywhere.")
    }

    fun labelForPackage(packageName: String): String =
        _installedApps.value.firstOrNull { it.packageName == packageName }?.label ?: packageName

    private fun pruneUninstalledPerAppRules(apps: List<InstalledAppInfo>) {
        if (apps.isEmpty()) return
        val installed = apps.mapTo(mutableSetOf()) { it.packageName }
        val current = triggerSettings.value.perAppGovernors
        val stale = current.keys.filterNot { it in installed }

        // Freeze whitelist entries for uninstalled apps are pruned as well.
        val freezeSettings = screenOffSettings.value
        val staleWhitelist = freezeSettings.freezeWhitelist.filterNot { it in installed }
        if (staleWhitelist.isNotEmpty()) {
            viewModelScope.launch {
                preferences.updateScreenOffSettings(
                    screenOffSettings.value.copy(
                        freezeWhitelist = freezeSettings.freezeWhitelist - staleWhitelist.toSet()
                    )
                )
            }
        }

        if (stale.isEmpty()) return
        viewModelScope.launch {
            preferences.updateTriggerSettings(
                triggerSettings.value.copy(perAppGovernors = current - stale.toSet())
            )
            _bannerMessage.value = UiBannerMessage(
                text = "Removed ${stale.size} per-app rule(s) for uninstalled apps.",
                isWarning = true
            )
        }
    }

    fun clearLogs() {
        viewModelScope.launch {
            preferences.clearEventLogs()
        }
    }

    class Factory(private val application: Application) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return CoolMaxViewModel(application) as T
        }
    }
}
