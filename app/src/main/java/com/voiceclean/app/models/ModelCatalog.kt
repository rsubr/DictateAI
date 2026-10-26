package com.voiceclean.app.models

enum class ModelKind(val dir: String, val label: String) {
    STT("stt", "Speech-to-text"),
    LLM("llm", "Cleanup LLM"),
}

enum class ModelTag(val label: String) {
    ACCURACY("Recommended for accuracy"),
    FASTER("Faster"),
    BALANCED("Balanced"),
}

/** A downloadable model. [sizeBytes] and [sha256] were verified against the HF tree API (see DECISIONS D12). */
data class CatalogModel(
    val id: String,
    val kind: ModelKind,
    val displayName: String,
    val repo: String,
    val fileName: String,
    val sizeBytes: Long,
    val quant: String,
    val ramHintMb: Int,
    val description: String,
    val tags: Set<ModelTag> = emptySet(),
    val sha256: String? = null,
    val revision: String = "main",
    /** True if the HF repo requires login/license acceptance (token needed). All curated entries are ungated mirrors. */
    val gated: Boolean = false,
    val custom: Boolean = false,
    /** Full URL override for custom models not on huggingface.co. */
    val urlOverride: String? = null,
) {
    val url: String get() = urlOverride ?: "https://huggingface.co/$repo/resolve/$revision/$fileName"
}

