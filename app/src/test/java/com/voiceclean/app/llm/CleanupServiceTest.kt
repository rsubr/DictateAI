package com.voiceclean.app.llm

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CleanupServiceTest {
    private class Canned(val out: String) : LlmEngine {
        var lastPrompt = ""
        override val id = "canned"
        override suspend fun load(path: String, cfg: LlmConfig) {}
        override fun generate(prompt: String, params: GenParams): Flow<String> = flow {
            lastPrompt = prompt
            out.chunked(5).forEach { emit(it) }
        }
        override fun unload() {}
    }

    private fun run(out: String, raw: String = "um so i wanna go to the store tomorrow", style: Style = Style.Clean): CleanupResult {
        val e = Canned(out)
        return runBlocking { CleanupService({ e }).run(raw, style) }
    }

    @Test fun normalOutputPasses() {
        val r = run("I want to go to the store tomorrow.")
        assertFalse(r.fellBack); assertEquals("I want to go to the store tomorrow.", r.text)
    }

    @Test fun stripsChatterPrefix() {
        val r = run("Here is the cleaned text:\nI want to go to the store tomorrow.")
        assertEquals("I want to go to the store tomorrow.", r.text)
    }

    @Test fun stripsThinkAndQuotes() {
        assertEquals("I want to go to the store tomorrow.", run("<think>hmm</think>\"I want to go to the store tomorrow.\"").text)
    }

    @Test fun emptyFallsBack() {
        val r = run("  "); assertTrue(r.fellBack); assertEquals("um so i wanna go to the store tomorrow", r.text)
    }

    @Test fun absurdLengthFallsBack() {
        assertTrue(run("word ".repeat(200)).fellBack)
        assertTrue(run("ok", raw = "this is a long enough sentence for the ratio guard to apply").fellBack)
    }

    @Test fun originalSkipsLlm() {
        val e = Canned("x")
        val r = runBlocking { CleanupService({ e }).run("raw text", Style.Original) }
        assertEquals("raw text", r.text); assertEquals("", e.lastPrompt)
    }

    @Test fun promptUsesRawText() {
        val e = Canned("Fine.")
        runBlocking { CleanupService({ e }, { "qwen3-4b" }).run("raw dictation", Style.Formal) }
        assertTrue(e.lastPrompt.contains("<text>\nraw dictation\n</text>"))
        assertTrue(e.lastPrompt.contains("/no_think"))
    }
}
