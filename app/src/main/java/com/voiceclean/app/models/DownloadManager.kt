package com.voiceclean.app.models

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

enum class DownloadStatus { QUEUED, DOWNLOADING, VERIFYING, PAUSED, FAILED }

data class DownloadProgress(
    val status: DownloadStatus,
    val downloaded: Long = 0,
    val total: Long = 0,
    val speedBps: Long = 0,
    val etaSec: Long = -1,
    val message: String? = null,
) {
    val fraction: Float get() = if (total > 0) (downloaded.toFloat() / total).coerceIn(0f, 1f) else 0f
    val isActive: Boolean get() = status == DownloadStatus.QUEUED || status == DownloadStatus.DOWNLOADING || status == DownloadStatus.VERIFYING
}

data class DownloadSpec(
    val id: String,
    val url: String,
    val dest: File,
    val expectedSize: Long,
    val sha256: String? = null,
)

/**
 * App-scoped download engine. Survives screen changes (scope lives with the process; DownloadService keeps the
 * process alive in the background). State per model id in [states]; completed downloads vanish from the map and
 * are announced on [completed].
 */
class DownloadManager(
    client: OkHttpClient,
    private val tokenProvider: suspend () -> String?,
    private val freeSpace: (File) -> Long = { f -> generateSequence(f) { it.parentFile }.firstOrNull { it.exists() }?.usableSpace ?: 0L },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val downloader: ResumableDownloader = ResumableDownloader(client),
) {
    private val _states = MutableStateFlow<Map<String, DownloadProgress>>(emptyMap())
    val states: StateFlow<Map<String, DownloadProgress>> = _states.asStateFlow()
    private val _completed = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val completed: SharedFlow<String> = _completed.asSharedFlow()

    private val jobs = HashMap<String, Job>()
    private val lock = Any()

    fun hasActive() = _states.value.values.any { it.isActive }

    fun partFile(dest: File) = File(dest.parentFile, dest.name + ".part")

    fun start(spec: DownloadSpec) {
        synchronized(lock) {
            if (jobs[spec.id]?.isActive == true) return
            val part = partFile(spec.dest)
            val have = part.length()
            set(spec.id, DownloadProgress(DownloadStatus.QUEUED, have, spec.expectedSize))
            jobs[spec.id] = scope.launch { run(spec) }
        }
    }

    /** Stops the transfer but keeps the .part file so it can be resumed. */
    fun pause(spec: DownloadSpec) {
        synchronized(lock) {
            val cur = _states.value[spec.id] ?: return
            jobs.remove(spec.id)?.cancel()
            set(spec.id, cur.copy(status = DownloadStatus.PAUSED, speedBps = 0, etaSec = -1, message = null))
        }
    }

    /** Stops and deletes partial data. */
    fun cancel(spec: DownloadSpec) {
        synchronized(lock) {
            jobs.remove(spec.id)?.cancel()
            _states.update { it - spec.id }
        }
        scope.launch { partFile(spec.dest).delete() }
    }

    fun clearFailed(id: String) = _states.update { if (it[id]?.status == DownloadStatus.FAILED) it - id else it }

    private fun set(id: String, p: DownloadProgress) = _states.update { it + (id to p) }

    /** Worker-side update: ignored if the user paused/cancelled meanwhile. */
    private fun setLive(id: String, p: DownloadProgress) = _states.update {
        val cur = it[id]
        if (cur == null || cur.status == DownloadStatus.PAUSED) it else it + (id to p)
    }

    private suspend fun run(spec: DownloadSpec) {
        val part = partFile(spec.dest)
        try {
            val needed = if (spec.expectedSize > 0) spec.expectedSize - part.length() else 0L
            spec.dest.parentFile?.mkdirs()
            val margin = 64L * 1024 * 1024
            if (needed > 0 && freeSpace(spec.dest.parentFile!!) < needed + margin) {
                throw FatalDownloadException("Not enough free storage: need ${needed / 1_000_000} MB more, " +
                    "${freeSpace(spec.dest.parentFile!!) / 1_000_000} MB free.")
            }
            val token = tokenProvider()
            var lastT = System.nanoTime()
            var lastB = part.length()
            var speed = 0.0
            val total = downloader.download(
                spec.url, part, spec.expectedSize, token,
                onProgress = { done, tot ->
                    val now = System.nanoTime()
                    val dt = (now - lastT) / 1e9
                    if (dt >= 0.5) {
                        val inst = (done - lastB) / dt
                        speed = if (speed == 0.0) inst else speed * 0.7 + inst * 0.3
                        lastT = now; lastB = done
                    }
                    val t = if (tot > 0) tot else spec.expectedSize
                    val eta = if (speed > 1 && t > 0) ((t - done) / speed).toLong() else -1L
                    setLive(spec.id, DownloadProgress(DownloadStatus.DOWNLOADING, done, t, speed.toLong(), eta))
                },
                onRetry = { msg ->
                    _states.update { m -> m[spec.id]?.let { m + (spec.id to it.copy(message = msg, speedBps = 0, etaSec = -1)) } ?: m }
                },
            )
            setLive(spec.id, DownloadProgress(DownloadStatus.VERIFYING, total, total, message = "Verifying"))
            withContext(Dispatchers.IO) { verifyAndCommit(spec, part, total) }
            _states.update { it - spec.id }
            _completed.tryEmit(spec.id)
        } catch (e: CancellationException) {
            throw e // pause()/cancel() already updated state
        } catch (e: Exception) {
            val cur = _states.value[spec.id]
            setLive(spec.id, DownloadProgress(DownloadStatus.FAILED, part.length(), cur?.total ?: spec.expectedSize,
                message = e.message ?: e.javaClass.simpleName))
        }
    }

    private fun verifyAndCommit(spec: DownloadSpec, part: File, total: Long) {
        if (part.length() != total || (spec.expectedSize > 0 && part.length() != spec.expectedSize)) {
            part.delete()
            throw FatalDownloadException("Size mismatch after download; partial file discarded. Try again.")
        }
        if (spec.sha256 != null) {
            val actual = ResumableDownloader.sha256(part)
            if (!actual.equals(spec.sha256, ignoreCase = true)) {
                part.delete()
                throw FatalDownloadException("Checksum mismatch; corrupted download discarded. Try again.")
            }
        }
        try {
            Files.move(part.toPath(), spec.dest.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: IOException) {
            if (!part.renameTo(spec.dest)) throw FatalDownloadException("Could not finalize file: ${e.message}")
        }
    }
}
