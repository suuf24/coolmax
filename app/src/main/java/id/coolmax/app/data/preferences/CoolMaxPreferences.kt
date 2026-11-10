package id.coolmax.app.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import id.coolmax.app.domain.model.ClusterConfig
import id.coolmax.app.domain.model.CpuProfile
import id.coolmax.app.domain.model.EventLogEntry
import id.coolmax.app.domain.model.KernelSnapshot
import id.coolmax.app.domain.model.ScreenOffSettings
import id.coolmax.app.domain.model.TriggerSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "coolmax_prefs")

/**
 * Persistent preferences & safety state backed by Jetpack DataStore.
 *
 * All auto-apply toggles default strictly to OFF (fail-safe by default).
 * Manages:
 * - First-run original kernel snapshot (`initial_snapshot_json`)
 * - Last pre-apply snapshot for single-tap Undo (`previous_snapshot_json`)
 * - Bootloop protection flag (`pending_apply`, `pending_apply_timestamp`, `safe_mode_active`, `safe_mode_reason`)
 * - Screen-off settings (`screen_off_settings_json`)
 * - Trigger settings (`trigger_settings_json`)
 * - Saved custom profiles (`saved_profiles_json`)
 * - Local event logs (`event_logs_json`, capped at 100 entries, exportable)
 * - Developer dry-run simulation mode (`dry_run_mode`)
 * - Screen-off 2G network guard state (`network_mode_baseline_json`, `network_mode_2g_active`,
 *   `network_radio_refresh_pending`)
 */
