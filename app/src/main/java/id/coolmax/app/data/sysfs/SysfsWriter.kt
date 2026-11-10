package id.coolmax.app.data.sysfs

import com.topjohnwu.superuser.Shell
import id.coolmax.app.data.root.SocVendor
import id.coolmax.app.domain.safety.SafeFrequencyPlanner
import kotlin.math.abs
import id.coolmax.app.domain.safety.SysfsWriteOperation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Single centralized, injection-safe gateway for all sysfs write operations.
 *
 * Enforces all safety rules:
 * - Strict path & value whitelist validation (prevents command injection; never interpolates raw user strings).
 * - 3-second timeout per operation (`TIMEOUT_MS = 3_000L`).
 * - Maximum 1 retry (`MAX_RETRIES = 1`) on transient failure.
 * - Mandatory read-back verification after writing: detects if kernel thermal/vendor HAL overrode the value.
 * - SoC-aware post-failure fallback:
 *     - **MediaTek**: `scaling_max_freq` is clamped by PPM/thermal drivers on MT6785-class SoCs, so a
 *       failed verification triggers `MtkOppFallbackWriter` (`/proc/cpufreq/MT_CPU_DVFS_<name>/cpufreq_oppidx`).
 *     - **Qualcomm**: the documented/vendor-respected sysfs sequence (raise/lighten `scaling_min_freq`
 *       first, rewrite `scaling_max_freq`); persistent clamps are reported transparently.
 * - Supports Dry-Run (simulation) mode where commands are recorded and returned without touching `/sys`.
 */
