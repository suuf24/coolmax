package id.coolmax.app.domain.model

import kotlinx.serialization.Serializable

/**
 * Tier classification for CPU clusters dynamically inferred by sorting max hardware frequencies.
 */
@Serializable
enum class ClusterTier {
    LITTLE,
    BIG,
    PRIME,
    SINGLE
}

/**
 * Time-in-state entry representing how long a cluster spent at a given frequency.
 * @property frequencyKhz Frequency in kHz
 * @property timeMs Duration in milliseconds (converted from kernel 10ms jiffies/ticks)
 */
@Serializable
data class TimeInStateEntry(
    val frequencyKhz: Long,
    val timeMs: Long
)

/**
 * Complete live state of a CPU cluster (policy0, policy4, policy7, or fallback cpuX).
 */
@Serializable
data class CpuCluster(
    val policyId: Int,
    val policyPath: String,
    val tier: ClusterTier,
    val affectedCpus: List<Int>,
    val relatedCpus: List<Int>,
    val onlineCpus: List<Int>,
    val currentFreqKhz: Long,
    val minFreqKhz: Long,
    val maxFreqKhz: Long,
    val cpuinfoMinFreqKhz: Long,
    val cpuinfoMaxFreqKhz: Long,
    val activeGovernor: String,
    val availableFrequenciesKhz: List<Long>,
    val availableGovernors: List<String>,
    val timeInState: List<TimeInStateEntry> = emptyList()
) {
    val displayName: String
        get() = when (tier) {
            ClusterTier.SINGLE -> "CPU Policy $policyId"
            ClusterTier.LITTLE -> "Little Cluster (policy$policyId)"
            ClusterTier.BIG -> "Big Cluster (policy$policyId)"
            ClusterTier.PRIME -> "Prime Cluster (policy$policyId)"
        }
}

/**
 * User-desired or profile-defined configuration for a single CPU cluster.
 */
@Serializable
data class ClusterConfig(
    val policyId: Int,
    val minFreqKhz: Long,
    val maxFreqKhz: Long,
    val governor: String,
    val offlineCores: List<Int> = emptyList()
)

/**
 * Snapshot of all clusters' original or previous kernel settings for safe rollback/undo.
 */
@Serializable
data class KernelSnapshot(
    val capturedAtEpochMs: Long,
    val label: String,
    val clusterConfigs: Map<Int, ClusterConfig>
)

/**
 * Preset types for Screen-Off & quick profile switching.
 */
@Serializable
enum class PresetType {
    BATTERY_SAVER,
    BALANCED,
    CUSTOM
}

/**
 * Trigger types ordered by strict safety priority:
 * SAFE_MODE > HIGH_TEMPERATURE > MANUAL > PER_APP > SCREEN_OFF > BATTERY_LOW > SCHEDULE > DEFAULT
 *
 * `SCREEN_ON` is an *action* label (not an evaluator outcome): it marks the "Screen-On Restore"
 * apply so the event log no longer mislabels restore events as `SCREEN_OFF`.
 */
@Serializable
enum class TriggerType(val priority: Int, val displayLabelId: String, val displayLabelEn: String) {
    SAFE_MODE(100, "Mode Aman (Darurat)", "Safe Mode (Emergency)"),
    HIGH_TEMPERATURE(90, "Perlindungan Suhu Tinggi", "High Temp Protection"),
    MANUAL(80, "Kontrol Manual", "Manual Control"),
    PER_APP(70, "Profil Per-Aplikasi", "Per-App Profile"),
    SCREEN_OFF(60, "Profil Layar Mati", "Screen-Off Profile"),
    BATTERY_LOW(55, "Hemat Baterai (<X%)", "Low Battery Saver"),
    SCHEDULE(50, "Jadwal Waktu", "Time Schedule"),
    SCREEN_ON(45, "Pemulihan Layar Nyala", "Screen-On Restore"),
    SYSTEM_DEFAULT(10, "Default Sistem", "System Default")
}

/**
 * Complete named CPU profile that can be saved, duplicated, edited, deleted, or exported/imported as JSON.
 */
