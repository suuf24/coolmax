package id.coolmax.app.data.network

import id.coolmax.app.data.preferences.CoolMaxPreferences
import id.coolmax.app.data.root.RootManager
import id.coolmax.app.domain.model.EventLogEntry
import id.coolmax.app.domain.model.TriggerType
import kotlinx.coroutines.flow.first
import java.util.UUID

/**
 * Screen-Off network guard: forces the cellular radio to **2G only (GSM)** while the display is off
 * and restores the previously active (default) network mode once the display comes back on.
 *
 * Two mechanisms are used:
 * 1. **Primary (Android 13+)**: `cmd phone set-allowed-network-types-for-users`, the same API the SIM
 *    settings screen calls (`TelephonyManager.setAllowedNetworkTypesForReason(USER)`). It reaches the
 *    modem immediately, so there is no airplane-mode cycle and no signal drop. The previous mask is
 *    captured per SIM slot first and restored on screen-on.
 * 2. **Fallback**: the global settings entries the telephony framework persists
 *    (`preferred_network_mode`, `preferred_network_mode<phoneId>`, `user_preferred_network_mode*`).
 *    RIL only re-reads those when the radio powers up, so this path is followed by a short
 *    airplane-mode cycle, which always ends with airplane mode off ([ensureAirplaneModeOff]).
 *
 * Every apply stores the previous state in DataStore *before* writing, so the exact default mode can
 * be restored even after a service restart or crash.
 */
