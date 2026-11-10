package id.coolmax.app.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkModeControllerTest {

    @Test
    fun `parses every preferred network mode entry and ignores unrelated settings`() {
        val raw = """
            airplane_mode_on=0
            preferred_network_mode=9
            preferred_network_mode1=9
            preferred_network_mode19=32,20
            user_preferred_network_mode1=9
            preferred_network_mode_wifi=1
            data_enabled=1
        """.trimIndent()

        val parsed = NetworkModeController.parseNetworkModeSettings(raw)

        assertEquals(
            mapOf(
                "preferred_network_mode" to "9",
                "preferred_network_mode1" to "9",
                "preferred_network_mode19" to "32,20",
                "user_preferred_network_mode1" to "9"
            ),
            parsed
        )
    }

    @Test
    fun `parses output without values or separators as empty`() {
        assertEquals(emptyMap<String, String>(), NetworkModeController.parseNetworkModeSettings(""))
        assertEquals(
            emptyMap<String, String>(),
            NetworkModeController.parseNetworkModeSettings("preferred_network_mode")
        )
    }

    @Test
    fun `restore writes saved values, deletes absent keys, and skips unsafe values`() {
        val commands = NetworkModeController.buildRestoreCommands(
            mapOf(
                "preferred_network_mode" to "9",
                "preferred_network_mode1" to NetworkModeController.ABSENT_VALUE,
                "preferred_network_mode2" to "1; reboot"
            )
        )

        assertEquals(
            listOf(
                "settings put global preferred_network_mode 9",
                "settings delete global preferred_network_mode1"
            ),
            commands
        )
    }

    @Test
    fun `restore keeps comma separated RAT masks used by extended network mode keys`() {
        val commands = NetworkModeController.buildRestoreCommands(
            mapOf("preferred_network_mode19" to "32,20,11")
        )

        assertEquals(
            listOf("settings put global preferred_network_mode19 32,20,11"),
            commands
        )
    }

    @Test
    fun `allowed network types are converted back to the binary mask the shell command expects`() {
        assertEquals(
            NetworkModeController.NATIVE_GSM_ONLY_BITMASK,
            NetworkModeController.allowedTypesToBitmask("GSM")
        )
        // GPRS(1) | EDGE(2) | UMTS(3) | HSDPA(8) | HSUPA(9) | HSPA(10) | LTE(13) | HSPA+(15) | GSM(16) | LTE_CA(19)
        assertEquals(
            "1001101001110000111",
            NetworkModeController.allowedTypesToBitmask(
                "GPRS|EDGE|UMTS|HSDPA|HSUPA|HSPA|LTE|HSPA+|GSM|LTE_CA"
            )
        )
        assertNull(NetworkModeController.allowedTypesToBitmask("NOT_A_TYPE"))
        assertNull(NetworkModeController.allowedTypesToBitmask(""))
    }

    @Test
    fun `allowed network types values are recognised and error lines are ignored`() {
        assertTrue(NetworkModeController.isAllowedTypesValue("GSM"))
        assertTrue(NetworkModeController.isAllowedTypesValue("LTE|GSM"))
        assertFalse(NetworkModeController.isAllowedTypesValue("No valid NETWORK_TYPES_BITMASK"))
        assertFalse(NetworkModeController.isAllowedTypesValue(""))
    }

    @Test
    fun `baseline mirror round trips every mode entry in a stable order`() {
        val baseline = mapOf("preferred_network_mode1" to "11", "preferred_network_mode" to "11")

        val encoded = NetworkModeController.encodeBaselineMirror(baseline)

        assertEquals("preferred_network_mode=11;preferred_network_mode1=11", encoded)
        assertEquals(baseline, NetworkModeController.decodeBaselineMirror(encoded))
    }

    @Test
    fun `baseline mirror drops unset unrelated and unvalidated entries`() {
        assertEquals(emptyMap<String, String>(), NetworkModeController.decodeBaselineMirror("null"))
        assertEquals(
            mapOf("preferred_network_mode" to "9"),
            NetworkModeController.decodeBaselineMirror(
                "preferred_network_mode=9;data_enabled=1;preferred_network_mode1=rm -rf;"
            )
        )
    }

    @Test
    fun `allowed types mirror round trips both SIM slots`() {
        val baseline = mapOf(1 to "LTE|LTE_CA", 0 to "GSM")

        val encoded = NetworkModeController.encodeAllowedTypesMirror(baseline)

        assertEquals("0=GSM;1=LTE|LTE_CA", encoded)
        assertEquals(baseline, NetworkModeController.decodeAllowedTypesMirror(encoded))
    }

    @Test
    fun `allowed types mirror drops unknown slots and malformed values`() {
        assertEquals(emptyMap<Int, String>(), NetworkModeController.decodeAllowedTypesMirror("null"))
        assertEquals(
            mapOf(0 to "GSM"),
            NetworkModeController.decodeAllowedTypesMirror("0=GSM;7=LTE;1=not-a-network")
        )
    }

    @Test
    fun `GSM only modes are recognised so they are never saved as the user default`() {
        assertTrue(NetworkModeController.isGsmOnlyModeValue("1"))
        assertTrue(NetworkModeController.isGsmOnlyModeValue("1, 1"))
        assertFalse(NetworkModeController.isGsmOnlyModeValue("11"))
        assertFalse(NetworkModeController.isGsmOnlyModeValue("9"))
        assertFalse(NetworkModeController.isGsmOnlyModeValue("1,20"))
        assertFalse(NetworkModeController.isGsmOnlyModeValue(""))
    }

    @Test
    fun `GSM only allowed types are recognised per slot`() {
        assertTrue(NetworkModeController.isGsmOnlyTypes("GSM"))
        assertTrue(NetworkModeController.isGsmOnlyTypes("GSM|GPRS|EDGE"))
        assertFalse(NetworkModeController.isGsmOnlyTypes("LTE|LTE_CA"))
        assertFalse(NetworkModeController.isGsmOnlyTypes("GSM|LTE"))
        assertFalse(NetworkModeController.isGsmOnlyTypes(""))
    }

    @Test
    fun `safe default mask keeps LTE available so a wiped install is not stuck on 2G`() {
        assertEquals(
            "1001000000000100",
            NetworkModeController.allowedTypesToBitmask(NetworkModeController.SAFE_DEFAULT_TYPES)
        )
        assertTrue(NetworkModeController.SAFE_DEFAULT_TYPES.split('|').contains("LTE"))
        assertEquals("9", NetworkModeController.SAFE_DEFAULT_MODE)
    }
}