class SysfsWriter(
    private val shellExecutor: SysfsShellExecutor = LibsuSysfsShellExecutor(),
    private val mtkOppFallback: MtkOppFallbackWriter? = null,
    private val qualcommFallback: QualcommFallbackWriter? = null,
    private val socVendorProvider: () -> SocVendor = { SocVendor.OTHER }
) {
    companion object {
        const val TIMEOUT_MS = 3_000L
        const val MAX_RETRIES = 1

        private val SAFE_VALUE_REGEX = Regex("^[a-zA-Z0-9_-]{1,32}$")
    }

    data class WriteBatchResult(
        val allSucceeded: Boolean,
        val overriddenBySystem: Boolean,
        val executedCommands: List<String>,
        val operationLogs: List<String>,
        val failedOperations: List<String>,
        val fallbackMethodUsed: String? = null
    )

    /**
     * Executes a validated list of [SysfsWriteOperation]s with timeout, max 1 retry,
     * and read-back verification.
     */
    suspend fun executeOperations(
        operations: List<SysfsWriteOperation>,
        dryRun: Boolean = false
    ): WriteBatchResult = withContext(Dispatchers.IO) {
        val executedCommands = mutableListOf<String>()
        val logs = mutableListOf<String>()
        val failures = mutableListOf<String>()
        var overriddenDetected = false
        var fallbackUsed: String? = null

        for (op in operations) {
            // 1. Validate path & value against strict regex to prevent shell injection
            if (!SafeFrequencyPlanner.isValidSysfsCpuPath(op.path)) {
                val msg = "REJECTED invalid sysfs path: ${op.path}"
                logs.add(msg)
                failures.add(msg)
                continue
            }
            if (!SAFE_VALUE_REGEX.matches(op.value)) {
                val msg = "REJECTED invalid sysfs value '${op.value}' for ${op.path}"
                logs.add(msg)
                failures.add(msg)
                continue
            }
            if (op.path == "/sys/devices/system/cpu/cpu0/online" && op.value == "0") {
                val msg = "REJECTED attempt to offline cpu0"
                logs.add(msg)
                failures.add(msg)
                continue
            }

            val writeCmd = "chmod 0644 '${op.path}' 2>/dev/null; echo '${op.value}' > '${op.path}'"
            executedCommands.add("echo '${op.value}' > '${op.path}'")

            if (dryRun) {
                logs.add("[DRY-RUN] Would execute: echo '${op.value}' > '${op.path}' (${op.description})")
                continue
            }

            var attempt = 0
            var verified = false
            var lastReadBack: String? = null

            while (attempt <= MAX_RETRIES && !verified) {
                attempt++
                val writeOk = withTimeoutOrNull(TIMEOUT_MS) {
                    shellExecutor.writeNode(writeCmd)
                } ?: false

                // Read back immediately to verify the kernel accepted the value
                lastReadBack = withTimeoutOrNull(TIMEOUT_MS) {
                    shellExecutor.readNode(op.path)
                }?.trim()

                if (writeOk && lastReadBack == op.value) {
                    verified = true
                    logs.add("[OK] ${op.path} = ${op.value} (attempt $attempt)")
                } else if (attempt <= MAX_RETRIES) {
                    logs.add("[RETRY] ${op.path} expected '${op.value}', read '$lastReadBack' (attempt $attempt)")
                    delay(80L)
                }
            }

            // 2. SoC-aware fallback when `scaling_max_freq` is clamped back by vendor/thermal drivers
            if (!verified && op.path.endsWith("/scaling_max_freq")) {
                val targetKhz = op.value.toLongOrNull()
                val fallbackOutcome = targetKhz?.let { runMaxFreqFallback(op, it) }
                if (fallbackOutcome != null) {
                    fallbackUsed = fallbackOutcome.method
                    logs.add("[FALLBACK] ${fallbackOutcome.method}: ${fallbackOutcome.detail}")
                    if (fallbackOutcome.success) {
                        verified = true
                        overriddenDetected = false
                    } else {
                        overriddenDetected = true
                    }
                }
            }

            if (!verified) {
                overriddenDetected = true
                val err = "Verification failed on ${op.path}: expected '${op.value}', actual '${lastReadBack ?: "unreadable"}' " +
                    "(possibly overridden by thermal engine or vendor power HAL)"
                logs.add("[ERROR] $err")
                failures.add(err)
            }
        }

        WriteBatchResult(
            allSucceeded = failures.isEmpty(),
            overriddenBySystem = overriddenDetected,
            executedCommands = executedCommands,
            operationLogs = logs,
            failedOperations = failures,
            fallbackMethodUsed = fallbackUsed
        )
    }

    /** Runs the SoC-specific maximum-frequency fallback chain for a failed `scaling_max_freq` write. */
    private suspend fun runMaxFreqFallback(op: SysfsWriteOperation, targetKhz: Long): FallbackOutcome? {
        return when (socVendorProvider()) {
            SocVendor.MEDIATEK -> mtkOppFallback?.limitMaxFreq(op.path, targetKhz)
            SocVendor.QUALCOMM -> qualcommFallback?.resetMaxFreq(op.path, targetKhz)
            SocVendor.OTHER -> null
        }
    }
}

/** Result of a SoC-specific fallback attempt. */
data class FallbackOutcome(
    val success: Boolean,
    val method: String,
    val detail: String
)

/** SoC-specific fallback strategies for clamped `scaling_max_freq` writes. */
interface MaxFreqFallbackWriter

/**
 * MediaTek fallback: write the closest OPP index to `/proc/cpufreq/MT_CPU_DVFS_*` because
 * the vendor cpufreq driver ignores/clamps `scaling_max_freq` on MT6785-class SoCs.
 */
interface MtkOppFallbackWriter : MaxFreqFallbackWriter {
    /**
     * @param scalingMaxPath full sysfs path, e.g. `/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq`
     * @param maxFreqKhz target maximum frequency in kHz
     */
    suspend fun limitMaxFreq(scalingMaxPath: String, maxFreqKhz: Long): FallbackOutcome?
}

/**
 * Qualcomm fallback: `scaling_max_freq` is ordinarily honored; if the thermal engine clamps it back,
 * first lift `scaling_min_freq` (netralisasi min/max) then rewrite `scaling_max_freq`, and verify.
 */