class NetworkModeController(
    private val rootManager: RootManager,
    private val preferences: CoolMaxPreferences
) {

    companion object {
        /** `RILConstants.NETWORK_MODE_GSM_ONLY`, i.e. 2G only. */
        const val MODE_GSM_ONLY = "1"
        const val AIRPLANE_MODE_SETTING = "airplane_mode_on"
        const val RADIO_SETTLE_SECONDS = 2
        const val COMMAND_TIMEOUT_MS = 25_000L

        const val PROFILE_NAME_APPLY = "Screen-Off Network (2G only)"
        const val PROFILE_NAME_RESTORE = "Screen-On Network Restore"

        /** Sentinel stored for a key that did not exist before the switch, so restoring deletes it. */
        const val ABSENT_VALUE = ""

        const val NATIVE_COMMAND = "cmd phone set-allowed-network-types-for-users"

        /** `1L shl (NETWORK_TYPE_GSM - 1)` in binary, because the shell command takes a bitmask. */
        const val NATIVE_GSM_ONLY_BITMASK = "1000000000000000"

        /** Dual-SIM devices are covered by iterating the first two slots. */
        const val MAX_SIM_SLOTS = 2

        /**
         * The baseline is also mirrored into these global settings, so an app data wipe or a
         * reinstall cannot strand the radio on 2G with no way back.
         */
        const val BASELINE_MIRROR_KEY = "coolmax_network_baseline"
        const val ALLOWED_TYPES_MIRROR_KEY = "coolmax_network_allowed_types"

        /** `RILConstants.NETWORK_MODE_LTE_GSM_WCDMA`, the Android default, used when nothing survived. */
        const val SAFE_DEFAULT_MODE = "9"

        /** Types for [SAFE_DEFAULT_MODE]. The native command takes names, not the numeric constant. */
        const val SAFE_DEFAULT_TYPES = "GSM|UMTS|LTE"

        private val MODE_KEY_REGEX = Regex("^(user_)?preferred_network_mode[0-9]{0,3}$")
        private val MODE_VALUE_REGEX = Regex("^[0-9]{1,3}(,[0-9]{1,3}){0,16}$")

        /** `TelephonyManager.NETWORK_TYPE_*` values by the names its `getNetworkTypeName()` prints. */
        private val NETWORK_TYPE_BITS = mapOf(
            "GPRS" to 1, "EDGE" to 2, "UMTS" to 3, "CDMA" to 4, "EVDO_0" to 5, "EVDO_A" to 6,
            "1xRTT" to 7, "HSDPA" to 8, "HSUPA" to 9, "HSPA" to 10, "IDEN" to 11, "EVDO_B" to 12,
            "LTE" to 13, "EHRPD" to 14, "HSPA+" to 15, "GSM" to 16, "TD_SCDMA" to 17, "IWLAN" to 18,
            "LTE_CA" to 19, "NR" to 20
        )

        /**
         * Parses `settings list global` output into `key -> value` for the preferred network mode
         * entries only. Exposed for unit tests.
         */
        internal fun parseNetworkModeSettings(rawOutput: String): Map<String, String> =
            rawOutput.lineSequence()
                .mapNotNull { line ->
                    if (!line.contains('=')) return@mapNotNull null
                    val key = line.substringBefore('=').trim()
                    if (!MODE_KEY_REGEX.matches(key)) return@mapNotNull null
                    key to line.substringAfter('=').trim()
                }
                .toMap()

        /**
         * Builds the shell commands that put the previously captured values back. A blank value means
         * the key did not exist, so it is deleted again. Values that are not a numeric (comma
         * separated) RAT mask are skipped, so unvalidated settings never reach a shell string.
         */
        internal fun buildRestoreCommands(baseline: Map<String, String>): List<String> =
            baseline.entries
                .sortedBy { it.key }
                .mapNotNull { (key, value) ->
                    when {
                        value.isEmpty() -> "settings delete global $key"
                        MODE_VALUE_REGEX.matches(value) -> "settings put global $key $value"
                        else -> null
                    }
                }

        /** `GPRS|EDGE|...` (as printed by `cmd phone get-allowed-network-types-for-users`) -> binary mask. */
        internal fun allowedTypesToBitmask(names: String): String? {
            val types = names.split('|').map { it.trim() }.filter { it.isNotEmpty() }
            if (types.isEmpty()) return null
            var mask = 0L
            for (type in types) {
                val bit = NETWORK_TYPE_BITS[type] ?: return null
                mask = mask or (1L shl (bit - 1))
            }
            return if (mask == 0L) null else java.lang.Long.toBinaryString(mask)
        }

        /** Network type names that only exist on 2G. */
        private val GSM_FAMILY_TYPES = setOf("GSM", "GPRS", "EDGE")

        /**
         * True when [value] is the GSM-only mode (`1`), possibly in a comma separated list.
         *
         * Such a value is never a real user default: it is what an interrupted screen-off switch
         * left behind. Capturing it as the baseline is what made the screen-on restore write 2G
         * back and strand the phone on GSM.
         */
        internal fun isGsmOnlyModeValue(value: String): Boolean {
            val parts = value.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            return parts.isNotEmpty() && parts.all { it == MODE_GSM_ONLY }
        }

        /** True when [names] is a `get-allowed-network-types-for-users` value restricted to 2G. */
        internal fun isGsmOnlyTypes(names: String): Boolean {
            val tokens = names.split('|').map { it.trim() }.filter { it.isNotEmpty() }
            return tokens.isNotEmpty() && tokens.all { it in GSM_FAMILY_TYPES }
        }

        /** True when [line] is a `get-allowed-network-types-for-users` value (single name or `|` list). */
        internal fun isAllowedTypesValue(line: String): Boolean {
            val tokens = line.split('|').map { it.trim() }.filter { it.isNotEmpty() }
            return tokens.isNotEmpty() && tokens.all { NETWORK_TYPE_BITS.containsKey(it) }
        }

        private fun sameTypes(a: String, b: String): Boolean =
            a.split('|').map { it.trim() }.filter { it.isNotEmpty() }.sorted() ==
                b.split('|').map { it.trim() }.filter { it.isNotEmpty() }.sorted()

        /** `{preferred_network_mode=11;preferred_network_mode1=11}` for the global settings mirror. */
        internal fun encodeBaselineMirror(baseline: Map<String, String>): String =
            baseline.entries.sortedBy { it.key }.joinToString(";") { (key, value) -> "$key=$value" }

        /** Inverse of [encodeBaselineMirror]. Anything that is not a validated mode entry is dropped. */
        internal fun decodeBaselineMirror(raw: String): Map<String, String> =
            raw.split(';')
                .mapNotNull { entry ->
                    if (!entry.contains('=')) return@mapNotNull null
                    val key = entry.substringBefore('=').trim()
                    val value = entry.substringAfter('=').trim()
                    if (!MODE_KEY_REGEX.matches(key)) return@mapNotNull null
                    if (value.isNotEmpty() && !MODE_VALUE_REGEX.matches(value)) return@mapNotNull null
                    key to value
                }
                .toMap()

        /** `{0=LTE|LTE_CA;1=LTE|LTE_CA}` for the global settings mirror. */
        internal fun encodeAllowedTypesMirror(baseline: Map<Int, String>): String =
            baseline.entries.sortedBy { it.key }.joinToString(";") { (slot, names) -> "$slot=$names" }

        /** Inverse of [encodeAllowedTypesMirror]. Unknown slots and names are dropped. */
        internal fun decodeAllowedTypesMirror(raw: String): Map<Int, String> =
            raw.split(';')
                .mapNotNull { entry ->
                    if (!entry.contains('=')) return@mapNotNull null
                    val slot = entry.substringBefore('=').trim().toIntOrNull() ?: return@mapNotNull null
                    val names = entry.substringAfter('=').trim()
                    if (slot !in 0 until MAX_SIM_SLOTS || !isAllowedTypesValue(names)) return@mapNotNull null
                    slot to names
                }
                .toMap()
    }

    data class NetworkModeOutcome(
        val isSuccess: Boolean,
        val message: String,
        val commandsExecuted: List<String> = emptyList()
    )

    /** True while the device is being kept on 2G only by the screen-off profile. */
    suspend fun is2gModeActive(): Boolean = preferences.networkMode2gActiveFlow.first()

    /**
     * Switches every discovered SIM slot / preferred network mode entry to GSM-only and remembers the
     * previous values. No-op (baseline preserved) when 2G-only is already active.
     */
    suspend fun apply2gOnly(): NetworkModeOutcome {
        if (preferences.networkMode2gActiveFlow.first()) {
            return NetworkModeOutcome(
                isSuccess = true,
                message = "Network mode is already 2G only; the saved baseline was kept."
            )
        }

        val discovered = readCurrentModes()
        // Entries that are already GSM-only belong to an earlier, interrupted switch. Keeping them
        // would make the restore write 2G back, so they are not captured; the restore step resets
        // whatever is still 2G to the LTE capable default (see [healStaleGsmOnly]).
        val clean = discovered.filterValues { !isGsmOnlyModeValue(it) }
        // If the settings could not be enumerated, fall back to the legacy key and remember that it
        // did not exist so the restore step removes it again.
        val baseline = clean.ifEmpty { mapOf("preferred_network_mode" to ABSENT_VALUE) }
        val settingsWrites = baseline.keys.sorted().map { key ->
            "settings put global $key $MODE_GSM_ONLY"
        }

        // Primary mechanism: restrict the allowed network types per SIM slot. This is what the SIM
        // settings screen does, so MIUI/telephony apply it immediately instead of at the next boot.
        val allowedTypesBaseline = readAllowedNetworkTypes().filterValues { !isGsmOnlyTypes(it) }
        val nativeWrites = allowedTypesBaseline.keys.sorted().map { slot ->
            "$NATIVE_COMMAND -s $slot $NATIVE_GSM_ONLY_BITMASK"
        }
        val useNative = nativeWrites.isNotEmpty()
        val commands = nativeWrites + settingsWrites

        if (preferences.dryRunModeFlow.first()) {
            val message = "[DRY-RUN] Simulated '$PROFILE_NAME_APPLY' (${commands.size} shell writes)."
            logEvent(TriggerType.SCREEN_OFF, PROFILE_NAME_APPLY, true, message, commands)
            return NetworkModeOutcome(isSuccess = true, message = message, commandsExecuted = commands)
        }

        val airplaneWasOn = isAirplaneModeEnabled()
        preferences.saveNetworkModeBaseline(baseline)
        preferences.saveNetworkAllowedTypesBaseline(allowedTypesBaseline)
        mirrorBaseline(baseline, allowedTypesBaseline)
        if (!useNative) preferences.setNetworkRadioRefreshPending(!airplaneWasOn)

        val script = if (useNative) {
            commands.joinToString("; ")
        } else {
            buildRadioScript(commands, airplaneWasOn).joinToString("; ")
        }
        val result = rootManager.execCommand(script, COMMAND_TIMEOUT_MS)

        if (!useNative) {
            preferences.setNetworkRadioRefreshPending(false)
            if (!airplaneWasOn) ensureAirplaneModeOff()
        }

        val live = readCurrentModes()
        val appliedKeys = baseline.keys.filter { live[it] == MODE_GSM_ONLY }
        val mismatched = baseline.keys.filter { live[it] != MODE_GSM_ONLY }
        val liveAllowed = if (useNative) readAllowedNetworkTypes() else emptyMap()
        val nativeAppliedSlots = allowedTypesBaseline.keys.filter { slot ->
            liveAllowed[slot]?.let { sameTypes(it, "GSM") } == true
        }
        val nativeOk = useNative && nativeAppliedSlots.size == allowedTypesBaseline.keys.size

        // Keep the baseline while anything was written, so a failed attempt is undone on screen-on.
        if (nativeAppliedSlots.isNotEmpty() || appliedKeys.isNotEmpty()) {
            preferences.setNetworkMode2gActive(true)
        }

        val success = if (useNative) nativeOk && mismatched.isEmpty() else mismatched.isEmpty() && appliedKeys.isNotEmpty()

        val message = when {
            success && useNative ->
                "Cellular radio restricted to 2G only (GSM) on SIM slot(s) " +
                    "${nativeAppliedSlots.sorted().joinToString()} via the telephony shell; " +
                    "no airplane-mode cycle was needed."
            success ->
                "Network mode forced to 2G only (GSM) on ${appliedKeys.sorted().joinToString()}; " +
                    if (airplaneWasOn) {
                        "airplane mode was already on, so the radio applies it on its next registration."
                    } else {
                        "radio refreshed with a ${RADIO_SETTLE_SECONDS}s airplane-mode cycle."
                    }
            else -> buildString {
                append("Could not switch the cellular radio to 2G only. ")
                if (useNative && !nativeOk) {
                    append(
                        "Allowed network types are still " +
                            allowedTypesBaseline.keys.sorted().joinToString { slot ->
                                "$slot=${liveAllowed[slot] ?: "unreadable"}"
                            } + ". "
                    )
                } else if (mismatched.isNotEmpty()) {
                    append(
                        "settings still show " +
                            mismatched.sorted().joinToString { "$it=${live[it] ?: "unreadable"}" } + ". "
                    )
                }
                if (!result.isSuccess) {
                    append("(shell exit ${result.exitCode}: ${result.stderr.trim().take(160)}) ")
                }
                append("Root access is required to change the network mode.")
            }
        }
        logEvent(TriggerType.SCREEN_OFF, PROFILE_NAME_APPLY, success, message, commands)
        return NetworkModeOutcome(success, message, commands)
    }

    /**
     * Restores the network mode captured before the last 2G switch. Returns null when 2G-only is not
     * active, so it is safe to call on every screen-on event.
     */
    suspend fun restoreDefaultIfActive(): NetworkModeOutcome? {
        if (!preferences.networkMode2gActiveFlow.first()) return null
        return restoreDefault()
    }

    /** Restores the captured default network mode; keeps the baseline if the restore fails. */
    suspend fun restoreDefault(): NetworkModeOutcome? {
        var baseline = preferences.networkModeBaselineFlow.first()
        var allowedTypesBaseline = preferences.networkAllowedTypesBaselineFlow.first()
        if (baseline.isEmpty() && allowedTypesBaseline.isEmpty()) {
            // The app data was wiped or the app was reinstalled while 2G only was active. The mirrored
            // copy in global settings still has the user's real mode, so try that before giving up.
            val (mirroredModes, mirroredTypes) = readMirroredBaseline()
            baseline = mirroredModes
            allowedTypesBaseline = mirroredTypes
        }
        if (baseline.isEmpty() && allowedTypesBaseline.isEmpty()) {
            // Nothing survived anywhere: restore the LTE preferred default rather than leaving the
            // device restricted to 2G forever.
            return restoreSafeDefault()
        }

        val restoreCommands = buildRestoreCommands(baseline)
        val nativeRestore = allowedTypesBaseline.entries.sortedBy { it.key }.mapNotNull { (slot, names) ->
            val bitmask = allowedTypesToBitmask(names) ?: return@mapNotNull null
            "$NATIVE_COMMAND -s $slot $bitmask"
        }
        if (restoreCommands.isEmpty() && nativeRestore.isEmpty()) {
            preferences.setNetworkMode2gActive(false)
            preferences.saveNetworkModeBaseline(emptyMap())
            preferences.saveNetworkAllowedTypesBaseline(emptyMap())
            clearMirroredBaseline()
            val message = "Saved network mode baseline contained no restorable value; state cleared."
            logEvent(TriggerType.SCREEN_ON, PROFILE_NAME_RESTORE, false, message)
            return NetworkModeOutcome(false, message)
        }

        val useNative = nativeRestore.isNotEmpty()
        val commands = nativeRestore + restoreCommands
        val airplaneWasOn = isAirplaneModeEnabled()
        if (!useNative) preferences.setNetworkRadioRefreshPending(!airplaneWasOn)

        val script = if (useNative) {
            commands.joinToString("; ")
        } else {
            buildRadioScript(commands, airplaneWasOn).joinToString("; ")
        }
        val result = rootManager.execCommand(script, COMMAND_TIMEOUT_MS)

        if (!useNative) {
            preferences.setNetworkRadioRefreshPending(false)
            if (!airplaneWasOn) ensureAirplaneModeOff()
        }

        val live = readCurrentModes()
        val mismatched = baseline.filter { (key, value) -> value.isNotEmpty() && live[key] != value }
        val liveAllowed = if (useNative) readAllowedNetworkTypes() else emptyMap()
        val unrestoredSlots = allowedTypesBaseline.keys.filter { slot ->
            liveAllowed[slot]?.let { sameTypes(it, allowedTypesBaseline.getValue(slot)) } != true
        }
        val success = mismatched.isEmpty() && unrestoredSlots.isEmpty()

        // The baseline check above cannot see a stale GSM-only entry, because those are never
        // captured. Reset them to the restored default, otherwise the phone stays on 2G even though
        // the restore reported success.
        val heal = if (success) {
            healStaleGsmOnly(
                // A per-subscription key takes a single value, so only the first entry of a comma
                // separated default (e.g. "11,11") may be copied over.
                fallbackMode = baseline["preferred_network_mode"]
                    ?.takeIf { MODE_VALUE_REGEX.matches(it) && !isGsmOnlyModeValue(it) }
                    ?.substringBefore(','),
                fallbackTypes = allowedTypesBaseline.values.firstOrNull { !isGsmOnlyTypes(it) }
            )
        } else {
            HealOutcome(healedEntries = 0, isClean = false)
        }
        val healNote = if (heal.healedEntries > 0) {
            " ${heal.healedEntries} leftover 2G entr" +
                if (heal.healedEntries == 1) {
                    "y from an earlier interrupted switch was reset to " +
                        "${baseline["preferred_network_mode"] ?: SAFE_DEFAULT_MODE}."
                } else {
                    "ies from an earlier interrupted switch were reset to " +
                        "${baseline["preferred_network_mode"] ?: SAFE_DEFAULT_MODE}."
                }
        } else {
            ""
        }

        if (success) {
            preferences.setNetworkMode2gActive(false)
            preferences.saveNetworkModeBaseline(emptyMap())
            preferences.saveNetworkAllowedTypesBaseline(emptyMap())
            clearMirroredBaseline()
        }

        val message = when {
            success && useNative ->
                "Default network mode restored on SIM slot(s) " +
                    "${allowedTypesBaseline.keys.sorted().joinToString()} via the telephony shell." + healNote
            success ->
                "Default network mode restored (${baseline.keys.sorted().joinToString()}); " +
                    if (airplaneWasOn) {
                        "airplane mode was already on, so the radio applies it on its next registration."
                    } else {
                        "radio refreshed with a ${RADIO_SETTLE_SECONDS}s airplane-mode cycle."
                    } + healNote
            else -> buildString {
                append("Default network mode not fully restored")
                if (unrestoredSlots.isNotEmpty()) {
                    append(
                        "; SIM slot(s) still restricted: " +
                            unrestoredSlots.sorted().joinToString { slot ->
                                "$slot=${liveAllowed[slot] ?: "unreadable"}"
                            }
                    )
                }
                if (mismatched.isNotEmpty()) {
                    append("; still 2G only on " + mismatched.keys.sorted().joinToString())
                }
                if (!result.isSuccess) {
                    append(" (shell exit ${result.exitCode}: ${result.stderr.trim().take(160)})")
                }
                append(". It will be retried at the next screen-on.")
            }
        }
        logEvent(TriggerType.SCREEN_ON, PROFILE_NAME_RESTORE, success, message, commands)
        return NetworkModeOutcome(success, message, commands)
    }

    /**
     * Self-healing entry point used when the guard service or the app starts:
     * - clears a radio refresh that was interrupted (airplane mode can never be left on), and
     * - restores the default network mode when 2G-only is still active while the screen is on.
     */
    suspend fun reconcile(isScreenOn: Boolean) {
        if (preferences.networkRadioRefreshPendingFlow.first()) {
            ensureAirplaneModeOff()
            preferences.setNetworkRadioRefreshPending(false)
        }
        if (isScreenOn) {
            restoreDefaultIfActive()
        }
    }

    /**
     * Wraps [commands] in an airplane-mode cycle so RIL re-reads the network mode, unless the user
     * already had airplane mode enabled. The trailing "airplane off" steps always run.
     */
    private fun buildRadioScript(commands: List<String>, airplaneWasOn: Boolean): List<String> {
        if (airplaneWasOn) return commands
        return buildList {
            add("settings put global $AIRPLANE_MODE_SETTING 1")
            add("am broadcast -a android.intent.action.AIRPLANE_MODE --ez state true")
            addAll(commands)
            add("sleep $RADIO_SETTLE_SECONDS")
            add("settings put global $AIRPLANE_MODE_SETTING 0")
            add("am broadcast -a android.intent.action.AIRPLANE_MODE --ez state false")
        }
    }

    /** Fail-safe: never leave the device in airplane mode because of an interrupted switch. */
    private suspend fun ensureAirplaneModeOff() {
        if (!isAirplaneModeEnabled()) return
        rootManager.execCommand(
            "settings put global $AIRPLANE_MODE_SETTING 0; " +
                "am broadcast -a android.intent.action.AIRPLANE_MODE --ez state false",
            8_000L
        )
    }

    /** Best-effort copy of the baseline into global settings, so it outlives the app's own data. */
    private suspend fun mirrorBaseline(baseline: Map<String, String>, allowedTypes: Map<Int, String>) {
        val script = buildList {
            if (baseline.isNotEmpty()) {
                add("settings put global $BASELINE_MIRROR_KEY \"${encodeBaselineMirror(baseline)}\"")
            }
            if (allowedTypes.isNotEmpty()) {
                add("settings put global $ALLOWED_TYPES_MIRROR_KEY \"${encodeAllowedTypesMirror(allowedTypes)}\"")
            }
        }.joinToString("; ")
        if (script.isEmpty()) return
        rootManager.execCommand(script, COMMAND_TIMEOUT_MS)
    }

    /** Reads the mirrored baseline back. `settings get` prints `null` when the key is absent. */
    private suspend fun readMirroredBaseline(): Pair<Map<String, String>, Map<Int, String>> {
        val modes = rootManager.execReadCommand("settings get global $BASELINE_MIRROR_KEY", 3_000L)
        val types = rootManager.execReadCommand("settings get global $ALLOWED_TYPES_MIRROR_KEY", 3_000L)
        return decodeBaselineMirror(modes.stdout.trim()) to decodeAllowedTypesMirror(types.stdout.trim())
    }

    private suspend fun clearMirroredBaseline() {
        rootManager.execCommand(
            "settings delete global $BASELINE_MIRROR_KEY; " +
                "settings delete global $ALLOWED_TYPES_MIRROR_KEY",
            8_000L
        )
    }

    /**
     * Last resort when no baseline survived anywhere: put the radio back on the LTE preferred default
     * instead of leaving the phone pinned to 2G with nothing left to restore from.
     */
    private suspend fun restoreSafeDefault(): NetworkModeOutcome {
        val bitmask = allowedTypesToBitmask(SAFE_DEFAULT_TYPES)
        val commands = buildList {
            add("settings put global preferred_network_mode $SAFE_DEFAULT_MODE")
            if (bitmask != null) {
                for (slot in 0 until MAX_SIM_SLOTS) add("$NATIVE_COMMAND -s $slot $bitmask")
            }
        }
        val result = rootManager.execCommand(commands.joinToString("; "), COMMAND_TIMEOUT_MS)

        val liveAllowed = readAllowedNetworkTypes()
        val hasLte = liveAllowed.values.any { types ->
            types.split('|').map { it.trim() }.contains("LTE")
        }
        if (hasLte) {
            preferences.setNetworkMode2gActive(false)
            preferences.saveNetworkModeBaseline(emptyMap())
            preferences.saveNetworkAllowedTypesBaseline(emptyMap())
            clearMirroredBaseline()
        }
        val message = if (hasLte) {
            "No saved baseline survived, so the radio was returned to the LTE preferred default " +
                "(${SAFE_DEFAULT_TYPES}). Select your preferred network mode again if it was 4G only."
        } else {
            buildString {
                append("No saved baseline survived and the LTE preferred default could not be applied")
                if (!result.isSuccess) {
                    append(" (shell exit ${result.exitCode}: ${result.stderr.trim().take(160)})")
                }
                append(". It will be retried while the screen stays on.")
            }
        }
        logEvent(TriggerType.SCREEN_ON, PROFILE_NAME_RESTORE, hasLte, message, commands)
        return NetworkModeOutcome(hasLte, message, commands)
    }

    private data class HealOutcome(val healedEntries: Int, val isClean: Boolean)

    /**
     * Resets any mode entry or SIM slot that is still restricted to 2G after a restore.
     *
     * Such entries are left behind by an interrupted switch: the apply never captured them (they
     * were already 2G), so the restore has nothing to write for them and they would keep the phone
     * on GSM with the screen on.
     */
    private suspend fun healStaleGsmOnly(fallbackMode: String?, fallbackTypes: String?): HealOutcome {
        val staleModes = readCurrentModes().filterValues { isGsmOnlyModeValue(it) }
        val staleSlots = readAllowedNetworkTypes().filterValues { isGsmOnlyTypes(it) }
        if (staleModes.isEmpty() && staleSlots.isEmpty()) return HealOutcome(0, isClean = true)

        val mode = fallbackMode ?: SAFE_DEFAULT_MODE
        val bitmask = allowedTypesToBitmask(fallbackTypes ?: SAFE_DEFAULT_TYPES)
            ?: return HealOutcome(0, isClean = false)
        val healCommands = buildList {
            for (key in staleModes.keys.sorted()) add("settings put global $key $mode")
            for (slot in staleSlots.keys.sorted()) add("$NATIVE_COMMAND -s $slot $bitmask")
        }
        rootManager.execCommand(healCommands.joinToString("; "), COMMAND_TIMEOUT_MS)

        val stillGsmModes = readCurrentModes().filterValues { isGsmOnlyModeValue(it) }
        val stillGsmSlots = readAllowedNetworkTypes().filterValues { isGsmOnlyTypes(it) }
        return HealOutcome(
            healedEntries = staleModes.size + staleSlots.size,
            isClean = stillGsmModes.isEmpty() && stillGsmSlots.isEmpty()
        )
    }

    private suspend fun isAirplaneModeEnabled(): Boolean =
        rootManager.execReadCommand("settings get global $AIRPLANE_MODE_SETTING", 3_000L)
            .stdout.trim() == "1"

    /** Reads every persisted preferred network mode entry, e.g. `{preferred_network_mode1=9}`. */
    private suspend fun readCurrentModes(): Map<String, String> {
        val listing = rootManager.execReadCommand("settings list global", 5_000L)
        if (!listing.isSuccess) return emptyMap()
        return parseNetworkModeSettings(listing.stdout)
    }

    /**
     * Reads the allowed network types of every SIM slot, e.g. `{0=GPRS|EDGE|LTE|GSM}`. Returns an
     * empty map on Android versions without the telephony shell command, which makes the caller use
     * the settings/airplane fallback.
     */
    private suspend fun readAllowedNetworkTypes(): Map<Int, String> {
        val result = mutableMapOf<Int, String>()
        for (slot in 0 until MAX_SIM_SLOTS) {
            val listing = rootManager.execReadCommand(
                "cmd phone get-allowed-network-types-for-users -s $slot",
                5_000L
            )
            if (!listing.isSuccess) continue
            val value = listing.stdout.lineSequence()
                .map { it.trim() }
                .lastOrNull { isAllowedTypesValue(it) }
                ?: continue
            result[slot] = value
        }
        return result
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
}
