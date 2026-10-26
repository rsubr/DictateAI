package com.voiceclean.app.llm

import org.junit.Assert.*
import org.junit.Test

class PromptBuilderTest {
    private val pb = PromptBuilder()

    @Test fun everyStyleHasStrictOutputRule() {
        for (s in Style.entries) {
            val p = pb.systemPrompt(s)
            assertTrue(p.contains("Output ONLY the rewritten text"))
            assertTrue(p.contains("names, numbers"))
            assertTrue(p.contains("English"))
        }
    }

    @Test fun stylesDiffer() {
        assertEquals(Style.entries.size, Style.entries.map { pb.systemPrompt(it) }.toSet().size)
        assertTrue(pb.systemPrompt(Style.Formal).contains("formal"))
        assertTrue(pb.systemPrompt(Style.Shorter).contains("concise"))
        assertTrue(pb.systemPrompt(Style.Clean).contains("filler"))
    }

    @Test fun userTextIsDelimited() {
        assertEquals("<text>\nhello\n</text>", pb.userMessage("  hello "))
    }

    @Test fun qwen3GetsNoThink() {
        val r = pb.render(Style.Clean, "hi", ModelFamily.QWEN3)
        assertTrue(r.contains("/no_think"))
        assertTrue(r.endsWith("<think>\n\n</think>\n\n"))
        assertFalse(pb.render(Style.Clean, "hi", ModelFamily.CHATML).contains("no_think"))
    }

    @Test fun familyTemplates() {
        assertTrue(pb.render(Style.Clean, "hi", ModelFamily.GEMMA).endsWith("<start_of_turn>model\n"))
        assertTrue(pb.render(Style.Clean, "hi", ModelFamily.LLAMA3).endsWith("<|end_header_id|>\n\n"))
        assertTrue(pb.render(Style.Clean, "hi", ModelFamily.CHATML).endsWith("<|im_start|>assistant\n"))
    }

    @Test fun detectFamily() {
        assertEquals(ModelFamily.QWEN3, ModelFamily.detect("Qwen3-4B-Q5_K_M.gguf"))
        assertEquals(ModelFamily.CHATML, ModelFamily.detect("qwen2.5-3b"))
        assertEquals(ModelFamily.GEMMA, ModelFamily.detect("gemma-3-4b-it"))
        assertEquals(ModelFamily.LLAMA3, ModelFamily.detect("Llama-3.2-3B-Instruct"))
        assertEquals(ModelFamily.PLAIN, ModelFamily.detect(""))
    }

    @Test fun lowTemperatureAndBudget() {
        val p = pb.params(Style.Clean, "x".repeat(300))
        assertTrue(p.temperature <= 0.3f)
        assertTrue(p.maxTokens in 128..1536)
        assertTrue(pb.params(Style.Longer, "x".repeat(300)).maxTokens > p.maxTokens)
    }
}