object ModelCatalog {
    val models: List<CatalogModel> = listOf(
        // ---- Speech-to-text (ggerganov/whisper.cpp) ----
        CatalogModel(
            "stt-base-en", ModelKind.STT, "Whisper base.en", "ggerganov/whisper.cpp", "ggml-base.en.bin",
            147_964_211, "F16", 400, "Smallest option. Fast, noticeably less accurate on names and noise.",
            setOf(ModelTag.FASTER), "a03779c86df3323075f5e796cb2ce5029f00ec8869eee3fdfb897afe36c6d002",
        ),
        CatalogModel(
            "stt-small-en", ModelKind.STT, "Whisper small.en", "ggerganov/whisper.cpp", "ggml-small.en.bin",
            487_614_201, "F16", 900, "Good accuracy/speed balance for live dictation.",
            setOf(ModelTag.BALANCED), "c6138d6d58ecc8322097e0f987c32f1be8bb0a18532a3f88f734d1bbf9c41e5d",
        ),
        CatalogModel(
            "stt-small-en-q8_0", ModelKind.STT, "Whisper small.en (Q8_0)", "ggerganov/whisper.cpp", "ggml-small.en-q8_0.bin",
            264_477_561, "Q8_0", 500, "Near-identical accuracy to small.en F16 at about half the size and memory.",
            setOf(ModelTag.BALANCED), "67a179f608ea6114bd3fdb9060e762b588a3fb3bd00c4387971be4d177958067",
        ),
        CatalogModel(
            "stt-medium-en-q8_0", ModelKind.STT, "Whisper medium.en (Q8_0)", "ggerganov/whisper.cpp", "ggml-medium.en-q8_0.bin",
            823_382_461, "Q8_0", 1300, "High accuracy English model at about half the size of F16.",
            setOf(ModelTag.ACCURACY), "43fa2cd084de5a04399a896a9a7a786064e221365c01700cea4666005218f11c",
        ),
        CatalogModel(
            "stt-medium-en", ModelKind.STT, "Whisper medium.en", "ggerganov/whisper.cpp", "ggml-medium.en.bin",
            1_533_774_781, "F16", 2000, "High accuracy English model. Slow on phones for live use.",
            setOf(ModelTag.ACCURACY), "cc37e93478338ec7700281a7ac30a10128929eb8f427dda2e865faa8f6da4356",
        ),
        CatalogModel(
            "stt-large-v3-turbo-q5_0", ModelKind.STT, "Whisper large-v3-turbo (Q5_0)", "ggerganov/whisper.cpp",
            "ggml-large-v3-turbo-q5_0.bin", 574_041_195, "Q5_0", 1100,
            "Large-v3 quality with a pruned decoder, quantized. Best accuracy per MB.",
            setOf(ModelTag.ACCURACY), "394221709cd5ad1f40c46e6031ca61bce88931e6e088c188294c6d5a55ffa7e2",
        ),
        CatalogModel(
            "stt-large-v3-turbo", ModelKind.STT, "Whisper large-v3-turbo (F16)", "ggerganov/whisper.cpp",
            "ggml-large-v3-turbo.bin", 1_624_555_275, "F16", 2600,
            "Unquantized large-v3-turbo. Highest accuracy; multilingual model used for English.",
            setOf(ModelTag.ACCURACY), "1fc70f774d38eb169993ac391eea357ef47c88757ef72ee5943879b7e8e2bc69",
        ),
        // ---- Cleanup LLMs (ungated GGUF mirrors) ----
        CatalogModel(
            "llm-qwen3-4b-q5_k_m", ModelKind.LLM, "Qwen3 4B (Q5_K_M)", "Qwen/Qwen3-4B-GGUF", "Qwen3-4B-Q5_K_M.gguf",
            2_889_513_184, "Q5_K_M", 3600, "Strong rewriting/grammar model (official Qwen). Thinking is disabled at prompt time.",
            setOf(ModelTag.ACCURACY, ModelTag.BALANCED), "aca596860e8cb40af6539e3f2ea40df305f42515deac56d49c08d39a02e6533f",
        ),
        CatalogModel(
            "llm-qwen3-4b-q6_k", ModelKind.LLM, "Qwen3 4B (Q6_K)", "Qwen/Qwen3-4B-GGUF", "Qwen3-4B-Q6_K.gguf",
            3_306_260_704, "Q6_K", 4100, "Near-lossless quant of Qwen3 4B. Best accuracy of the 4B class.",
            setOf(ModelTag.ACCURACY), "8a08533841623c2a689763b9318d8e27b3e052ad5b38015d4be4c04fa96f68a3",
        ),
        CatalogModel(
            "llm-gemma3-4b-q5_k_m", ModelKind.LLM, "Gemma 3 4B it (Q5_K_M)", "unsloth/gemma-3-4b-it-GGUF",
            "gemma-3-4b-it-Q5_K_M.gguf", 2_829_698_176, "Q5_K_M", 3600,
            "Google Gemma 3 instruct via unsloth mirror (ungated). Natural, fluent prose.",
            setOf(ModelTag.ACCURACY, ModelTag.BALANCED), "974e5c2f13c321fc3258b6fbf2ce326a09d8ace511aa6846df1db62baf7df7d4",
        ),
        CatalogModel(
            "llm-gemma3-4b-q6_k", ModelKind.LLM, "Gemma 3 4B it (Q6_K)", "unsloth/gemma-3-4b-it-GGUF",
            "gemma-3-4b-it-Q6_K.gguf", 3_190_740_096, "Q6_K", 4000,
            "Higher-precision Gemma 3 4B (ungated unsloth mirror).",
            setOf(ModelTag.ACCURACY), "df3fb9a1449296d26258c0771d5280ceb71559ad98e2db658799a05218ed9fa1",
        ),
        CatalogModel(
            "llm-llama32-3b-q6_k", ModelKind.LLM, "Llama 3.2 3B Instruct (Q6_K)", "unsloth/Llama-3.2-3B-Instruct-GGUF",
            "Llama-3.2-3B-Instruct-Q6_K.gguf", 2_643_853_760, "Q6_K", 3300,
            "Compact, reliable instruction follower (ungated unsloth mirror of Meta's model).",
            setOf(ModelTag.BALANCED), "a48f9d72a278835e0b0c00c6c115a3edd1703f5f15713b5f3194bc8b392ea631",
        ),
        CatalogModel(
            "llm-qwen3-1.7b-q8_0", ModelKind.LLM, "Qwen3 1.7B (Q8_0)", "Qwen/Qwen3-1.7B-GGUF", "Qwen3-1.7B-Q8_0.gguf",
            1_834_426_016, "Q8_0", 2400, "Fast cleanup on mid-range chips; lower rewrite quality.",
            setOf(ModelTag.FASTER), "061b54daade076b5d3362dac252678d17da8c68f07560be70818cace6590cb1a",
        ),
        // ---- ExecuTorch + Qualcomm QNN (.pte, Hexagon NPU; l3utterfly/Qwen-3-1.7B-qnn-executorch, sizes/SHA256 via HF tree API 2026-10-26, see docs/QNN.md) ----
        CatalogModel(
            "llm-qnn-qwen3-1.7b-sm8650", ModelKind.LLM, "Qwen3 1.7B QNN (SM8650, 8 Gen 3)", "l3utterfly/Qwen-3-1.7B-qnn-executorch",
            "Qwen-3-1.7B.SM8650.pte", 2_403_955_732, "QNN HTP", 2600,
            "Experimental. Runs on the Hexagon NPU via ExecuTorch; only for Snapdragon 8 Gen 3 (e.g. Galaxy S24). Needs the ExecuTorch-QNN backend.",
            emptySet(), "98100b2e264c073633d5a02f1f669867acee4c527616635d36f21871f7b96fc1",
        ),
        CatalogModel(
            "llm-qnn-qwen3-1.7b-sm8750", ModelKind.LLM, "Qwen3 1.7B QNN (SM8750, 8 Elite)", "l3utterfly/Qwen-3-1.7B-qnn-executorch",
            "Qwen-3-1.7B.SM8750.pte", 2_403_185_684, "QNN HTP", 2600,
            "Experimental. Runs on the Hexagon NPU via ExecuTorch; only for Snapdragon 8 Elite (e.g. Galaxy S25). Needs the ExecuTorch-QNN backend.",
            emptySet(), "16a51f81b1aed6391d4bac3446e2f9724b1ae0bcf0101614a03b1953e4739ee9",
        ),
        CatalogModel(
            "llm-qnn-qwen3-1.7b-sm8850", ModelKind.LLM, "Qwen3 1.7B QNN (SM8850, 8 Elite Gen 5)", "l3utterfly/Qwen-3-1.7B-qnn-executorch",
            "Qwen-3-1.7B.SM8850.pte", 2_403_185_684, "QNN HTP", 2600,
            "Experimental. Same bytes as the SM8750 file on the Hub (unverified for Hexagon V81). Needs the ExecuTorch-QNN backend.",
            emptySet(), "16a51f81b1aed6391d4bac3446e2f9724b1ae0bcf0101614a03b1953e4739ee9",
        ),
    )

    fun byKind(kind: ModelKind) = models.filter { it.kind == kind }
    fun byId(id: String) = models.firstOrNull { it.id == id }
}
