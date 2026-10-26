package com.voiceclean.app.models

/** Parsed reference to a file on huggingface.co. */
data class HfFileRef(val repo: String, val revision: String, val file: String) {
    val url get() = "https://huggingface.co/$repo/resolve/$revision/$file"
}

/**
 * Accepts:
 *  - https://huggingface.co/owner/repo/resolve/main/path/file.gguf (or /blob/ or ?download=true)
 *  - owner/repo + [fileName]
 *  - owner/repo/file.gguf
 * Returns null when it cannot be understood.
 */
fun parseHfReference(input: String, fileName: String? = null): HfFileRef? {
    var s = input.trim().substringBefore('?').substringBefore('#')
    if (s.isEmpty()) return null
    val m = Regex("""^https?://(?:www\.)?huggingface\.co/(.+)$""").matchEntire(s)
    if (m != null) s = m.groupValues[1] else if (s.startsWith("http")) return null
    val parts = s.trim('/').split('/')
    if (parts.size < 2) return null
    val repo = parts[0] + "/" + parts[1]
    if (!Regex("""[\w.\-]+/[\w.\-]+""").matches(repo)) return null
    val rest = parts.drop(2)
    if (rest.size >= 3 && (rest[0] == "resolve" || rest[0] == "blob")) {
        return HfFileRef(repo, rest[1], rest.drop(2).joinToString("/")).takeIf { it.file.isNotBlank() }
    }
    val f = fileName?.trim()?.takeIf { it.isNotEmpty() } ?: rest.joinToString("/").takeIf { it.isNotEmpty() } ?: return null
    return HfFileRef(repo, "main", f.trim('/'))
}

/** Local file name safe for app storage; must end in .gguf (LLM) or .bin (STT). */
fun safeModelFileName(file: String, kind: ModelKind): String? {
    val base = file.substringAfterLast('/').replace(Regex("""[^\w.\-]"""), "_")
    val ext = if (kind == ModelKind.LLM) ".gguf" else ".bin"
    return base.takeIf { it.endsWith(ext, ignoreCase = true) && it.length > ext.length && !it.startsWith(".") }
}

/** Magic bytes: GGUF for LLMs; whisper.cpp ggml "lmgg" for STT. */
fun magicMatches(kind: ModelKind, head: ByteArray): Boolean = when (kind) {
    ModelKind.LLM -> head.size >= 4 && String(head, 0, 4, Charsets.ISO_8859_1) == "GGUF"
    ModelKind.STT -> head.size >= 4 && String(head, 0, 4, Charsets.ISO_8859_1) == "lmgg"
}
