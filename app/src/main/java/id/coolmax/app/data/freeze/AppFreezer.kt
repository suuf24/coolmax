package id.coolmax.app.data.freeze

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Process
import android.view.inputmethod.InputMethodManager
import id.coolmax.app.data.preferences.CoolMaxPreferences
import id.coolmax.app.data.root.RootManager
import id.coolmax.app.domain.model.EventLogEntry
import id.coolmax.app.domain.model.TriggerType
import kotlinx.coroutines.flow.first
import java.util.UUID

/**
 * Screen-Off app guard: freezes every running third-party app while the display is off, except the
 * packages the user whitelisted.
 *
 * Freezing is *reversible* (unlike `am force-stop`, which destroys the app's state), so the apps come
 * back exactly where they were when the screen turns on:
 * - primary: the per-UID freezer cgroup (`/sys/fs/cgroup/uid_<uid>/cgroup.freeze` on Android 12+,
 *   `…/freezer/uid_<uid>/freezer.state` or `/dev/freezer/uid_<uid>/freezer.state` on older kernels),
 * - fallback: `SIGSTOP` for every process of that UID, released again with `SIGCONT`.
 *
 * Safety rules:
 * - only UIDs in the app range (10000–19999) whose packages are all third-party (never system/ROM
 *   apps) are touched, and only apps that are actually running,
 * - CoolMax itself, the launcher and the enabled input methods are always excluded,
 * - the target UIDs are persisted *before* the freeze, so an interrupted run is always thawed at the
 *   next screen-on, service start, app start or boot,
 * - a failed/partial freeze is immediately rolled back instead of leaving half the apps frozen.
 */
