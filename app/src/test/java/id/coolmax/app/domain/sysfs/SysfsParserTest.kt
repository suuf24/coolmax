package id.coolmax.app.domain.sysfs

import id.coolmax.app.domain.model.ClusterTier
import id.coolmax.app.domain.model.ThermalSensorReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SysfsParserTest {

    @Test
    fun `parseFrequencies sorts ascending, removes duplicates and ignores invalid tokens`() {
        val raw = "1804800 307200  1113600 614400 307200 invalid -500 0\n1401600 "
        val parsed = SysfsParser.parseFrequencies(raw)
        assertEquals(listOf(307200L, 614400L, 1113600L, 1401600L, 1804800L), parsed)
    }

    @Test
    fun `parseTimeInState converts 10ms jiffies to milliseconds and sorts by frequency`() {
        val raw = """
            1401600 250
            307200 1200
            614400 50
        """.trimIndent()
        val entries = SysfsParser.parseTimeInState(raw)
        assertEquals(3, entries.size)
        assertEquals(307200L, entries[0].frequencyKhz)
        assertEquals(12000L, entries[0].timeMs)
        assertEquals(614400L, entries[1].frequencyKhz)
        assertEquals(500L, entries[1].timeMs)
        assertEquals(1401600L, entries[2].frequencyKhz)
        assertEquals(2500L, entries[2].timeMs)
    }

    @Test
    fun `resolveAvailableFrequencies falls back to stats time_in_state when scaling_available_frequencies is missing`() {
        val timeInState = """
            806400 100
            1497600 400
            2841600 20
        """.trimIndent()
        val resolved = SysfsParser.resolveAvailableFrequencies(
            scalingAvailableFreqsRaw = null,
            boostFreqsRaw = null,
            timeInStateRaw = timeInState
        )
        assertEquals(listOf(806400L, 1497600L, 2841600L), resolved)
    }

    @Test
    fun `parseGovernors parses valid governor tokens and rejects shell metacharacters`() {
        val raw = "schedutil powersave performance conservative bad;gov"
        val govs = SysfsParser.parseGovernors(raw)
        assertEquals(listOf("schedutil", "powersave", "performance", "conservative"), govs)
    }

    @Test
    fun `parseCpuList supports both range syntax and whitespace syntax`() {
        assertEquals(listOf(0, 1, 2, 3, 6, 7), SysfsParser.parseCpuList("0-3,6-7"))
        assertEquals(listOf(4, 5, 6), SysfsParser.parseCpuList("4 5 6"))
        assertTrue(SysfsParser.parseCpuList("").isEmpty())
    }

    @Test
    fun `classifyClusterTiers assigns LITTLE, BIG, and PRIME dynamically by max frequency`() {
        val threeClusterMap = mapOf(
            0 to 1804800L,
            4 to 2419200L,
            7 to 2841600L
        )
        val tiers = SysfsParser.classifyClusterTiers(threeClusterMap)
        assertEquals(ClusterTier.LITTLE, tiers[0])
        assertEquals(ClusterTier.BIG, tiers[4])
        assertEquals(ClusterTier.PRIME, tiers[7])
    }

    @Test
    fun `buildClustersFromFallbackCpuNodes groups per-core cpufreq nodes into logical clusters`() {
        val nodes = listOf(
            SysfsParser.RawCpuNodeData(
                cpuId = 0,
                cpufreqPath = "/sys/devices/system/cpu/cpu0/cpufreq",
                relatedCpusRaw = "0 1 2 3",
                affectedCpusRaw = "0 1 2 3",
                curFreqRaw = "1113600",
                minFreqRaw = "307200",
                maxFreqRaw = "1804800",
                cpuinfoMinRaw = "307200",
                cpuinfoMaxRaw = "1804800",
                governorRaw = "schedutil",
                availableFreqsRaw = "307200 1113600 1804800",
                availableGovsRaw = "schedutil powersave",
                timeInStateRaw = null
            ),
            SysfsParser.RawCpuNodeData(
                cpuId = 1,
                cpufreqPath = "/sys/devices/system/cpu/cpu1/cpufreq",
                relatedCpusRaw = "0 1 2 3",
                affectedCpusRaw = "0 1 2 3",
                curFreqRaw = "1113600",
                minFreqRaw = "307200",
                maxFreqRaw = "1804800",
                cpuinfoMinRaw = "307200",
                cpuinfoMaxRaw = "1804800",
                governorRaw = "schedutil",
                availableFreqsRaw = "307200 1113600 1804800",
                availableGovsRaw = "schedutil powersave",
                timeInStateRaw = null
            ),
            SysfsParser.RawCpuNodeData(
                cpuId = 4,
                cpufreqPath = "/sys/devices/system/cpu/cpu4/cpufreq",
                relatedCpusRaw = "4 5 6 7",
                affectedCpusRaw = "4 5 6 7",
                curFreqRaw = "1497600",
                minFreqRaw = "633600",
                maxFreqRaw = "2419200",
                cpuinfoMinRaw = "633600",
                cpuinfoMaxRaw = "2419200",
                governorRaw = "schedutil",
                availableFreqsRaw = "633600 1497600 2419200",
                availableGovsRaw = "schedutil performance",
                timeInStateRaw = null
            )
        )

        val clusters = SysfsParser.buildClustersFromFallbackCpuNodes(
            nodes = nodes,
            onlineCpus = listOf(0, 1, 2, 4, 5, 6, 7)
        )
        assertEquals(2, clusters.size)
        assertEquals(ClusterTier.LITTLE, clusters[0].tier)
        assertEquals(listOf(0, 1, 2), clusters[0].onlineCpus)
        assertEquals(ClusterTier.BIG, clusters[1].tier)
        assertEquals(2419200L, clusters[1].maxFreqKhz)
    }

    @Test
    fun `parseThermalTemperatureCelsius converts millidegrees and filters implausible readings`() {
        assertEquals(43.5f, SysfsParser.parseThermalTemperatureCelsius("43500") ?: 0f, 0.01f)
        assertEquals(41.0f, SysfsParser.parseThermalTemperatureCelsius("41") ?: 0f, 0.01f)
        assertNull(SysfsParser.parseThermalTemperatureCelsius("250000")) // 250C is implausible
        assertNull(SysfsParser.parseThermalTemperatureCelsius("invalid"))

        val sensors = listOf(
            ThermalSensorReading("thermal_zone0", "cpu-cluster0-therm", 44.2f),
            ThermalSensorReading("thermal_zone1", "battery", 33.0f)
        )
        val filtered = SysfsParser.filterCpuThermalReadings(sensors)
        assertEquals(1, filtered.size)
        assertEquals("cpu-cluster0-therm", filtered.first().type)
    }
}
