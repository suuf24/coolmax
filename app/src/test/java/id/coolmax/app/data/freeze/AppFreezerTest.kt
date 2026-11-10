package id.coolmax.app.data.freeze

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppFreezerTest {

    @Test
    fun `parses running app uids and ignores system and malformed lines`() {
        val raw = """
            1000 412
            10123 1201
            10123 1202
            10145 1500
            2000 22

        """.trimIndent()

        val parsed = AppFreezer.parseRunningUids(raw)

        assertEquals(listOf(1201, 1202), parsed[10123])
        assertEquals(listOf(1500), parsed[10145])
        assertEquals(setOf(10123, 10145), parsed.keys)
    }

    @Test
    fun `ignores isolated uids and header noise`() {
        val raw = "UID PID\n20000 900\n10199 abc\n10198 77"

        val parsed = AppFreezer.parseRunningUids(raw)

        assertEquals(setOf(10198), parsed.keys)
    }

    @Test
    fun `freeze script targets every requested uid and has no unresolved placeholder`() {
        val script = AppFreezer.buildFreezeScript(listOf(10145, 10123))

        assertFalse(script.contains('§'))
        // The script is generic over $u; the UID list is what carries the targets.
        assertTrue(script.contains("/sys/fs/cgroup/uid_\$u/cgroup.freeze"))
        assertTrue(script.contains("/sys/fs/cgroup/freezer/uid_\$u/freezer.state"))
        assertTrue(script.contains("/dev/freezer/uid_\$u/freezer.state"))
        assertTrue(script.contains("kill -STOP"))
        // Sorted, deterministic UID list
        assertEquals("UIDS=\"10123 10145\"", script.lineSequence().first())
    }

    @Test
    fun `unfreeze script thaws cgroups and releases sigstop`() {
        val script = AppFreezer.buildUnfreezeScript(listOf(10123))

        assertFalse(script.contains('§'))
        assertTrue(script.contains("echo 0 > \"/sys/fs/cgroup/uid_\$u/cgroup.freeze\""))
        assertTrue(script.contains("echo THAWED > \"/sys/fs/cgroup/freezer/uid_\$u/freezer.state\""))
        assertTrue(script.contains("kill -CONT"))
        assertEquals("UIDS=\"10123\"", script.lineSequence().first())
    }

    @Test
    fun `empty uid list produces a no-op script`() {
        assertEquals("true", AppFreezer.buildFreezeScript(emptyList()))
        assertEquals("true", AppFreezer.buildUnfreezeScript(emptyList()))
    }

    @Test
    fun `parses both freezer mechanisms from the script output`() {
        val raw = """
            CG: 10123 10145
            SG: 10199
        """.trimIndent()

        assertEquals(setOf(10123, 10145, 10199), AppFreezer.parseFreezeOutput(raw))
        assertEquals(emptySet<Int>(), AppFreezer.parseFreezeOutput("something else"))
    }
}