@Serializable
data class CpuProfile(
    val id: String,
    val name: String,
    val iconName: String = "speed",
    val presetType: PresetType = PresetType.CUSTOM,
    val clusterConfigs: Map<Int, ClusterConfig>,
    val createdAtEpochMs: Long = System.currentTimeMillis()
)

/**
 * Screen-off specific preferences.
 */
@Serializable
enum class ScreenOnRestoreMode {
    /** Restore the exact kernel state captured right before the screen went off (default; recommended). */
    RESTORE_PREVIOUS_SNAPSHOT,
    /** Always apply the user-defined screen-on configuration stored in `screen_on_config_json` (e.g. a performance profile). */
    APPLY_SCREEN_ON_CONFIG
}

/**
 * Screen-off specific preferences.
 */
@Serializable
data class ScreenOffSettings(
    val enabled: Boolean = false, // Fail-safe default OFF
    val preset: PresetType = PresetType.BATTERY_SAVER,
    val delaySeconds: Int = 5, // 0..60s, default 5s
    val skipWhenCharging: Boolean = true,
    val skipWhenMediaPlaying: Boolean = true,
    /**
     * Also force the cellular network to 2G only (GSM) while the screen is off and restore the
     * previous/default network mode when the screen turns on. Fail-safe default OFF.
     */
    val network2gOnScreenOff: Boolean = false,
    /**
     * Freeze every running third-party app while the screen is off (thawed on screen-on), except the
     * packages in [freezeWhitelist]. Fail-safe default OFF.
     */
    val freezeAppsOnScreenOff: Boolean = false,
    /** Packages that stay running (never frozen) while the screen-off freeze is active. */
    val freezeWhitelist: Set<String> = emptySet(),
    val customClusterConfigs: Map<Int, ClusterConfig> = emptyMap(),
    val screenOnRestoreMode: ScreenOnRestoreMode = ScreenOnRestoreMode.RESTORE_PREVIOUS_SNAPSHOT
)

/**
 * Additional automatic trigger settings (all default OFF).
 */
@Serializable
data class TriggerSettings(
    val batteryTriggerEnabled: Boolean = false,
    val batteryThresholdPercent: Int = 20,
    val applySaverOnCharging: Boolean = false,
    val scheduleTriggerEnabled: Boolean = false,
    val scheduleStartHour: Int = 23,
    val scheduleStartMinute: Int = 0,
    val scheduleEndHour: Int = 6,
    val scheduleEndMinute: Int = 0,
    val perAppTriggerEnabled: Boolean = false,
    val perAppProfiles: Map<String, String> = emptyMap(), // packageName -> profileId (legacy)
    /** Per-app governor mapping: `packageName -> governor` (see `PerAppGovernorRule`). */
    val perAppGovernors: Map<String, String> = emptyMap(),
    /**
     * Configurable CPU thermal guard.
     * Default 70 °C (per ubah.md: 45 °C was too low for real devices like Helio G95, where
     * normal operating range is 50–75 °C, causing near-constant false `HIGH_TEMPERATURE` blocks).
     */
    val thermalLimitCelsius: Float = 70.0f,
    /** Master switch for the step-down thermal guard. Default OFF (fail-safe). */
    val thermalGuardEnabled: Boolean = false
)

/**
 * Thermal zone reading from /sys/class/thermal/thermal_zone*
 */
@Serializable
data class ThermalSensorReading(
    val zoneName: String,
    val type: String,
    val tempCelsius: Float
)

/**
 * Read-only hardware & OS information for Device Info screen.
 */
@Serializable
data class DeviceInfo(
    val socModel: String,
    val boardPlatform: String,
    val clusterCount: Int,
    val totalCores: Int,
    val availableGovernors: List<String>,
    val kernelVersion: String,
    val isRooted: Boolean,
    val rootProvider: String,
    val selinuxStatus: String
)

/**
 * Structured event log entry for profile applications, thermal overrides, and errors.
 */
@Serializable
data class EventLogEntry(
    val id: String,
    val timestampEpochMs: Long,
    val trigger: TriggerType,
    val profileName: String,
    val success: Boolean,
    val message: String,
    val commandsExecuted: List<String> = emptyList()
)
