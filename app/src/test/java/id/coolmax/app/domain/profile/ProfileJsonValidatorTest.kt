package id.coolmax.app.domain.profile

import id.coolmax.app.domain.model.ClusterConfig
import id.coolmax.app.domain.model.ClusterTier
import id.coolmax.app.domain.model.CpuCluster
import id.coolmax.app.domain.model.CpuProfile
import id.coolmax.app.domain.model.PresetType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileJsonValidatorTest {

    private val liveClusters = listOf(
        CpuCluster(
            policyId = 0,
            policyPath = "/sys/devices/system/cpu/cpufreq/policy0",
            tier = ClusterTier.LITTLE,
            affectedCpus = listOf(0, 1, 2, 3),
            relatedCpus = listOf(0, 1, 2, 3),
            onlineCpus = listOf(0, 1, 2, 3),
            currentFreqKhz = 1113600L,
            minFreqKhz = 307200L,
            maxFreqKhz = 1804800L,
            cpuinfoMinFreqKhz = 307200L,
            cpuinfoMaxFreqKhz = 1804800L,
            activeGovernor = "schedutil",
            availableFrequenciesKhz = listOf(307200L, 614400L, 1113600L, 1804800L),
            availableGovernors = listOf("schedutil", "powersave")
        )
    )

    @Test
    fun `exportToJson and validateAndImport round-trip valid profile`() {
        val original = CpuProfile(
            id = "prof-1",
            name = "My Balanced Profile",
            presetType = PresetType.CUSTOM,
            clusterConfigs = mapOf(
                0 to ClusterConfig(
                    policyId = 0,
                    minFreqKhz = 307200L,
                    maxFreqKhz = 1113600L,
                    governor = "schedutil"
                )
            )
        )
        val json = ProfileJsonValidator.exportToJson(original)
        val result = ProfileJsonValidator.validateAndImport(json, liveClusters)
        assertTrue(result is ProfileJsonValidator.ImportResult.Success)
        val imported = (result as ProfileJsonValidator.ImportResult.Success).profile
        assertEquals("My Balanced Profile", imported.name)
        assertEquals(1113600L, imported.clusterConfigs[0]?.maxFreqKhz)
    }

    @Test
    fun `validateAndImport rejects dangerous frequencies or cpu0 offline`() {
        val dangerousProfile = CpuProfile(
            id = "prof-bad",
            name = "Dangerous Profile",
            presetType = PresetType.CUSTOM,
            clusterConfigs = mapOf(
                0 to ClusterConfig(
                    policyId = 0,
                    minFreqKhz = 307200L,
                    maxFreqKhz = 307200L, // 17% of 1804800 (< 30% safety floor)
                    governor = "powersave",
                    offlineCores = listOf(0)
                )
            )
        )
        val json = ProfileJsonValidator.exportToJson(dangerousProfile)
        val result = ProfileJsonValidator.validateAndImport(json, liveClusters)
        assertTrue(result is ProfileJsonValidator.ImportResult.Invalid)
    }
}
