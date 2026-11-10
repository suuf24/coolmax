package id.coolmax.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import id.coolmax.app.CoolMaxApplication
import id.coolmax.app.MainActivity
import id.coolmax.app.R
import id.coolmax.app.domain.model.PresetType
import id.coolmax.app.domain.model.ScreenOnRestoreMode
import id.coolmax.app.domain.model.TriggerType
import id.coolmax.app.domain.model.TriggerSettings
import id.coolmax.app.domain.safety.SafeFrequencyPlanner
import id.coolmax.app.domain.thermal.ThermalAction
import id.coolmax.app.domain.thermal.ThermalGuardConfig
import id.coolmax.app.domain.thermal.ThermalThrottlePlanner
import id.coolmax.app.domain.trigger.GovernorDecision
import id.coolmax.app.domain.trigger.GovernorPriorityInput
import id.coolmax.app.domain.trigger.GovernorPriorityResolver
import id.coolmax.app.domain.trigger.TriggerEvaluationInput
import id.coolmax.app.domain.trigger.TriggerPriorityResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Calendar

/**
 * Core ForegroundService that monitors `ACTION_SCREEN_OFF` and `ACTION_SCREEN_ON` (dynamically registered,
 * never in AndroidManifest.xml) as well as thermal, battery, per-app, and schedule triggers.
 *
 * Includes persistent notification (with user-dismissible notification channel settings)
 * and quick action buttons: "Pause 1 Hour" and "Reset to System Default".
 */
class ScreenOffGuardService : Service() {

