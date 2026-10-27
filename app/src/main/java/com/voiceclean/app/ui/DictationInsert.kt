package com.voiceclean.app.ui

/**
 * Result of [DictationInsert.apply].
 * @property text the full new text
 * @property dictStart start index of the dictated text itself (after any leading space)
 * @property dictLen length of the dictated text itself
 * @property insertedLen total length inserted at the anchor including added spaces (pass back as `insertedPrev`)
 * @property caret caret position: right after the dictated text (before an added trailing space)
 */
data class InsertResult(val text: String, val dictStart: Int, val dictLen: Int, val insertedLen: Int, val caret: Int)

/** Pure insertion/range-replacement logic for dictating into an existing text at the cursor. */
object DictationInsert {
    private const val NO_SPACE_BEFORE = ".,;:!?)]}%"

    /**
     * Replaces the dictation range of [oldText] with [dictation].
     * The range is `[anchor, selEnd)` on the first call (the user's selection, possibly empty) and
     * `[anchor, anchor + insertedPrev)` on later calls (`insertedPrev` > 0 means we already inserted text).
     * A space is added before/after only when the neighbouring character is non-whitespace (and, after, not punctuation).
     * Blank [dictation] leaves the text unchanged on the first call, or removes the previous insertion.
     */
    fun apply(oldText: String, anchor: Int, selEnd: Int, insertedPrev: Int, dictation: String): InsertResult {
        val a = anchor.coerceIn(0, oldText.length)
        val end = (if (insertedPrev > 0) a + insertedPrev else selEnd).coerceIn(a, oldText.length)
        val core = dictation.trim()
        // keep a paragraph break requested by the user (blank line) at the end; stray engine newlines are trimmed
        val d = if (dictation.trimEnd(' ', '\t').endsWith("\n\n") && core.isNotEmpty()) core + "\n\n" else core
        if (d.isEmpty()) {
            return if (insertedPrev > 0) InsertResult(oldText.removeRange(a, end), a, 0, 0, a)
            else InsertResult(oldText, a, 0, 0, end)
        }
        val pre = if (a > 0 && !oldText[a - 1].isWhitespace()) " " else ""
        val post = if (end < oldText.length && !d.endsWith('\n') && !oldText[end].isWhitespace() && oldText[end] !in NO_SPACE_BEFORE) " " else ""
        val text = oldText.substring(0, a) + pre + d + post + oldText.substring(end)
        val dictStart = a + pre.length
        return InsertResult(text, dictStart, d.length, pre.length + d.length + post.length, dictStart + d.length)
    }
}
