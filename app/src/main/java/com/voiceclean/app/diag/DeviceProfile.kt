package com.voiceclean.app.diag

import android.app.ActivityManager
import android.content.Context
import android.os.Build

enum class SocVendor { QUALCOMM, SAMSUNG_EXYNOS, MEDIATEK, GOOGLE, OTHER }

/** Rough speed class of the CPU for whisper/llama inference. */
enum class DeviceTier(val label: String) {
    FLAGSHIP("Flagship"), HIGH("High-end"), MID("Mid-range"), LOW("Entry-level"),
}

data class DeviceProfile(
    val vendor: SocVendor,
    val socName: String,
    val tier: DeviceTier,
    val ramMb: Int,
    val perfCores: Int,
    /** Qualcomm Hexagon NPU (QNN) is plausibly available. */
    val qualcomm: Boolean,
    /** Ids from ModelCatalog (STT), best first. */
    val recommendedStt: List<String>,
    val summary: String,
) {
    companion object {
        fun detect(context: Context): DeviceProfile {
            val mi = ActivityManager.MemoryInfo()
            (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
            return from(Build.SOC_MANUFACTURER, Build.SOC_MODEL, Build.HARDWARE, (mi.totalMem / (1024 * 1024)).toInt(), CpuInfo.performanceCores())
        }

        /** Pure and testable. */
        fun from(manufacturer: String, model: String, hardware: String, ramMb: Int, perfCores: Int): DeviceProfile {
            val m = model.uppercase()
            val mf = manufacturer.uppercase()
            val vendor = when {
                mf.contains("QUALCOMM") || m.startsWith("SM") || m.startsWith("SDM") || m.startsWith("QCS") || hardware.equals("qcom", true) -> SocVendor.QUALCOMM
                mf.contains("SAMSUNG") || m.startsWith("EXYNOS") || m.startsWith("S5E") -> SocVendor.SAMSUNG_EXYNOS
                mf.contains("MEDIATEK") || m.startsWith("MT") -> SocVendor.MEDIATEK
                mf.contains("GOOGLE") || m.startsWith("GS") || m.startsWith("ZUMA") -> SocVendor.GOOGLE
                else -> SocVendor.OTHER
            }
            val tier = tierOf(vendor, m, ramMb, perfCores)
            val rec = when (tier) {
                DeviceTier.FLAGSHIP -> listOf("stt-large-v3-turbo-q5_0", "stt-medium-en-q8_0", "stt-small-en-q8_0")
                DeviceTier.HIGH -> listOf("stt-small-en-q8_0", "stt-large-v3-turbo-q5_0", "stt-small-en")
                DeviceTier.MID -> listOf("stt-small-en-q8_0", "stt-base-en")
                DeviceTier.LOW -> listOf("stt-base-en", "stt-small-en-q8_0")
            }.let { l -> if (ramMb in 1..3999) l.filterNot { it.startsWith("stt-large") || it.startsWith("stt-medium") } else l }
            val name = listOf(manufacturer, model).filter { it.isNotBlank() && it != "unknown" }.joinToString(" ").ifBlank { hardware }
            return DeviceProfile(vendor, name, tier, ramMb, perfCores, vendor == SocVendor.QUALCOMM, rec,
                "$name - ${tier.label}, ${String.format("%.1f", ramMb / 1024.0)} GB RAM, $perfCores performance cores")
        }

        private fun tierOf(v: SocVendor, m: String, ramMb: Int, perf: Int): DeviceTier {
            if (v == SocVendor.QUALCOMM) {
                // SM8850 (8 Elite Gen 5), SM8750 (8 Elite), SM8650 (8 Gen 3), SM8635/8550 (8s Gen 3 / 8 Gen 2): flagship-class.
                val flagship = listOf("SM8850", "SM8845", "SM8750", "SM8735", "SM8650", "SM8635", "SM8550")
                if (flagship.any { m.startsWith(it) }) return DeviceTier.FLAGSHIP
                if (m.startsWith("SM8475") || m.startsWith("SM8450") || m.startsWith("SM7675") || m.startsWith("SM7550") || m.startsWith("SM8350")) return DeviceTier.HIGH
                if (m.startsWith("SM7")) return DeviceTier.MID
                if (m.startsWith("SM6") || m.startsWith("SM4")) return DeviceTier.LOW
            }
            // Unknown SoC: judge by RAM and core count.
            return when {
                ramMb >= 11000 && perf >= 4 -> DeviceTier.HIGH
                ramMb >= 6000 -> DeviceTier.MID
                else -> DeviceTier.LOW
            }
        }
    }
}
