package id.coolmax.app.data.sysfs

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MtkOppFallbackWriterTest {

    private val littleFreqs = listOf(1075000L, 1175000L, 1200000L, 1500000L, 1800000L, 1933000L, 2000000L)
        .sorted()

    private val littleOppDump = """
        cpufreq_oppidx = 7
        [0] 2000000 KHz, 1137500 uV
        [1] 1933000 KHz, 1100000 uV
        [2] 1800000 KHz, 1000000 uV
        [3] 1500000 KHz, 900000 uV
        [4] 1200000 KHz, 850000 uV
        [5] 1175000 KHz, 800000 uV
        [6] 1075000 KHz, 780000 uV
    """.trimIndent()

    private val bigOppDump = """
        cpufreq_oppidx = 3
        [0] 2050000 KHz, 1150000 uV
        [1] 2000000 KHz, 1137500 uV
        [2] 1800000 KHz, 1000000 uV
        [3] 1500000 KHz, 900000 uV
    """.trimIndent()

    private class FakeProcShell(
        private val files: Map<String, String>,
        private val procEntries: List<String> = listOf("MT_CPU_DVFS_LL", "MT_CPU_DVFS_L")
    ) : SysfsShellExecutor {
        val writes = mutableMapOf<String, String>()

        override suspend fun writeNode(command: String): Boolean {
            val match = Regex("echo '([^']+)' > '([^']+)'").find(command) ?: return false
            writes[match.groupValues[2]] = match.groupValues[1]
            return true
        }

        override suspend fun readNode(path: String): String? = writes[path] ?: files[path]

        override suspend fun listDir(dirPath: String): List<String> =
            if (dirPath == LibsuMtkOppFallbackWriter.MTK_CPUFREQ_PROC_DIR) procEntries else emptyList()
    }

    @Test
    fun `parses bracketed MTK OPP dump and ignores voltage values`() {
        val writer = LibsuMtkOppFallbackWriter(FakeProcShell(emptyMap()))
        val table = writer.parseOppTable(littleOppDump, littleFreqs)

        assertEquals(7, table.size)
        assertEquals(0, table.first().index)
        assertEquals(2000000L, table.first().freqKhz)
        assertEquals(6, table.last().index)
        assertEquals(1075000L, table.last().freqKhz)
    }

    @Test
    fun `derives descending index table when proc dump cannot be parsed`() = runTest {
        val shell = FakeProcShell(
            mapOf(
                "/proc/cpufreq/MT_CPU_DVFS_LL/cpufreq_oppidx" to "permission denied",
                "/proc/cpufreq/MT_CPU_DVFS_LL/scaling_available_frequencies" to littleFreqs.joinToString(" "),
                "/proc/cpufreq/MT_CPU_DVFS_LL/cpuinfo_max_freq" to "2000000"
            )
        )
        val writer = LibsuMtkOppFallbackWriter(shell)
        val table = writer.resolveOppTable("/proc/cpufreq/MT_CPU_DVFS_LL", littleFreqs)

        assertEquals(littleFreqs.size, table.size)
        assertEquals(0, table.first().index)
        assertEquals(2000000L, table.first().freqKhz)
        assertEquals(1075000L, table.last().freqKhz)
    }

    @Test
    fun `limits the little cluster by writing the OPP index of the target frequency`() = runTest {
        val policyDir = "/sys/devices/system/cpu/cpufreq/policy0"
        val shell = FakeProcShell(
            mapOf(
                "$policyDir/scaling_available_frequencies" to littleFreqs.joinToString(" "),
                "$policyDir/cpuinfo_max_freq" to "2000000",
                "$policyDir/scaling_max_freq" to "2000000",
                "/proc/cpufreq/MT_CPU_DVFS_LL/cpufreq_oppidx" to littleOppDump,
                "/proc/cpufreq/MT_CPU_DVFS_L/cpufreq_oppidx" to bigOppDump
            )
        )
        val writer = LibsuMtkOppFallbackWriter(shell)

        val outcome = writer.limitMaxFreq("$policyDir/scaling_max_freq", 1175000L)

        assertNotNull(outcome)
        assertTrue(outcome!!.detail, outcome.success)
        // 1175000 kHz is OPP index 5 in the little-cluster table
        assertEquals("5", shell.writes["/proc/cpufreq/MT_CPU_DVFS_LL/cpufreq_oppidx"])
        assertTrue(outcome.detail, outcome.detail.contains("oppidx=5"))
    }
}