interface QualcommFallbackWriter : MaxFreqFallbackWriter {
    /** @return [FallbackOutcome] or null when the QC specific path is not applicable. */
    suspend fun resetMaxFreq(scalingMaxPath: String, maxFreqKhz: Long): FallbackOutcome?
}

/**
 * MediaTek production implementation on top of the same root `SysfsShellExecutor` used by [SysfsWriter].
 *
 * Interface discovery per `perubahan.md` §3.3:
 * - `/proc/cpufreq/MT_CPU_DVFS_LL` → cluster cpu0-5 (little, policy0 on MT6785)
 * - `/proc/cpufreq/MT_CPU_DVFS_L`  → cluster cpu6-7 (big/A76 on MT6785)
 * Node↔cluster mapping is resolved DYNAMICALLY by comparing the OPP table of each DVFS node
 * with the cluster's current/available frequency (no hardcoded SoC assumptions).
 */
class LibsuMtkOppFallbackWriter(
    private val shellExecutor: SysfsShellExecutor
) : MtkOppFallbackWriter {

    companion object {
        const val MTK_CPUFREQ_PROC_DIR = "/proc/cpufreq"
        private val DVFS_NODE_REGEX = Regex("^MT_CPU_DVFS_[A-Z]+$")
        private val INT_SEQ_REGEX = Regex("\\d+")
        private val EXPLICIT_INDEX_REGEX = Regex("^\\[\\s*(\\d+)\\s*\\]|^(\\d+)\\s*[:=]")
        private const val MAX_FREQ_MATCH_TOLERANCE_KHZ = 300_000L
        private const val MTK_WRITE_MAX_RETRIES = 1
    }

    /** One OPP step of a MediaTek DVFS node. */
    data class MtkOppStep(
        val index: Int,
        val freqKhz: Long
    )

    override suspend fun limitMaxFreq(scalingMaxPath: String, maxFreqKhz: Long): FallbackOutcome? {
        val nodes = discoverDvfsNodes() ?: return null
        if (nodes.isEmpty()) return null

        val policyDir = scalingMaxPath.substringBeforeLast("/")
        val clusterFrequencies = readClusterFrequencies(policyDir)
        val clusterFreqHint = readClusterFreqHint(policyDir, clusterFrequencies)
        val node = pickNodeForCluster(nodes, clusterFreqHint, clusterFrequencies) ?: return null
        val table = resolveOppTable(node, clusterFrequencies)
        if (table.isEmpty()) {
            return FallbackOutcome(
                success = false,
                method = "MediaTek cpufreq_oppidx",
                detail = "OPP table of $node unreadable and cluster frequencies unavailable"
            )
        }

        // Target: the first (highest) OPP step whose frequency is still <= maxFreqKhz (perubahan.md §5.2).
        // MTK OPP index 0 is the highest frequency step (CPU_DVFS_FREQ0).
        val target = table.sortedBy { it.index }.firstOrNull { it.freqKhz <= maxFreqKhz }
            ?: table.sortedBy { it.index }.firstOrNull { it.freqKhz <= clusterFreqHint }
        if (target == null) {
            return FallbackOutcome(
                success = false,
                method = "MediaTek cpufreq_oppidx",
                detail = "No OPP step <= $maxFreqKhz kHz on $node (table: ${table.joinToString { "${it.index}:${it.freqKhz}" }})"
            )
        }

        val indexPath = "$node/cpufreq_oppidx"
        var attempt = 0
        var readBack: String? = null
        while (attempt <= MTK_WRITE_MAX_RETRIES) {
            attempt++
            val written = withTimeoutOrNull(3_000L) {
                shellExecutor.writeNode("chmod 0644 '$indexPath' 2>/dev/null; echo '${target.index}' > '$indexPath'")
            } ?: false
            readBack = withTimeoutOrNull(3_000L) { shellExecutor.readNode(indexPath) }?.trim()
            val readBackIndex = readBack?.let { INT_SEQ_REGEX.find(it)?.value }?.toIntOrNull()
            if (written && readBackIndex == target.index) {
                return FallbackOutcome(
                    success = true,
                    method = "MediaTek cpufreq_oppidx",
                    detail = "oppidx=${target.index} (freq ${target.freqKhz} kHz) on $node, verified read-back"
                )
            }
            if (attempt <= MTK_WRITE_MAX_RETRIES) delay(80L)
        }
        return FallbackOutcome(
            success = false,
            method = "MediaTek cpufreq_oppidx",
            detail = "oppidx write not verified on $indexPath (target=${target.index}, read='$readBack')"
        )
    }

    /** Lists `/proc/cpufreq` MT_CPU_DVFS_* directories through the root shell. */
    private suspend fun discoverDvfsNodes(): List<String>? {
        val listing = withTimeoutOrNull(3_000L) {
            shellExecutor.listDir(MTK_CPUFREQ_PROC_DIR)
        } ?: return null
        return listing
            .filter { DVFS_NODE_REGEX.matches(it) }
            .sortedDescending() // LL before L, matching MT6785 naming so ties resolve to the bigger little-cluster
            .map { "$MTK_CPUFREQ_PROC_DIR/$it" }
    }

    /** Reads the cluster's `scaling_available_frequencies` (ascending) as the canonical OPP frequency list. */
    private suspend fun readClusterFrequencies(policyDir: String): List<Long> {
        val raw = shellExecutor.readNode("$policyDir/scaling_available_frequencies") ?: return emptyList()
        return raw.split(Regex("\\s+"))
            .mapNotNull { it.trim().toLongOrNull() }
            .filter { it in 50_000..5_000_000L }
            .distinct()
            .sorted()
    }

    /** Cluster hardware maximum, used to match the cluster to its MTK DVFS node. */
    private suspend fun readClusterFreqHint(policyDir: String, clusterFrequencies: List<Long>): Long {
        val cpuinfoMax = shellExecutor.readNode("$policyDir/cpuinfo_max_freq")?.trim()?.toLongOrNull()
        if (cpuinfoMax != null && cpuinfoMax > 0L) return cpuinfoMax
        val scalingMax = shellExecutor.readNode("$policyDir/scaling_max_freq")?.trim()?.toLongOrNull()
        if (scalingMax != null && scalingMax > 0L) return scalingMax
        return clusterFrequencies.maxOrNull() ?: 0L
    }

    /**
     * Chooses the DVFS node whose top OPP frequency is closest to the cluster hardware maximum.
     * MT6785: `MT_CPU_DVFS_LL` tops at 2000000 kHz (policy0, cpu0-5) and `MT_CPU_DVFS_L`
     * tops at 2050000 kHz (policy6, cpu6-7).
     */
    private suspend fun pickNodeForCluster(
        nodes: List<String>,
        freqHint: Long,
        clusterFrequencies: List<Long>
    ): String? {
        if (freqHint <= 0L && clusterFrequencies.isEmpty()) return nodes.firstOrNull()
        var best: Pair<Long, String>? = null
        for (node in nodes) {
            val top = resolveOppTable(node, clusterFrequencies).maxOfOrNull { it.freqKhz } ?: 0L
            if (top == 0L) continue
            val distance = abs(top - freqHint)
            if (distance <= MAX_FREQ_MATCH_TOLERANCE_KHZ && (best == null || distance < best.first)) {
                best = distance to node
            }
        }
        return best?.second ?: nodes.firstOrNull()
    }

    /**
     * Resolves the index→frequency table for a DVFS node.
     *
     * The procfs dump differs between MTK kernel branches, e.g. `[0] 2000000 KHz, 1137500 uV`,
     * `0: 2000000` or a bare frequency per line. Voltage values overlap the frequency range, so a
     * number is only accepted as a frequency when it matches one of the cluster's own
     * `scaling_available_frequencies` (within 2%). When the dump cannot be interpreted, the index
     * table is derived from `scaling_available_frequencies` sorted descending (MTK index 0 = highest
     * frequency, per `CPU_DVFS_FREQ0`).
     */
    internal suspend fun resolveOppTable(
        dvfsNode: String,
        clusterFrequencies: List<Long> = emptyList()
    ): List<MtkOppStep> {
        val raw = shellExecutor.readNode("$dvfsNode/cpufreq_oppidx")
        val parsed = raw?.let { parseOppTable(it, clusterFrequencies) }.orEmpty()
        if (parsed.size >= 2) return parsed

        if (clusterFrequencies.size >= 2) {
            return clusterFrequencies.sortedDescending().mapIndexed { index, freqKhz ->
                MtkOppStep(index = index, freqKhz = freqKhz)
            }
        }
        return emptyList()
    }

    /** Pure parser for the procfs OPP dump; exposed for unit tests. */
    internal fun parseOppTable(raw: String, clusterFrequencies: List<Long>): List<MtkOppStep> {
        val known = clusterFrequencies.sorted()
        val rows = mutableListOf<MtkOppStep>()
        var implicitIndex = 0
        for (line in raw.lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            // Skip the meta line, e.g. "cpufreq_oppidx = 12" / "SS index: 12"
            if (trimmed.contains("oppidx", ignoreCase = true)) continue

            val nums = INT_SEQ_REGEX.findAll(trimmed)
                .mapNotNull { it.value.toLongOrNull() }
                .filter { it in 20_000..5_000_000L }
                .toList()
            if (nums.isEmpty()) continue

            // The index prefix is parsed before frequency filtering, otherwise a leading 0..19 is
            // dropped by the frequency range filter and silently replaced by the implicit index.
            val explicitIndex = EXPLICIT_INDEX_REGEX.find(trimmed)?.let { match ->
                match.groupValues.drop(1).firstOrNull { it.isNotEmpty() }?.toIntOrNull()
            }
            val freq = pickFrequency(nums, known) ?: continue
            val index = explicitIndex?.takeIf { it in 0..255 } ?: implicitIndex
            if (rows.none { it.index == index }) {
                rows.add(MtkOppStep(index = index, freqKhz = freq))
            }
            implicitIndex++
        }
        return rows.sortedBy { it.index }
    }

    /**
     * Picks the number that is a plausible CPU frequency. Cluster frequencies are the source of
     * truth, so a candidate within 2% of a known step always wins; otherwise the largest number in
     * the row (frequencies are larger than MTK voltages in practice) is used.
     */
    private fun pickFrequency(nums: List<Long>, known: List<Long>): Long? {
        for (n in nums) {
            if (known.any { step -> abs(step - n) <= (step * 0.02).toLong().coerceAtLeast(1_000L) }) {
                return known.minByOrNull { step -> abs(step - n) }
            }
        }
        val fallback = nums.maxOrNull() ?: return null
        return fallback.takeIf { it >= 300_000L }
    }
}

