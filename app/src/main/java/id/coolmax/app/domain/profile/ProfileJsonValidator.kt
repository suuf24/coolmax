package id.coolmax.app.domain.profile

import id.coolmax.app.domain.model.CpuCluster
import id.coolmax.app.domain.model.CpuProfile
import id.coolmax.app.domain.safety.SafeFrequencyPlanner
import id.coolmax.app.domain.safety.ValidationPlanResult
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Handles JSON export and strict safety-checked JSON import of [CpuProfile] definitions.
 * Rejects malformed JSON, empty names, shell metacharacters, or unsafe frequencies/cores.
 */
object ProfileJsonValidator {

    private val jsonFormat = Json {
        prettyPrint = true
        ignoreUnknownKeys = false
        encodeDefaults = true
    }

    private val SAFE_NAME_REGEX = Regex("^[a-zA-Z0-9 _\\-().]{1,50}$")

    sealed class ImportResult {
        data class Success(val profile: CpuProfile, val warnings: List<String>) : ImportResult()
        data class Invalid(val error: String) : ImportResult()
    }

    fun exportToJson(profile: CpuProfile): String {
        return jsonFormat.encodeToString(profile)
    }

    fun validateAndImport(
        rawJson: String,
        liveClusters: List<CpuCluster>
    ): ImportResult {
        if (rawJson.isBlank()) {
            return ImportResult.Invalid("JSON string is empty.")
        }
        val parsed = try {
            jsonFormat.decodeFromString<CpuProfile>(rawJson)
        } catch (e: Exception) {
            return ImportResult.Invalid("Malformed profile JSON: ${e.message}")
        }

        if (!SAFE_NAME_REGEX.matches(parsed.name.trim())) {
            return ImportResult.Invalid(
                "Profile name '${parsed.name}' contains invalid characters or exceeds 50 chars."
            )
        }

        if (parsed.clusterConfigs.isEmpty()) {
            return ImportResult.Invalid("Profile contains no cluster configurations.")
        }

        val clusterMap = liveClusters.associateBy { it.policyId }
        val allWarnings = mutableListOf<String>()

        for ((policyId, config) in parsed.clusterConfigs) {
            if (config.policyId != policyId) {
                return ImportResult.Invalid("Mismatched policyId key ($policyId vs ${config.policyId}).")
            }
            val liveCluster = clusterMap[policyId]
                ?: return ImportResult.Invalid("Profile targets policy$policyId which does not exist on this device.")

            when (val plan = SafeFrequencyPlanner.validateAndPlan(liveCluster, config)) {
                is ValidationPlanResult.Rejected -> {
                    return ImportResult.Invalid("Unsafe config for policy$policyId: ${plan.errorReason}")
                }
                is ValidationPlanResult.Valid -> {
                    allWarnings.addAll(plan.warnings)
                }
            }
        }

        return ImportResult.Success(profile = parsed, warnings = allWarnings)
    }
}
