package com.voiceclean.app.llm

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class QnnModelInfoTest {
    @Test fun socTags() {
        assertEquals("SM8750", QnnModelInfo.socTag("Qwen-3-1.7B.SM8750.pte"))
        assertNull(QnnModelInfo.socTag("model.pte"))
        assertEquals("SM8650", QnnModelInfo.normalizeSoc("SM8650"))
        assertEquals("SM8750", QnnModelInfo.normalizeSoc("sm8750-ac"))
        assertNull(QnnModelInfo.normalizeSoc("Tensor G4"))
        assertEquals(79, QnnModelInfo.htpArch("SM8750"))
        assertEquals(81, QnnModelInfo.htpArch("SM8850"))
        assertNull(QnnModelInfo.htpArch(null))
    }
    @Test fun familiesAndTokenizers() {
        assertEquals(QnnModelInfo.Family.QWEN, QnnModelInfo.family("Qwen-3-1.7B.SM8750.pte"))
        assertEquals(QnnModelInfo.Family.LLAMA3, QnnModelInfo.family("Llama-3.2-3B-Instruct.SM8650.pte"))
        assertEquals(QnnModelInfo.Family.PHI, QnnModelInfo.family("phi-4-mini-instruct-kv.SM8650.pte"))
        assertNull(QnnModelInfo.family("mystery.pte"))
        assertNotNull(QnnModelInfo.tokenizerSource("Qwen-3-1.7B.SM8750.pte"))
        assertNull(QnnModelInfo.tokenizerSource("mystery.pte"))
    }
    @Test fun tokenizerCandidates() {
        val c = QnnModelInfo.tokenizerCandidates(File("/m/Qwen-3-1.7B.SM8750.pte")).map { it.name }
        assertEquals("Qwen-3-1.7B.SM8750.tokenizer.json", c[0])
        assertTrue("Qwen-3-1.7B.tokenizer.json" in c && "tokenizer.json" in c)
        assertEquals("Qwen-3-1.7B.SM8750.tokenizer.json", QnnModelInfo.downloadTarget(File("/m/Qwen-3-1.7B.SM8750.pte")).name)
    }
    @Test fun prompts() {
        assertEquals("<|im_start|>system\nS<|im_end|>\n<|im_start|>user\nU<|im_end|>\n<|im_start|>assistant\n",
            QnnModelInfo.buildPrompt(QnnModelInfo.Family.QWEN, "S", "U"))
        assertTrue(QnnModelInfo.buildPrompt(QnnModelInfo.Family.LLAMA3, null, "U").startsWith("<|start_header_id|>user"))
        assertEquals("<|user|>U<|end|><|assistant|>", QnnModelInfo.buildPrompt(QnnModelInfo.Family.PHI, " ", "U"))
    }
    @Test fun stopMarkers() {
        assertEquals("hi", QnnModelInfo.cutAtStop(QnnModelInfo.Family.QWEN, "hi<|im_end|>junk"))
        assertNull(QnnModelInfo.cutAtStop(QnnModelInfo.Family.QWEN, "hello"))
    }
}