/**
 * Abstraction over root shell execution so unit tests can verify exact command sequences,
 * retry behavior, and read-back verification without requiring an Android device.
 */
interface SysfsShellExecutor {
    suspend fun writeNode(command: String): Boolean
    suspend fun readNode(path: String): String?
    suspend fun listDir(dirPath: String): List<String> = emptyList()
}

/**
 * Production implementation backed by `com.github.topjohnwu.libsu`.
 */
class LibsuSysfsShellExecutor : SysfsShellExecutor {
    override suspend fun writeNode(command: String): Boolean {
        return try {
            Shell.cmd(command).exec().isSuccess
        } catch (_: Throwable) {
            false
        }
    }

    override suspend fun readNode(path: String): String? {
        return try {
            val res = Shell.cmd("cat '$path' 2>/dev/null").exec()
            if (res.isSuccess) res.out.joinToString("\n").trim() else null
        } catch (_: Throwable) {
            null
        }
    }

    override suspend fun listDir(dirPath: String): List<String> {
        return try {
            val res = Shell.cmd("ls -1 '$dirPath' 2>/dev/null").exec()
            if (res.isSuccess) res.out.map { it.trim() }.filter { it.isNotEmpty() } else emptyList()
        } catch (_: Throwable) {
            emptyList()
        }
    }
}

