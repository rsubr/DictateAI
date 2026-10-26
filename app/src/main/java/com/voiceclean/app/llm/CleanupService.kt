package com.voiceclean.app.llm


data class CleanupResult(
    val text: String,
    val style: Style,
    /** True when the model output was rejected and [text] is the raw input. */
    val fellBack: Boolean = false,
    val reason: String? = null,
)

/**
 * Runs the LLM on the RAW (base) text with the chosen style. Styles never stack on previous rewrites:
 * callers always pass the raw dictation. Cancel by cancelling the calling coroutine.
 */
class CleanupService(
    private val engine: () -> LlmEngine,
    private val modelName: () -> String = { "" },
    private val promptBuilder: PromptBuilder = PromptBuilder(),
) {
    /** @param onPartial receives the sanitized cumulative output while streaming. */
    suspend fun run(raw: String, style: Style, onPartial: (String) -> Unit = {}): CleanupResult {
        val base = raw.trim()
        if (base.isEmpty() || style == Style.Original) return CleanupResult(base, style)
        val family = ModelFamily.detect(modelName())
        val prompt = promptBuilder.render(style, base, family)
        val params = promptBuilder.params(style, base)
        val sb = StringBuilder()
        engine().generate(prompt, params).collect { piece ->
            sb.append(piece)
            onPartial(sanitize(sb.toString()))
        }
        val out = sanitize(sb.toString())
        return guard(base, out, style)
    }

    companion object {
        private val thinkBlock = Regex("""(?s)<think>.*?</think>""")
        private val thinkOpen = Regex("""(?s)<think>.*$""")
        private val chatterLine = Regex(
            """(?i)^\s*(here(?:'s| is| are)\b.*|sure\b.*|certainly\b.*|of course\b.*|okay\b[,!].*|rewritten( text)?\b.*|cleaned(-| )?up( text| version)?\b.*|corrected( text| version)?\b.*|output\b.*)[:!]\s*$"""
        )
        private val specialTokens = Regex("""<\|[a-z_]+\|>|<end_of_turn>|<start_of_turn>|</?text>""")

        /** Strips think blocks, special tokens, code fences, chatter lead-ins and wrapping quotes. */
        fun sanitize(s: String): String {
            var t = thinkBlock.replace(s, "")
            t = thinkOpen.replace(t, "")
            t = specialTokens.replace(t, "")
            t = t.trim()
            if (t.startsWith("```")) {
                t = t.removePrefix("```").substringAfter('\n', "").removeSuffix("```").trim()
            }
            // drop a leading chatter line such as "Here is the cleaned text:"
            val nl = t.indexOf('\n')
            if (nl > 0 && chatterLine.matches(t.substring(0, nl))) t = t.substring(nl + 1).trim()
            else if (nl < 0 && chatterLine.matches(t)) t = ""
            if (t.length >= 2 && ((t.first() == '"' && t.last() == '"') || (t.first() == '“' && t.last() == '”'))) {
                val inner = t.substring(1, t.length - 1)
                if ('"' !in inner) t = inner.trim()
            }
            return t
        }

        fun guard(raw: String, out: String, style: Style): CleanupResult {
            if (out.isBlank()) return CleanupResult(raw, style, true, "Model returned no text")
            val ratio = out.length.toFloat() / raw.length.coerceAtLeast(1)
            val (lo, hi) = when (style) {
                Style.Shorter -> 0.08f to 1.3f
                Style.Longer -> 0.6f to 6f
                else -> 0.4f to 2.5f
            }
            val small = raw.length < 25
            if (!small && (ratio < lo || ratio > hi)) return CleanupResult(raw, style, true, "Model output length looked wrong")
            if (small && out.length > raw.length * 6 + 60) return CleanupResult(raw, style, true, "Model output length looked wrong")
            return CleanupResult(out, style)
        }
    }
}
