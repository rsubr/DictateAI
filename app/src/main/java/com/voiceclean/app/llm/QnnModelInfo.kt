package com.voiceclean.app.llm

import java.io.File

/**
 * Pure helpers for ExecuTorch/QNN `.pte` models (no Android or ExecuTorch classes, JVM-testable).
 * QNN context binaries inside a `.pte` are compiled for ONE Hexagon (HTP) architecture, so the file
 * name's `SMxxxx` tag must match the device SoC. See docs/QNN.md.
 */
object QnnModelInfo {
    enum class Family(val stopMarkers: List<String>) {
        QWEN(listOf("<|im_end|>", "<|endoftext|>")),
        LLAMA3(listOf("<|eot_id|>", "<|end_of_text|>")),
        PHI(listOf("<|end|>", "<|endoftext|>")),
    }

    /** Downloaded when no tokenizer sits next to the `.pte` (all ungated; sizes/SHA256 from the HF tree API 2026-10-26). */
    data class TokenizerSource(val url: String, val sizeBytes: Long, val sha256: String)

    private val SOC_TAG = Regex("""(?i)\bSM(\d{4})\b""")

    /** `Qwen-3-1.7B.SM8750.pte` -> `SM8750`; null if the name has no SoC tag. */
    fun socTag(fileName: String): String? = SOC_TAG.find(fileName)?.let { "SM" + it.groupValues[1] }

    /** Normalises Build.SOC_MODEL values such as "SM8750", "SM8750-AC" or "sm8650" to `SMxxxx`; null if unrecognised. */
    fun normalizeSoc(socModel: String?): String? = socModel?.let { SOC_TAG.find(it)?.let { m -> "SM" + m.groupValues[1] } }

    /** Hexagon architecture for a SoC (Snapdragon 8 / 7 series that have an HTP), null if unknown. */
    fun htpArch(soc: String?): Int? = when (soc?.uppercase()) {
        "SM8350" -> 68
        "SM8450", "SM7475" -> 69
        "SM8550", "SM8635", "SM7550" -> 73
        "SM8650" -> 75
        "SM8750" -> 79
        "SM8850" -> 81
        else -> null
    }

    fun family(fileName: String): Family? {
        val n = fileName.lowercase()
        return when {
            "qwen" in n -> Family.QWEN
            "llama" in n -> Family.LLAMA3
            "phi" in n -> Family.PHI
            else -> null
        }
    }

    fun tokenizerSource(fileName: String): TokenizerSource? {
        val n = fileName.lowercase()
        return when {
            "qwen-3" in n || "qwen3" in n -> TokenizerSource(
                "https://huggingface.co/Qwen/Qwen3-1.7B/resolve/main/tokenizer.json", 11_422_654,
                "aeb13307a71acd8fe81861d94ad54ab689df773318809eed3cbe794b4492dae4",
            )
            "llama-3.2" in n || "llama3.2" in n -> TokenizerSource(
                "https://huggingface.co/unsloth/Llama-3.2-3B-Instruct/resolve/main/tokenizer.json", 17_209_920,
                "6b9e4e7fb171f92fd137b777cc2714bf87d11576700a1dcd7a399e7bbe39537b",
            )
            "phi-4-mini" in n -> TokenizerSource(
                "https://huggingface.co/microsoft/Phi-4-mini-instruct/resolve/main/tokenizer.json", 15_524_095,
                "382cc235b56c725945e149cc25f191da667c836655efd0857b004320e90e91ea",
            )
            else -> null
        }
    }

    /** Candidate tokenizer files next to the model, best first. */
    fun tokenizerCandidates(model: File): List<File> {
        val dir = model.parentFile ?: File(".")
        val stem = model.name.removeSuffix(".pte")
        val noSoc = stem.replace(Regex("""(?i)\.?SM\d{4}"""), "")
        return listOf("$stem.tokenizer.json", "$noSoc.tokenizer.json", "tokenizer.json", "tokenizer.model", "tokenizer.bin")
            .distinct().map { File(dir, it) }
    }

    /** Where a downloaded tokenizer is stored. */
    fun downloadTarget(model: File): File = File(model.parentFile, model.name.removeSuffix(".pte") + ".tokenizer.json")

    /**
     * The ExecuTorch runner does NOT apply chat templates, so the full prompt is rendered here.
     * No BOS is added: the runner prepends it itself (LlmModule numBos).
     */
    fun buildPrompt(family: Family, system: String?, user: String): String {
        val sys = system?.takeIf { it.isNotBlank() }
        return when (family) {
            Family.QWEN -> buildString {
                if (sys != null) append("<|im_start|>system\n").append(sys).append("<|im_end|>\n")
                append("<|im_start|>user\n").append(user).append("<|im_end|>\n<|im_start|>assistant\n")
            }
            Family.LLAMA3 -> buildString {
                if (sys != null) append("<|start_header_id|>system<|end_header_id|>\n\n").append(sys).append("<|eot_id|>")
                append("<|start_header_id|>user<|end_header_id|>\n\n").append(user)
                append("<|eot_id|><|start_header_id|>assistant<|end_header_id|>\n\n")
            }
            Family.PHI -> buildString {
                if (sys != null) append("<|system|>").append(sys).append("<|end|>")
                append("<|user|>").append(user).append("<|end|><|assistant|>")
            }
        }
    }

    /** If [text] contains a stop marker returns the text before it, else null. */
    fun cutAtStop(family: Family, text: String): String? {
        var cut = -1
        for (m in family.stopMarkers) { val i = text.indexOf(m); if (i >= 0 && (cut < 0 || i < cut)) cut = i }
        return if (cut >= 0) text.substring(0, cut) else null
    }
}