/**
 * Qualcomm strategy (own method, no MTK OPP interface):
 * 1. If the cluster is pinned with `min == max` (performance governor / perfd boost), the kernel
 *    rejects a lower ceiling while min sits above it, so lift `scaling_min_freq` to the target first.
 * 2. If the governor is still `performance`, switch it to a neutral governor so perfd stops
 *    re-raising `scaling_max_freq` on the next input boost.
 * 3. Rewrite `scaling_max_freq`, verify read-back, and report when the thermal engine/perfd still
 *    clamps it (CoolMax never fights or disables vendor thermal daemons).
 */
class LibsuQualcommFallbackWriter(
    private val shellExecutor: SysfsShellExecutor
) : QualcommFallbackWriter {

    override suspend fun resetMaxFreq(scalingMaxPath: String, maxFreqKhz: Long): FallbackOutcome? {
        val policyDir = scalingMaxPath.substringBeforeLast("/")
        val minPath = "$policyDir/scaling_min_freq"
        val govPath = "$policyDir/scaling_governor"
        val currentMax = shellExecutor.readNode(scalingMaxPath)?.trim()?.toLongOrNull() ?: return null
        if (currentMax == 0L || maxFreqKhz >= currentMax) return null

        val steps = mutableListOf<String>()

        // Step 1: free a pinned min==max band so the kernel accepts a lower ceiling.
        val currentMin = shellExecutor.readNode(minPath)?.trim()?.toLongOrNull()
        if (currentMin != null && currentMin >= maxFreqKhz) {
            val lowered = shellExecutor.writeNode(
                "chmod 0644 '$minPath' 2>/dev/null; echo '$maxFreqKhz' > '$minPath'"
            )
            if (lowered) steps.add("scaling_min_freq neutralized to $maxFreqKhz")
        }

        // Step 2: stop `performance` from pinning min==max again.
        val governor = shellExecutor.readNode(govPath)?.trim()
        if (governor == "performance") {
            val available = shellExecutor.readNode("$policyDir/scaling_available_governors")
                ?.split(Regex("\\s+"))?.filter { it.isNotBlank() }.orEmpty()
            val neutral = listOf("schedutil", "interactive", "ondemand", "conservative")
                .firstOrNull { it in available }
            if (neutral != null && shellExecutor.writeNode(
                    "chmod 0644 '$govPath' 2>/dev/null; echo '$neutral' > '$govPath'"
                )
            ) {
                steps.add("governor performance -> $neutral")
            }
        }

        // Step 3: write and verify the ceiling.
        val maxOk = shellExecutor.writeNode(
            "chmod 0644 '$scalingMaxPath' 2>/dev/null; echo '$maxFreqKhz' > '$scalingMaxPath'"
        )
        val readBack = shellExecutor.readNode(scalingMaxPath)?.trim()?.toLongOrNull()
        val verified = maxOk && readBack == maxFreqKhz
        steps.add(
            if (verified) "scaling_max_freq=$maxFreqKhz verified"
            else "scaling_max_freq still clamped (read ${readBack ?: "unreadable"}); thermal engine/perfd override"
        )
        return FallbackOutcome(
            success = verified,
            method = "Qualcomm min/max + governor neutralization",
            detail = steps.joinToString("; ")
        )
    }
}
