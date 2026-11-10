package id.coolmax.app.data.root

import android.os.Build

/**
 * SoC vendor classification used to pick the correct maximum-frequency strategy.
 *
 * - MEDIATEK vendors (PDA/MediaTek PPM + MTkt thermal engine) clamp `scaling_max_freq`, so CoolMax
 *   must fall back to `/proc/cpufreq/MT_CPU_DVFS_*` + `cpufreq_oppidx`.
 * - QUALCOMM kernels respect `scaling_max_freq`, but thermal-engine/perfd may clamp it back; CoolMax
 *   uses its own verified sequence (neutralize min first, verified writes) and transparently reports
 *   remaining overrides.
 * - OTHER: generic upstream Android cpufreq behavior.
 */
enum class SocVendor {
    MEDIATEK,
    QUALCOMM,
    OTHER;

    companion object {
        fun fromProps(
            mediatekPlatform: String,
            boardPlatform: String,
            socManufacturer: String
        ): SocVendor {
            val mtk = listOf(mediatekPlatform, boardPlatform, socManufacturer)
                .any { it.isNotBlank() && (it.startsWith("mt", ignoreCase = true) || it.contains("mediatek", ignoreCase = true)) }
            if (mtk) return MEDIATEK

            val qualcommRegex = Regex("(^|\\s)(qcom|msm\\d+|sdm\\d+|sm\\d+|apq\\d+|qualcomm)")
            val qc = listOf(boardPlatform, socManufacturer).any { value ->
                qualcommRegex.containsMatchIn(value.lowercase())
            }
            if (qc) return QUALCOMM

            return OTHER
        }

        /**
         * Detects the SoC vendor from stable Android build fields, without requiring a root shell.
         * On MT6785 (Redmi Note 10 / rosemary) `Build.HARDWARE` is `mt6785` and
         * `Build.SOC_MANUFACTURER` is `Mediatek`.
         */
        fun detect(): SocVendor {
            val manufacturer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Build.SOC_MANUFACTURER.orEmpty()
            } else {
                ""
            }
            return fromProps(
                mediatekPlatform = manufacturer,
                boardPlatform = listOfNotNull(Build.HARDWARE, Build.BOARD).joinToString(" "),
                socManufacturer = manufacturer
            )
        }
    }
}
