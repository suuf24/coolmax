package id.coolmax.app.domain.trigger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GovernorPriorityResolverTest {

    @Test
    fun `applies the per-app governor when the configured app is in front`() {
        val decision = GovernorPriorityResolver.resolve(
            GovernorPriorityInput(
                isScreenOff = false,
                perAppEnabled = true,
                foregroundPackageName = "com.example.game",
                foregroundPerAppGovernor = "performance",
                activePerAppPackage = null
            )
        )
        assertEquals(GovernorDecision.ApplyPerApp("com.example.game", "performance"), decision)
    }

    @Test
    fun `does not rewrite sysfs when the same app is already active`() {
        val decision = GovernorPriorityResolver.resolve(
            GovernorPriorityInput(
                isScreenOff = false,
                perAppEnabled = true,
                foregroundPackageName = "com.example.game",
                foregroundPerAppGovernor = "performance",
                activePerAppPackage = "com.example.game"
            )
        )
        assertEquals(GovernorDecision.None, decision)
    }

    @Test
    fun `restores the global governor when leaving the configured app`() {
        val decision = GovernorPriorityResolver.resolve(
            GovernorPriorityInput(
                isScreenOff = false,
                perAppEnabled = true,
                foregroundPackageName = "com.android.launcher",
                foregroundPerAppGovernor = null,
                activePerAppPackage = "com.example.game"
            )
        )
        assertEquals(GovernorDecision.RestoreGlobal, decision)
    }

    @Test
    fun `screen-off profile wins over per-app governor`() {
        val decision = GovernorPriorityResolver.resolve(
            GovernorPriorityInput(
                isScreenOff = true,
                perAppEnabled = true,
                foregroundPackageName = "com.example.game",
                foregroundPerAppGovernor = "performance",
                activePerAppPackage = "com.example.game"
            )
        )
        assertEquals(GovernorDecision.None, decision)
    }

    @Test
    fun `disabling the feature restores the global governor`() {
        val decision = GovernorPriorityResolver.resolve(
            GovernorPriorityInput(
                isScreenOff = false,
                perAppEnabled = false,
                foregroundPackageName = "com.example.game",
                foregroundPerAppGovernor = "performance",
                activePerAppPackage = "com.example.game"
            )
        )
        assertEquals(GovernorDecision.RestoreGlobal, decision)
    }

    @Test
    fun `nothing happens without an app match and without an active rule`() {
        val decision = GovernorPriorityResolver.resolve(
            GovernorPriorityInput(
                isScreenOff = false,
                perAppEnabled = true,
                foregroundPackageName = "com.example.other",
                foregroundPerAppGovernor = null,
                activePerAppPackage = null
            )
        )
        assertEquals(GovernorDecision.None, decision)
    }

    @Test
    fun `unavailable governors are rejected before they reach sysfs`() {
        assertTrue(GovernorPriorityResolver.isGovernorSupported("schedutil", listOf("schedutil", "powersave")))
        assertFalse(GovernorPriorityResolver.isGovernorSupported("performance", listOf("schedutil", "powersave")))
        assertFalse(GovernorPriorityResolver.isGovernorSupported("", listOf("schedutil")))
    }
}
