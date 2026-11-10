package id.coolmax.app.domain.safety

import id.coolmax.app.domain.model.ClusterConfig
import id.coolmax.app.domain.model.ClusterTier
import id.coolmax.app.domain.model.CpuCluster
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SafeFrequencyPlannerTest {

    private val sampleCluster = CpuCluster(
        policyId = 0,
        policyPath = "/sys/devices/system/cpu/cpufreq/policy0",
        tier = ClusterTier.LITTLE,
        affectedCpus = listOf(0, 1, 2, 3),
        relatedCpus = listOf(0, 1, 2, 3),
        onlineCpus = listOf(0, 1, 2, 3),
        currentFreqKhz = 1000000L,
        minFreqKhz = 600000L,
        maxFreqKhz = 1200000L,
        cpuinfoMinFreqKhz = 300000L,
        cpuinfoMaxFreqKhz = 2000000L,
        activeGovernor = "schedutil",
        availableFrequenciesKhz = listOf(300000L, 600000L, 800000L, 1000000L, 1200000L, 1600000L, 2000000L),
        availableGovernors = listOf("schedutil", "powersave", "performance")
    )

    @Test
    fun `planner writes governor first, then scaling_max_freq BEFORE scaling_min_freq when raising`() {
        // Current: min=600_000, max=1_200_000. Target: min=1_600_000, max=2_000_000
        val target = ClusterConfig(
            policyId = 0,
            minFreqKhz = 1600000L,
            maxFreqKhz = 2000000L,
            governor = "performance"
        )

        val result = SafeFrequencyPlanner.validateAndPlan(sampleCluster, target)
        assertTrue(result is ValidationPlanResult.Valid)
        val valid = result as ValidationPlanResult.Valid
        assertEquals("/sys/devices/system/cpu/cpufreq/policy0/scaling_governor", valid.operations[0].path)
        assertEquals("performance", valid.operations[0].value)
        assertEquals("/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq", valid.operations[1].path)
        assertEquals("2000000", valid.operations[1].value)
        assertEquals("/sys/devices/system/cpu/cpufreq/policy0/scaling_min_freq", valid.operations[2].path)
        assertEquals("1600000", valid.operations[2].value)
    }

    @Test
    fun `planner writes governor first, then scaling_min_freq BEFORE scaling_max_freq when lowering`() {
        // Current: min=600_000, max=1_200_000. Target: min=300_000, max=800_000 (40% of 2_000_000)
        val target = ClusterConfig(
            policyId = 0,
            minFreqKhz = 300000L,
            maxFreqKhz = 800000L,
            governor = "powersave"
        )

        val result = SafeFrequencyPlanner.validateAndPlan(sampleCluster, target)
        assertTrue(result is ValidationPlanResult.Valid)
        val valid = result as ValidationPlanResult.Valid
        assertEquals("/sys/devices/system/cpu/cpufreq/policy0/scaling_governor", valid.operations[0].path)
        assertEquals("powersave", valid.operations[0].value)
        assertEquals("/sys/devices/system/cpu/cpufreq/policy0/scaling_min_freq", valid.operations[1].path)
        assertEquals("300000", valid.operations[1].value)
        assertEquals("/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq", valid.operations[2].path)
        assertEquals("800000", valid.operations[2].value)
        // 800_000 is 40% of 2_000_000 (< 50%), so a warning must be emitted
        assertTrue(valid.warnings.isNotEmpty())
    }

    @Test
    fun `rejects configuration when minFreqKhz exceeds maxFreqKhz`() {
        val target = ClusterConfig(
            policyId = 0,
            minFreqKhz = 1600000L,
            maxFreqKhz = 1200000L,
            governor = "schedutil"
        )
        val result = SafeFrequencyPlanner.validateAndPlan(sampleCluster, target)
        assertTrue(result is ValidationPlanResult.Rejected)
    }

    @Test
    fun `rejects max frequency below 30 percent of cluster hardware ceiling`() {
        // 300_000 is 15% of 2_000_000 (< 30% safety floor)
        val target = ClusterConfig(
            policyId = 0,
            minFreqKhz = 300000L,
            maxFreqKhz = 300000L,
            governor = "powersave"
        )
        val result = SafeFrequencyPlanner.validateAndPlan(sampleCluster, target)
        assertTrue(result is ValidationPlanResult.Rejected)
        val reason = (result as ValidationPlanResult.Rejected).errorReason
        assertTrue(reason.contains("30%"))
    }

    @Test
    fun `rejects frequency or governor not present in available lists`() {
        val badFreqTarget = ClusterConfig(
            policyId = 0,
            minFreqKhz = 600000L,
            maxFreqKhz = 1555000L, // Not in availableFrequenciesKhz
            governor = "schedutil"
        )
        assertTrue(SafeFrequencyPlanner.validateAndPlan(sampleCluster, badFreqTarget) is ValidationPlanResult.Rejected)

        val badGovTarget = ClusterConfig(
            policyId = 0,
            minFreqKhz = 600000L,
            maxFreqKhz = 1600000L,
            governor = "userspace_nonexistent"
        )
        assertTrue(SafeFrequencyPlanner.validateAndPlan(sampleCluster, badGovTarget) is ValidationPlanResult.Rejected)
    }

    @Test
    fun `rejects offlining cpu0 or offlining all cores in a cluster`() {
        val offlineCpu0 = ClusterConfig(
            policyId = 0,
            minFreqKhz = 600000L,
            maxFreqKhz = 1600000L,
            governor = "schedutil",
            offlineCores = listOf(0)
        )
        assertTrue(SafeFrequencyPlanner.validateAndPlan(sampleCluster, offlineCpu0) is ValidationPlanResult.Rejected)

        val dualCoreCluster = sampleCluster.copy(
            policyId = 4,
            policyPath = "/sys/devices/system/cpu/cpufreq/policy4",
            relatedCpus = listOf(4, 5),
            affectedCpus = listOf(4, 5)
        )
        val offlineAllClusterCores = ClusterConfig(
            policyId = 4,
            minFreqKhz = 600000L,
            maxFreqKhz = 1600000L,
            governor = "schedutil",
            offlineCores = listOf(4, 5)
        )
        assertTrue(
            SafeFrequencyPlanner.validateAndPlan(dualCoreCluster, offlineAllClusterCores) is ValidationPlanResult.Rejected
        )
    }
}
