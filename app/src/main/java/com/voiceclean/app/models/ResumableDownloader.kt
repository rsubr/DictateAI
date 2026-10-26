package com.voiceclean.app.models

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest

/** Non-retryable failure (auth, 404, no space, verification). */
class FatalDownloadException(message: String) : IOException(message)

/** Parses "bytes 100-199/1000" -> Triple(start, end, total) (total -1 when '*'). */
internal fun parseContentRange(h: String?): Triple<Long, Long, Long>? {
    val m = Regex("""bytes\s+(\d+)-(\d+)/(\d+|\*)""").matchEntire(h?.trim() ?: return null) ?: return null
    return Triple(m.groupValues[1].toLong(), m.groupValues[2].toLong(), m.groupValues[3].toLongOrNull() ?: -1L)
}

/**
 * Single-file HTTP downloader with Range resume into a ".part" file.
 * Retries transient IO errors with backoff (the counter resets whenever bytes made progress).
 */
class ResumableDownloader(
    private val client: OkHttpClient,
    private val maxStalledRetries: Int = 6,
    private val backoffMs: (attempt: Int) -> Long = { minOf(1000L shl it, 15_000L) },
) {
    /**
     * Downloads [url] into [part] (resuming if it exists). Returns total bytes when complete.
     * [onProgress] is called with (downloaded, total) – total may be -1 if unknown.
     * [onRetry] is called with a human message when reconnecting.
     */
    suspend fun download(
        url: String,
        part: File,
        expectedSize: Long,
        token: String?,
        onProgress: (Long, Long) -> Unit,
        onRetry: (String) -> Unit = {},
    ): Long {
        var stalled = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val before = part.length()
            try {
                return attempt(url, part, expectedSize, token, onProgress)
            } catch (e: CancellationException) {
                throw e
            } catch (e: FatalDownloadException) {
                throw e
            } catch (e: IOException) {
                if (part.length() > before) stalled = 0
                if (++stalled > maxStalledRetries) throw IOException("Network error: ${e.message}", e)
                onRetry("Connection lost, retrying ($stalled/$maxStalledRetries)")
                delay(backoffMs(stalled - 1))
            }
        }
    }

    private suspend fun attempt(
        url: String, part: File, expectedSize: Long, token: String?, onProgress: (Long, Long) -> Unit,
    ): Long {
        part.parentFile?.mkdirs()
        var existing = part.length()
        if (expectedSize > 0 && existing > expectedSize) { part.delete(); existing = 0 }
        if (expectedSize > 0 && existing == expectedSize) return expectedSize

        val rb = Request.Builder().url(url).header("Accept-Encoding", "identity")
        if (!token.isNullOrBlank()) rb.header("Authorization", "Bearer ${token.trim()}")
        if (existing > 0) rb.header("Range", "bytes=$existing-")
        client.newCall(rb.build()).execute().use { resp ->
            when (resp.code) {
                200, 206 -> {}
                401, 403 -> throw FatalDownloadException(
                    "Access denied (HTTP ${resp.code}). This repo may be gated: add a Hugging Face token and accept the license.")
                404 -> throw FatalDownloadException("File not found on server (HTTP 404).")
                416 -> {
                    // Range not satisfiable: part is complete (== total) or garbage.
                    val total = parseContentRange(resp.header("Content-Range"))?.third
                        ?: Regex("""\*/(\d+)""").find(resp.header("Content-Range") ?: "")?.groupValues?.get(1)?.toLongOrNull()
                    if (total != null && total == existing) return total
                    part.delete()
                    throw IOException("Range rejected, restarting from zero")
                }
                else -> throw IOException("HTTP ${resp.code}")
            }
            val body = resp.body ?: throw IOException("Empty body")
            val resumed: Boolean
            val total: Long
            if (resp.code == 206) {
                val cr = parseContentRange(resp.header("Content-Range"))
                if (cr == null || cr.first != existing) {
                    part.delete(); throw IOException("Server returned unexpected range")
                }
                resumed = true
                total = if (cr.third > 0) cr.third else expectedSize
            } else {
                resumed = false // server ignored Range: restart
                total = body.contentLength().takeIf { it > 0 } ?: expectedSize
            }
            if (expectedSize > 0 && total > 0 && total != expectedSize)
                throw FatalDownloadException("Server file size $total differs from expected $expectedSize")
            var done = if (resumed) existing else 0L
            RandomAccessFile(part, "rw").use { raf ->
                if (!resumed) raf.setLength(0)
                raf.seek(done)
                val buf = ByteArray(256 * 1024)
                val src = body.source()
                var lastReport = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = src.read(buf)
                    if (n < 0) break
                    raf.write(buf, 0, n)
                    done += n
                    val now = System.nanoTime()
                    if (now - lastReport > 250_000_000L) { lastReport = now; onProgress(done, total) }
                }
            }
            onProgress(done, total)
            if (total > 0 && done < total) throw IOException("Connection closed early ($done/$total)")
            return done
        }
    }

    companion object {
        fun sha256(file: File, onBytes: (Long) -> Unit = {}): String {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { ins ->
                val buf = ByteArray(1 shl 20)
                var total = 0L
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n); total += n; onBytes(total)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
