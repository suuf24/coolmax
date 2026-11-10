package id.coolmax.app.data.sysfs

import id.coolmax.app.domain.safety.SysfsWriteOperation
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SysfsWriterTest {

    private class FakeShellExecutor(
        private val simulateThermalOverrideOnPath: String? = null
    ) : SysfsShellExecutor {
        val state = mutableMapOf<String, String>()
        var writeCount = 0

        override suspend fun writeNode(command: String): Boolean {
            writeCount++
            // Extract value and path from: chmod 0644 '<path>' 2>/dev/null; echo '<val>' > '<path>'
            val regex = Regex("echo '([^']+)' > '([^']+)'")
            val match = regex.find(command) ?: return false
            val value = match.groupValues[1]
            val path = match.groupValues[2]
            if (path != simulateThermalOverrideOnPath) {
                state[path] = value
            } else {
                state[path] = "999999" // Simulate vendor/thermal HAL overriding value
            }
            return true
        }

        override suspend fun readNode(path: String): String? {
            return state[path]
        }
    }

    @Test
    fun `dryRun records commands without invoking shell executor`() = runTest {
        val fakeExecutor = FakeShellExecutor()
        val writer = SysfsWriter(fakeExecutor)
        val ops = listOf(
            SysfsWriteOperation(
                path = "/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq",
                value = "1804800",
                description = "Set max freq"
            )
        )

        val result = writer.executeOperations(ops, dryRun = true)
        assertTrue(result.allSucceeded)
        assertEquals(0, fakeExecutor.writeCount)
        assertEquals(1, result.executedCommands.size)
        assertTrue(result.operationLogs.first().contains("[DRY-RUN]"))
    }

    @Test
    fun `detects when kernel or thermal HAL overrides written sysfs value after retry`() = runTest {
        val targetPath = "/sys/devices/system/cpu/cpufreq/policy4/scaling_max_freq"
        val fakeExecutor = FakeShellExecutor(simulateThermalOverrideOnPath = targetPath)
        val writer = SysfsWriter(fakeExecutor)
        val ops = listOf(
            SysfsWriteOperation(
                path = targetPath,
                value = "2419200",
                description = "Set max freq"
            )
        )

        val result = writer.executeOperations(ops, dryRun = false)
        assertFalse(result.allSucceeded)
        assertTrue(result.overriddenBySystem)
        // Initial attempt + 1 retry = 2 write attempts
        assertEquals(2, fakeExecutor.writeCount)
    }

    @Test
    fun `blocks command injection and path traversal before touching shell`() = runTest {
        val fakeExecutor = FakeShellExecutor()
        val writer = SysfsWriter(fakeExecutor)
        val maliciousOps = listOf(
            SysfsWriteOperation(
                path = "/sys/devices/system/cpu/../../etc/passwd",
                value = "1804800",
                description = "Path traversal attempt"
            ),
            SysfsWriteOperation(
                path = "/sys/devices/system/cpu/cpufreq/policy0/scaling_governor",
                value = "schedutil; rm -rf /",
                description = "Command injection attempt"
            )
        )

        val result = writer.executeOperations(maliciousOps, dryRun = false)
        assertFalse(result.allSucceeded)
        assertEquals(0, fakeExecutor.writeCount)
        assertEquals(2, result.failedOperations.size)
    }
}