    companion object {
        const val CHANNEL_ID_GUARD = "coolmax_service_channel"
        const val CHANNEL_ID_ALERT = "coolmax_thermal_alert_channel"
        const val NOTIFICATION_ID_GUARD = 1001
        const val NOTIFICATION_ID_THERMAL_ALERT = 1002

        const val ACTION_START_GUARD = "id.coolmax.app.ACTION_START_GUARD"
        const val ACTION_STOP_GUARD = "id.coolmax.app.ACTION_STOP_GUARD"
        const val ACTION_PAUSE_ONE_HOUR = "id.coolmax.app.ACTION_PAUSE_ONE_HOUR"
        const val ACTION_RESET_DEFAULTS = "id.coolmax.app.ACTION_RESET_DEFAULTS"

        /** An app must stay in front this long before its per-app governor is written (debounce). */
        private const val PER_APP_SETTLE_MS = 1_500L
        private const val PER_APP_MIN_WRITE_INTERVAL_MS = 2_000L

        /** How often the 2G network watchdog retries a restore that has not gone through yet. */
        private const val NETWORK_RESTORE_RETRY_MS = 60_000L

        fun startServiceIfNeeded(context: Context) {
            val intent = Intent(context, ScreenOffGuardService::class.java).apply {
                action = ACTION_START_GUARD
            }
            // minSdk is 26, so the foreground-service entry point always exists.
            context.startForegroundService(intent)
        }

        fun stopService(context: Context) {
            val intent = Intent(context, ScreenOffGuardService::class.java).apply {
                action = ACTION_STOP_GUARD
            }
            context.stopService(intent)
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var screenOffDelayJob: Job? = null
    private var periodicMonitorJob: Job? = null
    private var thermalMonitorJob: Job? = null
    private var isScreenReceiverRegistered = false
    private var preScreenOffCaptured = false

    /** Serializes every sysfs write so the fast thermal loop and the trigger loop never interleave. */
    private val applyMutex = Mutex()

    /** One thermal alert per throttling episode (a device hovering on the limit must not spam). */
    private var thermalAlertShown = false

    /** Per-app governor debounce: an app only counts once it stayed in front for [PER_APP_SETTLE_MS]. */
    private var observedForegroundPkg: String? = null
    private var observedForegroundAtMs = 0L
    private var activePerAppPackage: String? = null
    private var lastPerAppWriteMs = 0L
    private var lastNetworkRestoreAttemptMs = 0L

    private val app by lazy { application as CoolMaxApplication }
    private val repository by lazy { app.cpuRepository }
    private val preferences by lazy { app.preferences }
    private val foregroundAppDetector by lazy { ForegroundAppDetector(this) }
    private val networkModeController by lazy { app.networkModeController }
    private val appFreezer by lazy { app.appFreezer }

    /**
     * Dynamically registered BroadcastReceiver for ACTION_SCREEN_OFF and ACTION_SCREEN_ON.
     */
    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> handleScreenOffEvent()
                Intent.ACTION_SCREEN_ON -> handleScreenOnEvent()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        startForegroundWithCompatibility(
            buildGuardNotification(getString(R.string.notif_status_monitoring))
        )
        registerDynamicScreenReceiver()
        startPeriodicTriggerLoop()
        startThermalAndPerAppLoop()

        // Fail-safe: never leave an interrupted airplane-mode refresh or a stale 2G-only network mode
        // behind when the service is (re)created while the display is already on.
        serviceScope.launch {
            val screenOn = isInteractiveScreenOn()
            runCatching { networkModeController.reconcile(isScreenOn = screenOn) }
            // A freeze must never outlive the screen-off period of a previous service process.
            runCatching { appFreezer.reconcile(isScreenOn = screenOn) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_GUARD -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_PAUSE_ONE_HOUR -> {
                serviceScope.launch {
                    val oneHourLater = System.currentTimeMillis() + 3_600_000L
                    preferences.setPauseUntil(oneHourLater)
                    runCatching { appFreezer.unfreezeAll() }
                    runCatching { networkModeController.restoreDefaultIfActive() }
                    repository.restoreSystemDefaultSnapshot(TriggerType.MANUAL)
                    updateNotification(getString(R.string.notif_status_paused_1h))
                }
            }
            ACTION_RESET_DEFAULTS -> {
                serviceScope.launch {
                    runCatching { appFreezer.unfreezeAll() }
                    runCatching { networkModeController.restoreDefaultIfActive() }
                    repository.restoreSystemDefaultSnapshot(TriggerType.SYSTEM_DEFAULT)
                    updateNotification(getString(R.string.notif_status_reset_done))
                }
            }
        }
        return START_STICKY
    }

    private fun registerDynamicScreenReceiver() {
        if (!isScreenReceiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(screenStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(screenStateReceiver, filter)
            }
            isScreenReceiverRegistered = true
        }
    }

    private fun unregisterDynamicScreenReceiver() {
        if (isScreenReceiverRegistered) {
            runCatching { unregisterReceiver(screenStateReceiver) }
            isScreenReceiverRegistered = false
        }
    }

    /**
     * Triggered when screen turns OFF:
     * 1. Checks if Screen-Off profile is enabled and not paused.
     * 2. Waits the user-configured delay (`delaySeconds`, 0..60s, default 5s).
     * 3. Verifies the screen is still OFF and checks charging/media exclusions.
     * 4. Saves current screen-on snapshot before applying the Screen-Off profile.
     */
    private fun handleScreenOffEvent() {
        screenOffDelayJob?.cancel()
        screenOffDelayJob = serviceScope.launch {
            val safeMode = preferences.safeModeActiveFlow.first()
            if (safeMode) return@launch

            val pauseUntil = preferences.pauseUntilEpochMsFlow.first()
            if (System.currentTimeMillis() < pauseUntil) return@launch

            val settings = preferences.screenOffSettingsFlow.first()
            if (!settings.enabled) return@launch

            val delayMs = settings.delaySeconds.coerceIn(0, 60) * 1_000L
            if (delayMs > 0L) {
                delay(delayMs)
            }

            // Re-verify screen is still off after delay
            if (isInteractiveScreenOn()) return@launch

            // Check charging exclusion
            if (settings.skipWhenCharging && isDeviceCharging()) {
                updateNotification(getString(R.string.notif_status_skipped_charging))
                return@launch
            }

            // Check media playback exclusion
            if (settings.skipWhenMediaPlaying && isAudioPlaybackActive()) {
                updateNotification(getString(R.string.notif_status_skipped_media))
                return@launch
            }

            val outcome = applyMutex.withLock {
                // Capture current screen-on baseline before applying screen-off profile
                val liveClusters = repository.getClusters()
                if (liveClusters.isNotEmpty()) {
                    val baseline = repository.captureSnapshotFromClusters(liveClusters, "Pre-Screen-Off Baseline")
                    preferences.saveScreenOnConfigs(baseline.clusterConfigs)
                    preScreenOffCaptured = true
                }

                val targetConfigs = repository.resolveScreenOffClusterConfigs()
                repository.applyClusterConfigs(
                    configs = targetConfigs,
                    trigger = TriggerType.SCREEN_OFF,
                    profileName = "Screen-Off (${settings.preset.name})"
                )
            }

            // The screen-off profile owns the governor layer while the display is off, so the per-app
            // governor must be re-evaluated (and re-applied) after the screen comes back on.
            activePerAppPackage = null

            // Screen-Off Network Guard: force the cellular radio to 2G only. The previous/default mode
            // is captured first and restored by `handleScreenOnEvent()`.
            if (settings.network2gOnScreenOff) {
                runCatching { networkModeController.apply2gOnly() }
            }

            // Screen-Off App Guard: freeze every running app except the whitelisted ones.
            if (settings.freezeAppsOnScreenOff) {
                runCatching { appFreezer.freezeAll(settings.freezeWhitelist) }
            }

            if (outcome.isSuccess) {
                updateNotification(getString(R.string.notif_status_screen_off_active, settings.preset.name))
            } else {
                updateNotification(getString(R.string.notif_status_apply_error))
            }
        }
    }

    /**
     * Triggered when screen turns ON:
     * Cancels any pending screen-off delay and restores the screen-on profile (or original kernel snapshot).
     */
    private fun handleScreenOnEvent() {
        screenOffDelayJob?.cancel()
        serviceScope.launch {
            // The app freeze and the 2G network guard are independent of the CPU profile, so they must be
            // released even when the screen-off settings were changed or disabled while the display was off.
            runCatching { appFreezer.unfreezeAll() }
            runCatching { networkModeController.restoreDefaultIfActive() }

            val safeMode = preferences.safeModeActiveFlow.first()
            if (safeMode) return@launch

            val settings = preferences.screenOffSettingsFlow.first()
            val savedScreenOn = preferences.screenOnConfigsFlow.first()
            if (!settings.enabled && !preScreenOffCaptured && savedScreenOn.isEmpty()) return@launch

            // P2 (perubahan.md): the user can choose between restoring the exact pre-screen-off kernel
            // state and always applying a fixed Screen-On configuration.
            val restored = applyMutex.withLock {
                when (settings.screenOnRestoreMode) {
                    ScreenOnRestoreMode.APPLY_SCREEN_ON_CONFIG -> {
                        if (savedScreenOn.isNotEmpty()) {
                            repository.applyClusterConfigs(
                                configs = savedScreenOn,
                                trigger = TriggerType.SCREEN_ON,
                                profileName = "Screen-On Config"
                            ).isSuccess
                        } else {
                            repository.undoLastApply(TriggerType.SCREEN_ON).isSuccess
                        }
                    }
                    ScreenOnRestoreMode.RESTORE_PREVIOUS_SNAPSHOT -> {
                        repository.undoLastApply(TriggerType.SCREEN_ON).isSuccess
                    }
                }
            }
            preScreenOffCaptured = false
            updateNotification(
                getString(
                    if (restored) R.string.notif_status_monitoring else R.string.notif_status_apply_error
                )
            )
        }
    }

    /**
     * Fast loop (2–5 s while the screen is ON, cheaper while OFF) for the two features that need low
     * latency: the manual thermal limit (step-down + hysteresis) and per-app governors.
     */
    private fun startThermalAndPerAppLoop() {
        thermalMonitorJob?.cancel()
        thermalMonitorJob = serviceScope.launch {
            while (isActive) {
                runCatching { evaluateThermalGuard() }
                if (isInteractiveScreenOn()) {
                    runCatching { evaluatePerAppGovernor() }
                }
                delay(
                    if (isInteractiveScreenOn()) ThermalGuardConfig.SCREEN_ON_POLL_MS
                    else ThermalGuardConfig.SCREEN_OFF_POLL_MS
                )
            }
        }
    }

    /**
     * Manual thermal guard: lowers `scaling_max_freq` one step per tick above the user limit, holds
     * inside the hysteresis band, and climbs back to the captured baseline once the device cooled down.
     */
    private suspend fun evaluateThermalGuard() = applyMutex.withLock {
        if (preferences.safeModeActiveFlow.first()) {
            // Safe mode must never leave a lowered ceiling behind.
            val baseline = preferences.thermalBaselineFlow.first()
            if (baseline.isNotEmpty()) {
                repository.applyThermalCeilings(baseline, ThermalGuardConfig.DEFAULT_LIMIT_CELSIUS)
                preferences.saveThermalBaseline(emptyMap())
                preferences.setThermalThrottleActive(false)
                thermalAlertShown = false
            }
            return@withLock
        }

        val triggerSettings = preferences.triggerSettingsFlow.first()
        val storedBaseline = preferences.thermalBaselineFlow.first()

        if (!triggerSettings.thermalGuardEnabled) {
            if (storedBaseline.isNotEmpty()) {
                repository.applyThermalCeilings(storedBaseline, triggerSettings.thermalLimitCelsius)
                preferences.saveThermalBaseline(emptyMap())
                preferences.setThermalThrottleActive(false)
                thermalAlertShown = false
                updateNotification(getString(R.string.notif_status_thermal_normal))
            }
            return@withLock
        }

        val clusters = repository.getClusters()
        if (clusters.isEmpty()) return@withLock
        val temperature = repository.getMaxCpuTemperatureCelsius()
        val decision = ThermalThrottlePlanner.decide(
            temperatureCelsius = temperature,
            limitCelsius = triggerSettings.thermalLimitCelsius,
            currentMaxByPolicy = clusters.associate { it.policyId to it.maxFreqKhz },
            frequenciesByPolicy = clusters.associate { it.policyId to it.availableFrequenciesKhz },
            baselineMaxByPolicy = storedBaseline
        )

        when (decision.action) {
            ThermalAction.IDLE -> {
                // A cluster already at the safe floor cannot step down any further, but the episode is
                // still active: persist the captured baseline so a restart can always release it.
                if (decision.throttling && storedBaseline.isEmpty() && decision.baselineMaxByPolicy.isNotEmpty()) {
                    preferences.saveThermalBaseline(decision.baselineMaxByPolicy)
                }
                // Keep the persisted flag honest after a service restart in the middle of an episode.
                if (decision.throttling != preferences.thermalThrottleActiveFlow.first()) {
                    preferences.setThermalThrottleActive(decision.throttling)
                }
            }
            ThermalAction.STEP_DOWN -> {
                preferences.saveThermalBaseline(decision.baselineMaxByPolicy)
                preferences.setThermalThrottleActive(true)
                repository.applyThermalCeilings(
                    decision.targetMaxByPolicy,
                    temperature ?: triggerSettings.thermalLimitCelsius
                )
                if (!thermalAlertShown && temperature != null) {
                    thermalAlertShown = true
                    postThermalThrottleNotification(temperature, triggerSettings.thermalLimitCelsius)
                }
                updateNotification(
                    getString(
                        R.string.notif_status_thermal_throttling,
                        temperature ?: 0f,
                        triggerSettings.thermalLimitCelsius
                    )
                )
            }
            ThermalAction.STEP_UP -> {
                repository.applyThermalCeilings(
                    decision.targetMaxByPolicy,
                    temperature ?: triggerSettings.thermalLimitCelsius
                )
            }
            ThermalAction.RESTORE -> {
                val outcome = repository.applyThermalCeilings(
                    decision.targetMaxByPolicy,
                    temperature ?: triggerSettings.thermalLimitCelsius
                )
                if (outcome.isSuccess) {
                    preferences.saveThermalBaseline(emptyMap())
                    preferences.setThermalThrottleActive(false)
                    thermalAlertShown = false
                    updateNotification(getString(R.string.notif_status_thermal_normal))
                }
            }
        }
    }

    /**
     * Applies the governor configured for the foreground app and restores the global governors when the
     * user leaves it. Debounced so fast app switching never produces repeated sysfs writes.
     * Priority: Screen-Off profile > per-app governor > global governor.
     */
    private suspend fun evaluatePerAppGovernor() = applyMutex.withLock {
        if (preferences.safeModeActiveFlow.first()) return@withLock
        val pauseUntil = preferences.pauseUntilEpochMsFlow.first()
        if (System.currentTimeMillis() < pauseUntil) return@withLock

        val settings: TriggerSettings = preferences.triggerSettingsFlow.first()
        val foreground = if (settings.perAppTriggerEnabled) {
            foregroundAppDetector.getForegroundPackageName()
        } else null

        val now = System.currentTimeMillis()
        if (foreground != observedForegroundPkg) {
            observedForegroundPkg = foreground
            observedForegroundAtMs = now
            // Wait for the app to settle before writing anything (debounce).
            if (settings.perAppTriggerEnabled) return@withLock
        }

        val decision = GovernorPriorityResolver.resolve(
            GovernorPriorityInput(
                isScreenOff = !isInteractiveScreenOn(),
                perAppEnabled = settings.perAppTriggerEnabled,
                foregroundPackageName = foreground,
                foregroundPerAppGovernor = foreground?.let { settings.perAppGovernors[it] },
                activePerAppPackage = activePerAppPackage
            )
        )

        when (decision) {
            is GovernorDecision.ApplyPerApp -> {
                if (now - observedForegroundAtMs < PER_APP_SETTLE_MS) return@withLock
                if (now - lastPerAppWriteMs < PER_APP_MIN_WRITE_INTERVAL_MS) return@withLock
                lastPerAppWriteMs = now
                val outcome = repository.applyGovernor(
                    governor = decision.governor,
                    trigger = TriggerType.PER_APP,
                    profileName = "Per-App ${decision.packageName}",
                    capturePerAppBaseline = activePerAppPackage == null
                )
                if (outcome.isSuccess) {
                    activePerAppPackage = decision.packageName
                    updateNotification(
                        getString(R.string.notif_status_per_app_governor, decision.governor, decision.packageName)
                    )
                } else {
                    // Never fail silently: keep the global governors and warn the user.
                    updateNotification(getString(R.string.notif_status_per_app_failed))
                }
            }
            GovernorDecision.RestoreGlobal -> {
                if (now - lastPerAppWriteMs < PER_APP_MIN_WRITE_INTERVAL_MS) return@withLock
                lastPerAppWriteMs = now
                repository.restoreGlobalGovernors(TriggerType.PER_APP, "Per-App Restore (Global)")
                activePerAppPackage = null
                updateNotification(getString(R.string.notif_status_monitoring))
            }
            GovernorDecision.None -> Unit
        }
    }

    /**
     * Safety net for the screen-off network guard. `ACTION_SCREEN_ON` only reaches a live, dynamically
     * registered receiver, and OEM power management can freeze or kill this process while the display
     * is off. Without this the radio can stay on 2G after screen-on until the app is opened again.
     */
    private suspend fun watchNetworkGuard() {
        if (!isInteractiveScreenOn()) return
        val now = System.currentTimeMillis()
        if (now - lastNetworkRestoreAttemptMs < NETWORK_RESTORE_RETRY_MS) return
        val attempt = runCatching { networkModeController.restoreDefaultIfActive() }
        if (attempt.getOrNull() != null) lastNetworkRestoreAttemptMs = now
    }

    /**
     * Periodically monitors the battery saver trigger, legacy per-app profiles, and time schedules
     * while respecting strict priority ordering (thermal is handled by its own fast loop above).
     */
    private fun startPeriodicTriggerLoop() {
        periodicMonitorJob?.cancel()
        periodicMonitorJob = serviceScope.launch {
            while (isActive) {
                delay(10_000L) // Check every 10 seconds
                evaluateBackgroundTriggers()
            }
        }
    }

    private suspend fun evaluateBackgroundTriggers() {
        watchNetworkGuard()

        val safeMode = preferences.safeModeActiveFlow.first()
        if (safeMode) return

        val pauseUntil = preferences.pauseUntilEpochMsFlow.first()
        if (System.currentTimeMillis() < pauseUntil) return

        val screenOffSettings = preferences.screenOffSettingsFlow.first()
        val triggerSettings = preferences.triggerSettingsFlow.first()
        // Thermal protection runs in its own fast loop (`evaluateThermalGuard`): it steps the ceiling
        // down gradually and keeps it applied, so this loop only needs the temperature for priority.
        val maxTemp = repository.getMaxCpuTemperatureCelsius()

        // Only evaluate Per-App / Battery / Schedule when screen is ON (when screen is OFF, Screen-Off handler manages it)
        if (!isInteractiveScreenOn()) return

        val now = Calendar.getInstance()
        val resolution = TriggerPriorityResolver.resolve(
            TriggerEvaluationInput(
                isSafeModeActive = safeMode,
                currentMaxTempCelsius = maxTemp,
                thermalThresholdCelsius = triggerSettings.thermalLimitCelsius,
                hasActiveManualOverride = false,
                isScreenOff = false,
                isScreenOffProfileEnabled = screenOffSettings.enabled,
                isCharging = isDeviceCharging(),
                skipScreenOffWhenCharging = screenOffSettings.skipWhenCharging,
                isMediaPlaying = isAudioPlaybackActive(),
                skipScreenOffWhenMediaPlaying = screenOffSettings.skipWhenMediaPlaying,
                foregroundPackageName = if (triggerSettings.perAppTriggerEnabled) {
                    foregroundAppDetector.getForegroundPackageName()
                } else null,
                perAppTriggerEnabled = triggerSettings.perAppTriggerEnabled,
                configuredPerAppPackages = triggerSettings.perAppProfiles.keys,
                batteryTriggerEnabled = triggerSettings.batteryTriggerEnabled,
                batteryLevelPercent = getBatteryLevelPercent(),
                batteryThresholdPercent = triggerSettings.batteryThresholdPercent,
                applySaverOnCharging = triggerSettings.applySaverOnCharging,
                scheduleTriggerEnabled = triggerSettings.scheduleTriggerEnabled,
                currentHour = now.get(Calendar.HOUR_OF_DAY),
                currentMinute = now.get(Calendar.MINUTE),
                scheduleStartHour = triggerSettings.scheduleStartHour,
                scheduleStartMinute = triggerSettings.scheduleStartMinute,
                scheduleEndHour = triggerSettings.scheduleEndHour,
                scheduleEndMinute = triggerSettings.scheduleEndMinute
            )
        )

        applyMutex.withLock {
            when (resolution.winningTrigger) {
                TriggerType.PER_APP -> {
                    val pkg = resolution.matchedAppPackage ?: return
                    val profileId = triggerSettings.perAppProfiles[pkg] ?: return
                    val profile = preferences.savedProfilesFlow.first().firstOrNull { it.id == profileId } ?: return
                    repository.applyClusterConfigs(
                        configs = profile.clusterConfigs,
                        trigger = TriggerType.PER_APP,
                        profileName = "${profile.name} ($pkg)"
                    )
                }
                TriggerType.BATTERY_LOW, TriggerType.SCHEDULE -> {
                    val clusters = repository.getClusters()
                    val saverConfigs = clusters.associate { cluster ->
                        cluster.policyId to SafeFrequencyPlanner.buildPresetConfigForCluster(
                            cluster,
                            PresetType.BATTERY_SAVER
                        )
                    }
                    repository.applyClusterConfigs(
                        configs = saverConfigs,
                        trigger = resolution.winningTrigger,
                        profileName = resolution.winningTrigger.displayLabelEn
                    )
                }
                else -> Unit
            }
        }
    }


    private fun isInteractiveScreenOn(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        return pm?.isInteractive ?: true
    }

    private fun isDeviceCharging(): Boolean {
        val bm = getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        return bm?.isCharging ?: false
    }

    private fun getBatteryLevelPercent(): Int {
        val bm = getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        return bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 100
    }

    private fun isAudioPlaybackActive(): Boolean {
        val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        return am?.isMusicActive ?: false
    }

    /** Notification channels exist on every API level CoolMax supports (minSdk 26). */
    private fun createNotificationChannels() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val guardChannel = NotificationChannel(
            CHANNEL_ID_GUARD,
            getString(R.string.notif_channel_guard_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notif_channel_guard_desc)
            setShowBadge(false)
        }
        val alertChannel = NotificationChannel(
            CHANNEL_ID_ALERT,
            getString(R.string.notif_channel_alert_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = getString(R.string.notif_channel_alert_desc)
        }
        nm.createNotificationChannel(guardChannel)
        nm.createNotificationChannel(alertChannel)
    }

    private fun buildGuardNotification(statusText: String): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java)
        val openAppPending = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val pauseIntent = Intent(this, ScreenOffGuardService::class.java).apply {
            action = ACTION_PAUSE_ONE_HOUR
        }
        val pausePending = PendingIntent.getService(
            this,
            1,
            pauseIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val resetIntent = Intent(this, ScreenOffGuardService::class.java).apply {
            action = ACTION_RESET_DEFAULTS
        }
        val resetPending = PendingIntent.getService(
            this,
            2,
            resetIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID_GUARD)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(statusText)
            .setContentIntent(openAppPending)
            .setOngoing(true)
            .addAction(
                android.R.drawable.ic_media_pause,
                getString(R.string.notif_action_pause_1h),
                pausePending
            )
            .addAction(
                android.R.drawable.ic_menu_revert,
                getString(R.string.notif_action_reset),
                resetPending
            )
            .build()
    }

    private fun updateNotification(statusText: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID_GUARD, buildGuardNotification(statusText))
    }

    private fun postThermalThrottleNotification(currentTemp: Float, limitTemp: Float) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = NotificationCompat.Builder(this, CHANNEL_ID_ALERT)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(getString(R.string.thermal_throttle_alert_title))
            .setContentText(
                getString(R.string.thermal_throttle_alert_body, currentTemp, limitTemp)
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIFICATION_ID_THERMAL_ALERT, notification)
    }

    private fun startForegroundWithCompatibility(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID_GUARD,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID_GUARD, notification)
        }
    }

    override fun onDestroy() {
        screenOffDelayJob?.cancel()
        periodicMonitorJob?.cancel()
        thermalMonitorJob?.cancel()
        unregisterDynamicScreenReceiver()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