class AppFreezer(
    private val context: Context,
    private val rootManager: RootManager,
    private val preferences: CoolMaxPreferences
) {

    companion object {
        const val PROFILE_NAME_FREEZE = "Screen-Off App Freeze"
        const val PROFILE_NAME_UNFREEZE = "Screen-On App Unfreeze"

        const val COMMAND_TIMEOUT_MS = 25_000L
        const val READ_TIMEOUT_MS = 8_000L

        private const val FIRST_APP_UID = 10_000
        private const val LAST_APP_UID = 19_999

        /** Parses `ps -A -o UID=,PID=` output, keeping only app-range UIDs. Exposed for tests. */
        internal fun parseRunningUids(psOutput: String): Map<Int, List<Int>> {
            val result = LinkedHashMap<Int, MutableList<Int>>()
            psOutput.lineSequence().forEach { line ->
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.size < 2) return@forEach
                val uid = parts[0].toIntOrNull() ?: return@forEach
                val pid = parts[1].toIntOrNull() ?: return@forEach
                if (uid < FIRST_APP_UID || uid > LAST_APP_UID) return@forEach
                result.getOrPut(uid) { mutableListOf() }.add(pid)
            }
            return result
        }

        /**
         * Freezes the given UIDs with the freezer cgroup that exists on this kernel, and falls back to
         * `SIGSTOP` per process. Prints which UIDs were frozen by each mechanism (`CG:` / `SG:`).
         * Exposed for tests.
         */
        internal fun buildFreezeScript(uids: Collection<Int>): String {
            if (uids.isEmpty()) return "true"
            val template = """
                UIDS="§UIDS"
                PSLIST="§(ps -A -o UID=,PID= 2>/dev/null)"
                CG=""
                SG=""
                for u in §UIDS; do
                  ok=0
                  if [ -e "/sys/fs/cgroup/uid_§u/cgroup.freeze" ]; then
                    echo 1 > "/sys/fs/cgroup/uid_§u/cgroup.freeze" 2>/dev/null && ok=1
                  fi
                  if [ §ok -eq 0 ] && [ -e "/sys/fs/cgroup/freezer/uid_§u/freezer.state" ]; then
                    echo FROZEN > "/sys/fs/cgroup/freezer/uid_§u/freezer.state" 2>/dev/null && ok=1
                  fi
                  if [ §ok -eq 0 ] && [ -e "/dev/freezer/uid_§u/freezer.state" ]; then
                    echo FROZEN > "/dev/freezer/uid_§u/freezer.state" 2>/dev/null && ok=1
                  fi
                  if [ §ok -eq 1 ]; then CG="§CG §u"; else SG="§SG §u"; fi
                done
                SGOK=""
                for u in §SG; do
                  n=0
                  for p in §(echo "§PSLIST" | awk -v u=§u '§1==u {print §2}'); do
                    kill -STOP "§p" 2>/dev/null && n=1
                  done
                  [ §n -eq 1 ] && SGOK="§SGOK §u"
                done
                echo "CG:§CG"
                echo "SG:§SGOK"
            """.trimIndent()
            return template.replace("§UIDS", uids.sorted().joinToString(" ")).replace('§', '$')
        }

        /** Thaws the given UIDs on every freezer variant and releases any `SIGSTOP`. */
        internal fun buildUnfreezeScript(uids: Collection<Int>): String {
            if (uids.isEmpty()) return "true"
            val template = """
                UIDS="§UIDS"
                PSLIST="§(ps -A -o UID=,PID= 2>/dev/null)"
                for u in §UIDS; do
                  if [ -e "/sys/fs/cgroup/uid_§u/cgroup.freeze" ]; then
                    echo 0 > "/sys/fs/cgroup/uid_§u/cgroup.freeze" 2>/dev/null
                  fi
                  if [ -e "/sys/fs/cgroup/freezer/uid_§u/freezer.state" ]; then
                    echo THAWED > "/sys/fs/cgroup/freezer/uid_§u/freezer.state" 2>/dev/null
                  fi
                  if [ -e "/dev/freezer/uid_§u/freezer.state" ]; then
                    echo THAWED > "/dev/freezer/uid_§u/freezer.state" 2>/dev/null
                  fi
                  for p in §(echo "§PSLIST" | awk -v u=§u '§1==u {print §2}'); do
                    kill -CONT "§p" 2>/dev/null
                  done
                done
                echo OK
            """.trimIndent()
            return template.replace("§UIDS", uids.sorted().joinToString(" ")).replace('§', '$')
        }

        /** Collects the UIDs reported as frozen (`CG:` cgroup, `SG:` SIGSTOP). Exposed for tests. */
        internal fun parseFreezeOutput(rawOutput: String): Set<Int> =
            rawOutput.lineSequence()
                .filter { it.startsWith("CG:") || it.startsWith("SG:") }
                .flatMap { it.substringAfter(':').trim().split(' ').asSequence() }
                .mapNotNull { it.trim().toIntOrNull() }
                .toSet()
    }

    data class FreezeOutcome(
        val isSuccess: Boolean,
        val message: String,
        val frozenUids: Set<Int> = emptySet()
    )

    /**
     * Freezes every running third-party app that is not in [whitelist]. No-op when a freeze is already
     * active, so a repeated screen-off event cannot capture a partially frozen state twice.
     */
    suspend fun freezeAll(whitelist: Set<String>): FreezeOutcome {
        val alreadyFrozen = preferences.frozenUidsFlow.first()
        if (alreadyFrozen.isNotEmpty()) {
            return FreezeOutcome(
                isSuccess = true,
                message = "Already frozen (${alreadyFrozen.size} app UID(s)); state kept.",
                frozenUids = alreadyFrozen
            )
        }

        val running = readRunningUids()
        if (running.isEmpty()) {
            val message = "Could not read the running process list; no app was frozen."
            logEvent(TriggerType.SCREEN_OFF, PROFILE_NAME_FREEZE, false, message)
            return FreezeOutcome(false, message)
        }

        val targets = selectTargets(running, whitelist)
        if (targets.isEmpty()) {
            val message = "No running third-party app to freeze (whitelist: ${whitelist.size})."
            logEvent(TriggerType.SCREEN_OFF, PROFILE_NAME_FREEZE, true, message)
            return FreezeOutcome(true, message)
        }

        if (preferences.dryRunModeFlow.first()) {
            val message = "[DRY-RUN] Simulated '$PROFILE_NAME_FREEZE' for ${targets.size} app(s) " +
                "(UIDs ${targets.sorted().joinToString()})."
            logEvent(TriggerType.SCREEN_OFF, PROFILE_NAME_FREEZE, true, message)
            return FreezeOutcome(true, message)
        }

        // Persist before touching anything: whatever happens next, the next screen-on can thaw it.
        preferences.saveFrozenUids(targets)

        val result = rootManager.execCommand(buildFreezeScript(targets), COMMAND_TIMEOUT_MS)
        val frozen = parseFreezeOutput(result.stdout)
        if (!result.isSuccess && frozen.isEmpty()) {
            // Roll back instead of leaving a half-applied freeze behind.
            runUnfreeze(targets)
            preferences.saveFrozenUids(emptySet())
            val message = "Freeze failed (shell exit ${result.exitCode}: " +
                "${result.stderr.trim().take(160)}); the previous state was restored."
            logEvent(TriggerType.SCREEN_OFF, PROFILE_NAME_FREEZE, false, message)
            return FreezeOutcome(false, message)
        }

        val message = "Froze ${frozen.size} app(s) for the screen-off profile " +
            "(whitelist: ${whitelist.size} app(s) kept running)."
        logEvent(TriggerType.SCREEN_OFF, PROFILE_NAME_FREEZE, frozen.isNotEmpty(), message)
        return FreezeOutcome(frozen.isNotEmpty(), message, frozen)
    }

    /** Thaws every app frozen by the screen-off profile. No-op when nothing is frozen. */
    suspend fun unfreezeAll(): FreezeOutcome? {
        val frozen = preferences.frozenUidsFlow.first()
        if (frozen.isEmpty()) return null
        if (preferences.dryRunModeFlow.first()) {
            val message = "[DRY-RUN] Simulated '$PROFILE_NAME_UNFREEZE' for ${frozen.size} app(s)."
            logEvent(TriggerType.SCREEN_ON, PROFILE_NAME_UNFREEZE, true, message)
            return FreezeOutcome(true, message)
        }

        val result = runUnfreeze(frozen)
        preferences.saveFrozenUids(emptySet())
        val message = if (result.isSuccess) {
            "Thawed ${frozen.size} app(s); they resume where they left off."
        } else {
            "Thaw attempted for ${frozen.size} app(s) but the shell reported exit ${result.exitCode}; " +
                "SIGCONT was still sent to every process."
        }
        logEvent(TriggerType.SCREEN_ON, PROFILE_NAME_UNFREEZE, true, message)
        return FreezeOutcome(true, message, frozen)
    }

    /**
     * Self-healing entry point for service/app start (and boot): a freeze can never survive the screen
     * being on, so anything still recorded as frozen is thawed immediately.
     */
    suspend fun reconcile(isScreenOn: Boolean) {
        if (!isScreenOn) return
        unfreezeAll()
    }

    /** Executes the thaw script. Always sends SIGCONT, even if a freezer path is unavailable. */
    private suspend fun runUnfreeze(uids: Set<Int>) =
        rootManager.execCommand(buildUnfreezeScript(uids), COMMAND_TIMEOUT_MS)

    private suspend fun readRunningUids(): Map<Int, List<Int>> {
        val result = rootManager.execReadCommand("ps -A -o UID=,PID=", READ_TIMEOUT_MS)
        if (!result.isSuccess) return emptyMap()
        return parseRunningUids(result.stdout)
    }

    /**
     * Keeps only running, third-party UIDs that are neither CoolMax, the launcher, an input method nor
     * whitelisted. A UID is skipped when any of its packages is unknown (package visibility) or is a
     * system/updated-system app, so ROM components can never be frozen.
     */
    private fun selectTargets(running: Map<Int, List<Int>>, whitelist: Set<String>): Set<Int> {
        val pm = context.packageManager
        val protected = mutableSetOf(Process.myUid())

        protected += resolveHomePackages(pm).mapNotNull { packageUid(pm, it) }
        protected += resolveInputMethodPackages().mapNotNull { packageUid(pm, it) }
        protected += whitelist.mapNotNull { packageUid(pm, it) }

        return running.keys.filter { uid ->
            uid !in protected && isThirdPartyUid(pm, uid)
        }.toSet()
    }

    private fun isThirdPartyUid(pm: PackageManager, uid: Int): Boolean {
        val packages = runCatching { pm.getPackagesForUid(uid) }.getOrNull()
            ?.filterNotNull()
            ?.distinct()
            ?.takeIf { it.isNotEmpty() }
            ?: return false
        return packages.all { packageName ->
            val info = runCatching { pm.getApplicationInfo(packageName, 0) }.getOrNull()
                ?: return@all false
            val isSystem = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0 ||
                (info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            !isSystem
        }
    }

    private fun packageUid(pm: PackageManager, packageName: String): Int? =
        runCatching { pm.getApplicationInfo(packageName, 0).uid }.getOrNull()

    private fun resolveHomePackages(pm: PackageManager): List<String> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return runCatching { pm.queryIntentActivities(intent, 0) }
            .getOrDefault(emptyList())
            .mapNotNull { it.activityInfo?.packageName }
    }

    private fun resolveInputMethodPackages(): List<String> {
        val imm = runCatching {
            context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        }.getOrNull() ?: return emptyList()
        return runCatching { imm.inputMethodList }
            .getOrDefault(emptyList())
            .mapNotNull { it.packageName }
    }

    private suspend fun logEvent(
        trigger: TriggerType,
        profileName: String,
        success: Boolean,
        message: String
    ) {
        preferences.appendEventLog(
            EventLogEntry(
                id = UUID.randomUUID().toString(),
                timestampEpochMs = System.currentTimeMillis(),
                trigger = trigger,
                profileName = profileName,
                success = success,
                message = message
            )
        )
    }
}