class CoolMaxPreferences(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    companion object {
        val KEY_INITIAL_SNAPSHOT = stringPreferencesKey("initial_snapshot_json")
        val KEY_PREVIOUS_SNAPSHOT = stringPreferencesKey("previous_snapshot_json")
        val KEY_SCREEN_ON_CONFIG = stringPreferencesKey("screen_on_config_json")
        val KEY_SCREEN_OFF_SETTINGS = stringPreferencesKey("screen_off_settings_json")
        val KEY_TRIGGER_SETTINGS = stringPreferencesKey("trigger_settings_json")
        val KEY_SAVED_PROFILES = stringPreferencesKey("saved_profiles_json")
        val KEY_EVENT_LOGS = stringPreferencesKey("event_logs_json")

        // Thermal guard state (survives service restarts so throttling can always be released)
        val KEY_THERMAL_BASELINE = stringPreferencesKey("thermal_baseline_max_json")
        val KEY_THERMAL_THROTTLE_ACTIVE = booleanPreferencesKey("thermal_throttle_active")

        // Governors captured right before the first per-app governor apply
        val KEY_PER_APP_BASELINE = stringPreferencesKey("per_app_baseline_governors_json")

        // Screen-off network guard (2G only): previous network mode values + in-flight state
        val KEY_NETWORK_MODE_BASELINE = stringPreferencesKey("network_mode_baseline_json")
        val KEY_NETWORK_MODE_2G_ACTIVE = booleanPreferencesKey("network_mode_2g_active")
        val KEY_NETWORK_ALLOWED_TYPES = stringPreferencesKey("network_allowed_types_baseline_json")
        val KEY_NETWORK_RADIO_REFRESH_PENDING = booleanPreferencesKey("network_radio_refresh_pending")

        // Screen-off app freeze: UIDs currently frozen (persisted BEFORE freezing, cleared on thaw)
        val KEY_FROZEN_UIDS = stringPreferencesKey("screen_off_frozen_uids")

        // Safety & Bootloop Protection Flags
        val KEY_PENDING_APPLY = booleanPreferencesKey("pending_apply")
        val KEY_PENDING_APPLY_TIMESTAMP = longPreferencesKey("pending_apply_timestamp")
        val KEY_SAFE_MODE_ACTIVE = booleanPreferencesKey("safe_mode_active")
        val KEY_SAFE_MODE_REASON = stringPreferencesKey("safe_mode_reason")
        val KEY_DRY_RUN_MODE = booleanPreferencesKey("dry_run_mode")
        val KEY_PAUSE_UNTIL_EPOCH_MS = longPreferencesKey("pause_until_epoch_ms")
    }

    val initialSnapshotFlow: Flow<KernelSnapshot?> = context.dataStore.data.map { prefs ->
        prefs[KEY_INITIAL_SNAPSHOT]?.let { raw ->
            runCatching { json.decodeFromString<KernelSnapshot>(raw) }.getOrNull()
        }
    }

    val previousSnapshotFlow: Flow<KernelSnapshot?> = context.dataStore.data.map { prefs ->
        prefs[KEY_PREVIOUS_SNAPSHOT]?.let { raw ->
            runCatching { json.decodeFromString<KernelSnapshot>(raw) }.getOrNull()
        }
    }

    val screenOnConfigsFlow: Flow<Map<Int, ClusterConfig>> = context.dataStore.data.map { prefs ->
        prefs[KEY_SCREEN_ON_CONFIG]?.let { raw ->
            runCatching { json.decodeFromString<Map<Int, ClusterConfig>>(raw) }.getOrNull()
        } ?: emptyMap()
    }

    val screenOffSettingsFlow: Flow<ScreenOffSettings> = context.dataStore.data.map { prefs ->
        prefs[KEY_SCREEN_OFF_SETTINGS]?.let { raw ->
            runCatching { json.decodeFromString<ScreenOffSettings>(raw) }.getOrNull()
        } ?: ScreenOffSettings() // Default: enabled = false
    }

    val triggerSettingsFlow: Flow<TriggerSettings> = context.dataStore.data.map { prefs ->
        prefs[KEY_TRIGGER_SETTINGS]?.let { raw ->
            runCatching { json.decodeFromString<TriggerSettings>(raw) }.getOrNull()
        } ?: TriggerSettings() // Default: all triggers false
    }

    val savedProfilesFlow: Flow<List<CpuProfile>> = context.dataStore.data.map { prefs ->
        prefs[KEY_SAVED_PROFILES]?.let { raw ->
            runCatching { json.decodeFromString<List<CpuProfile>>(raw) }.getOrNull()
        } ?: emptyList()
    }

    /** Original per-policy maximum frequencies captured when a thermal episode starts. */
    val thermalBaselineFlow: Flow<Map<Int, Long>> = context.dataStore.data.map { prefs ->
        prefs[KEY_THERMAL_BASELINE]?.let { raw ->
            runCatching { json.decodeFromString<Map<Int, Long>>(raw) }.getOrNull()
        } ?: emptyMap()
    }

    val thermalThrottleActiveFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_THERMAL_THROTTLE_ACTIVE] ?: false
    }

    /** Governors captured before the first per-app governor apply, used to restore the global state. */
    val perAppBaselineFlow: Flow<Map<Int, String>> = context.dataStore.data.map { prefs ->
        prefs[KEY_PER_APP_BASELINE]?.let { raw ->
            runCatching { json.decodeFromString<Map<Int, String>>(raw) }.getOrNull()
        } ?: emptyMap()
    }

    /** `settings` key -> previous value captured before forcing 2G only (blank = key was absent). */
    val networkModeBaselineFlow: Flow<Map<String, String>> = context.dataStore.data.map { prefs ->
        prefs[KEY_NETWORK_MODE_BASELINE]?.let { raw ->
            runCatching { json.decodeFromString<Map<String, String>>(raw) }.getOrNull()
        } ?: emptyMap()
    }

    /** SIM slot -> allowed network types (names) captured before forcing 2G only. */
    val networkAllowedTypesBaselineFlow: Flow<Map<Int, String>> = context.dataStore.data.map { prefs ->
        prefs[KEY_NETWORK_ALLOWED_TYPES]?.let { raw ->
            runCatching { json.decodeFromString<Map<Int, String>>(raw) }.getOrNull()
        } ?: emptyMap()
    }

    /** True while the screen-off profile is keeping the cellular radio on 2G only. */
    val networkMode2gActiveFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_NETWORK_MODE_2G_ACTIVE] ?: false
    }

    /** True while an airplane-mode radio refresh is running (used to recover a crash mid-switch). */
    val networkRadioRefreshPendingFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_NETWORK_RADIO_REFRESH_PENDING] ?: false
    }

    /** UIDs frozen by the screen-off app guard; empty when nothing is frozen. */
    val frozenUidsFlow: Flow<Set<Int>> = context.dataStore.data.map { prefs ->
        prefs[KEY_FROZEN_UIDS]
            ?.split(',')
            ?.mapNotNull { it.trim().toIntOrNull() }
            ?.toSet()
            ?: emptySet()
    }

    val eventLogsFlow: Flow<List<EventLogEntry>> = context.dataStore.data.map { prefs ->
        prefs[KEY_EVENT_LOGS]?.let { raw ->
            runCatching { json.decodeFromString<List<EventLogEntry>>(raw) }.getOrNull()
        } ?: emptyList()
    }

    val pendingApplyFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_PENDING_APPLY] ?: false
    }

    val safeModeActiveFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_SAFE_MODE_ACTIVE] ?: false
    }

    val safeModeReasonFlow: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[KEY_SAFE_MODE_REASON]
    }

    val dryRunModeFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_DRY_RUN_MODE] ?: false
    }

    val pauseUntilEpochMsFlow: Flow<Long> = context.dataStore.data.map { prefs ->
        prefs[KEY_PAUSE_UNTIL_EPOCH_MS] ?: 0L
    }

    suspend fun saveInitialSnapshotIfNeeded(snapshot: KernelSnapshot) {
        context.dataStore.edit { prefs ->
            if (prefs[KEY_INITIAL_SNAPSHOT] == null) {
                prefs[KEY_INITIAL_SNAPSHOT] = json.encodeToString(snapshot)
            }
        }
    }

    suspend fun savePreviousSnapshot(snapshot: KernelSnapshot) {
        context.dataStore.edit { prefs ->
            prefs[KEY_PREVIOUS_SNAPSHOT] = json.encodeToString(snapshot)
        }
    }

    suspend fun saveScreenOnConfigs(configs: Map<Int, ClusterConfig>) {
        context.dataStore.edit { prefs ->
            prefs[KEY_SCREEN_ON_CONFIG] = json.encodeToString(configs)
        }
    }

    suspend fun updateScreenOffSettings(settings: ScreenOffSettings) {
        context.dataStore.edit { prefs ->
            prefs[KEY_SCREEN_OFF_SETTINGS] = json.encodeToString(settings)
        }
    }

    suspend fun updateTriggerSettings(settings: TriggerSettings) {
        context.dataStore.edit { prefs ->
            prefs[KEY_TRIGGER_SETTINGS] = json.encodeToString(settings)
        }
    }

    suspend fun saveProfiles(profiles: List<CpuProfile>) {
        context.dataStore.edit { prefs ->
            prefs[KEY_SAVED_PROFILES] = json.encodeToString(profiles)
        }
    }

    suspend fun saveThermalBaseline(baseline: Map<Int, Long>) {
        context.dataStore.edit { prefs ->
            if (baseline.isEmpty()) prefs.remove(KEY_THERMAL_BASELINE)
            else prefs[KEY_THERMAL_BASELINE] = json.encodeToString(baseline)
        }
    }

    suspend fun setThermalThrottleActive(active: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_THERMAL_THROTTLE_ACTIVE] = active
        }
    }

    suspend fun savePerAppBaseline(governors: Map<Int, String>) {
        context.dataStore.edit { prefs ->
            if (governors.isEmpty()) prefs.remove(KEY_PER_APP_BASELINE)
            else prefs[KEY_PER_APP_BASELINE] = json.encodeToString(governors)
        }
    }

    suspend fun saveNetworkModeBaseline(baseline: Map<String, String>) {
        context.dataStore.edit { prefs ->
            if (baseline.isEmpty()) prefs.remove(KEY_NETWORK_MODE_BASELINE)
            else prefs[KEY_NETWORK_MODE_BASELINE] = json.encodeToString(baseline)
        }
    }

    suspend fun saveNetworkAllowedTypesBaseline(baseline: Map<Int, String>) {
        context.dataStore.edit { prefs ->
            if (baseline.isEmpty()) prefs.remove(KEY_NETWORK_ALLOWED_TYPES)
            else prefs[KEY_NETWORK_ALLOWED_TYPES] = json.encodeToString(baseline)
        }
    }

    suspend fun setNetworkMode2gActive(active: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_NETWORK_MODE_2G_ACTIVE] = active
        }
    }

    suspend fun saveFrozenUids(uids: Set<Int>) {
        context.dataStore.edit { prefs ->
            if (uids.isEmpty()) prefs.remove(KEY_FROZEN_UIDS)
            else prefs[KEY_FROZEN_UIDS] = uids.sorted().joinToString(",")
        }
    }

    suspend fun setNetworkRadioRefreshPending(pending: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_NETWORK_RADIO_REFRESH_PENDING] = pending
        }
    }

    suspend fun appendEventLog(entry: EventLogEntry) {
        context.dataStore.edit { prefs ->
            val current = prefs[KEY_EVENT_LOGS]?.let { raw ->
                runCatching { json.decodeFromString<List<EventLogEntry>>(raw) }.getOrNull()
            } ?: emptyList()
            val updated = (listOf(entry) + current).take(100)
            prefs[KEY_EVENT_LOGS] = json.encodeToString(updated)
        }
    }

    suspend fun clearEventLogs() {
        context.dataStore.edit { prefs ->
            prefs.remove(KEY_EVENT_LOGS)
        }
    }

    /**
     * Bootloop Protection: Sets `pending_apply = true` before writing to sysfs.
     * Cleared only after read-back verification succeeds and the system stays alive for 2 minutes.
     */
    suspend fun setPendingApply(pending: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_PENDING_APPLY] = pending
            if (pending) {
                prefs[KEY_PENDING_APPLY_TIMESTAMP] = System.currentTimeMillis()
            } else {
                prefs.remove(KEY_PENDING_APPLY_TIMESTAMP)
            }
        }
    }

    /**
     * Checks at app/boot startup if `pending_apply` was left true from an interrupted apply or crash/reboot.
     * If so, activates SAFE MODE and disables all auto-apply features immediately.
     */
    suspend fun checkAndEnterSafeModeIfPendingApplyLeftover(): Boolean {
        val prefs = context.dataStore.data.first()
        val wasPending = prefs[KEY_PENDING_APPLY] ?: false
        if (wasPending) {
            enterSafeMode(
                reason = "Detected unfinished profile application (pending_apply flag remained set after restart/boot). " +
                    "All automatic profiles have been disabled and original kernel defaults restored to prevent bootloop."
            )
            return true
        }
        return false
    }

    suspend fun enterSafeMode(reason: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_SAFE_MODE_ACTIVE] = true
            prefs[KEY_SAFE_MODE_REASON] = reason
            prefs[KEY_PENDING_APPLY] = false

            // Fail-safe: turn OFF all auto-apply toggles
            val currentScreenOff = prefs[KEY_SCREEN_OFF_SETTINGS]?.let {
                runCatching { json.decodeFromString<ScreenOffSettings>(it) }.getOrNull()
            } ?: ScreenOffSettings()
            prefs[KEY_SCREEN_OFF_SETTINGS] = json.encodeToString(currentScreenOff.copy(enabled = false))

            val currentTriggers = prefs[KEY_TRIGGER_SETTINGS]?.let {
                runCatching { json.decodeFromString<TriggerSettings>(it) }.getOrNull()
            } ?: TriggerSettings()
            prefs[KEY_TRIGGER_SETTINGS] = json.encodeToString(
                currentTriggers.copy(
                    batteryTriggerEnabled = false,
                    scheduleTriggerEnabled = false,
                    perAppTriggerEnabled = false,
                    thermalGuardEnabled = false
                )
            )
        }
    }

    suspend fun clearSafeMode() {
        context.dataStore.edit { prefs ->
            prefs[KEY_SAFE_MODE_ACTIVE] = false
            prefs.remove(KEY_SAFE_MODE_REASON)
            prefs[KEY_PENDING_APPLY] = false
        }
    }

    suspend fun setDryRunMode(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_DRY_RUN_MODE] = enabled
        }
    }

    suspend fun setPauseUntil(epochMs: Long) {
        context.dataStore.edit { prefs ->
            prefs[KEY_PAUSE_UNTIL_EPOCH_MS] = epochMs
        }
    }
}
