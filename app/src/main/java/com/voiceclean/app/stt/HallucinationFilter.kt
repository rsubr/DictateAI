package com.voiceclean.app.stt

/** Removes typical Whisper artefacts produced on silence / noise. */
object HallucinationFilter {
    private val tags = Regex("""\[[^\]]{0,40}]|\([^)]{0,40}\)|\*[^*]{0,40}\*|♪[^♪]*♪?""")
    private val knownTagWords = Regex("""(?i)blank|audio|music|noise|silence|applause|laughter|inaudible|speaking|sound|silent|clicking|typing|cough|breath|sigh""")
    private val ws = Regex("""\s+""")

    /** Whole-utterance phrases Whisper invents on silence. Only dropped when little speech was detected. */
    private val phantoms = setOf(
        "thank you", "thanks", "thank you very much", "thank you so much", "thanks for watching", "thank you for watching",
        "thanks for listening", "thank you for listening", "please subscribe", "like and subscribe", "bye", "bye-bye",
        "bye bye", "you", "so", "okay", "oh", "uh", "um", "hmm", "mm-hmm", "the end", "i'm sorry", "see you next time",
        "see you in the next video", "i'll see you in the next video", "subtitles by the amara.org community", "mm", "ah",
    )
    private val edgePunct = charArrayOf(',', '.', '!', '?', ';', ':', '"', '\'', ' ', '-', '…')

    /**
     * @param speechSeconds seconds of VAD-detected speech in the audio that produced [text]; phantom phrases are only
     * dropped when this is small (< [phantomMaxSpeechSec]) or [speechRatio] (speech share of the audio) is below 0.25.
     */
    fun clean(text: String, speechSeconds: Float = 0f, phantomMaxSpeechSec: Float = 1.6f, speechRatio: Float = 0f): String {
        var t = tags.replace(text) { m -> if (knownTagWords.containsMatchIn(m.value) || m.value.startsWith("♪")) " " else m.value }
        t = ws.replace(t, " ").trim()
        if (t.isEmpty()) return ""
        t = collapseRepeats(t)
        val lowSpeech = speechSeconds < phantomMaxSpeechSec || (speechRatio in 0.0001f..0.25f && speechSeconds < 4f)
        if (lowSpeech && t.lowercase().trim(*edgePunct) in phantoms) return ""
        // pure punctuation
        if (t.none { it.isLetterOrDigit() }) return ""
        return t
    }

    /** Collapses a word n-gram (1..8 words) repeated 3+ times in a row into a single occurrence. */
    fun collapseRepeats(text: String, minRepeats: Int = 3): String {
        var words = text.split(' ').filter { it.isNotEmpty() }
        var changed = true
        while (changed) {
            changed = false
            outer@ for (n in 1..8) {
                var i = 0
                while (i + n * minRepeats <= words.size) {
                    var reps = 1
                    while (i + (reps + 1) * n <= words.size && sameGram(words, i, i + reps * n, n)) reps++
                    if (reps >= minRepeats) {
                        words = words.subList(0, i + n) + words.subList(i + reps * n, words.size)
                        changed = true
                        break@outer
                    }
                    i++
                }
            }
        }
        return words.joinToString(" ")
    }

    private fun sameGram(w: List<String>, a: Int, b: Int, n: Int): Boolean {
        for (k in 0 until n) if (norm(w[a + k]) != norm(w[b + k])) return false
        return true
    }

    private fun norm(s: String) = s.lowercase().trim(',', '.', '!', '?', ';', ':')
}
