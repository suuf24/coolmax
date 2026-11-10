package id.coolmax.app.domain.trigger

/**
 * Inputs for governor-layer arbitration. Priorities are documented and enforced here:
 *
 * `Screen-Off Profile > Per-App Governor > Global governor`
 *
 * The thermal guard sits above all of them: it only lowers `scaling_max_freq`, so it can never be
 * overridden by a governor choice.
 */
data class GovernorPriorityInput(
    val isScreenOff: Boolean,
    val perAppEnabled: Boolean,
    val foregroundPackageName: String?,
    /** Governor configured for [foregroundPackageName], or null when the app has no rule. */
    val foregroundPerAppGovernor: String?,
    /** Package whose per-app governor is currently written to sysfs, if any. */
    val activePerAppPackage: String?
)

sealed interface GovernorDecision {
    data class ApplyPerApp(val packageName: String, val governor: String) : GovernorDecision

    /** The foreground app left a per-app rule: put the captured global governors back. */
    data object RestoreGlobal : GovernorDecision

    data object None : GovernorDecision
}

object GovernorPriorityResolver {

    fun resolve(input: GovernorPriorityInput): GovernorDecision {
        // Screen-Off profile owns the governor while the display is off.
        if (input.isScreenOff) return GovernorDecision.None

        if (!input.perAppEnabled) {
            return if (input.activePerAppPackage != null) GovernorDecision.RestoreGlobal else GovernorDecision.None
        }

        val foreground = input.foregroundPackageName?.takeIf { it.isNotBlank() }
        val governor = input.foregroundPerAppGovernor?.takeIf { it.isNotBlank() }

        if (foreground != null && governor != null) {
            // Debounce: never rewrite the same governor for the same package again.
            if (foreground == input.activePerAppPackage) return GovernorDecision.None
            return GovernorDecision.ApplyPerApp(foreground, governor)
        }

        if (input.activePerAppPackage != null) return GovernorDecision.RestoreGlobal
        return GovernorDecision.None
    }

    /**
     * A per-app rule can only be applied when at least one cluster exposes the governor, otherwise
     * the caller must warn the user instead of writing an unsupported governor.
     */
    fun isGovernorSupported(governor: String, availableGovernors: Collection<String>): Boolean =
        governor.isNotBlank() && governor in availableGovernors
}
