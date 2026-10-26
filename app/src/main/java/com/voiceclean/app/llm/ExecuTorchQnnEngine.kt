package com.voiceclean.app.llm

import android.content.Context
import android.os.Build
import android.system.Os
import com.voiceclean.app.diag.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.pytorch.executorch.extension.llm.LlmCallback
import org.pytorch.executorch.extension.llm.LlmModule
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * ExecuTorch + Qualcomm QNN (Hexagon NPU) backend (decision D3/D36). Loads a QNN-exported `.pte`
 * (file name carries the SoC tag, e.g. `Qwen-3-1.7B.SM8750.pte`) plus a tokenizer.json, streams tokens.
 *
 * Only temperature is honoured from [GenParams] (the ExecuTorch runner has no top-k/top-p/penalty API).
 * Chat templating is done here ([QnnModelInfo.buildPrompt]). Untested on a device (see docs/QNN.md).
 */
class ExecuTorchQnnEngine(
    private val context: Context,
    private val http: OkHttpClient = OkHttpClient(),
) : LlmEngine {
    override val id = "executorch-qnn"

    @Volatile private var module: LlmModule? = null
    @Volatile private var family: QnnModelInfo.Family = QnnModelInfo.Family.QWEN
    @Volatile private var maxCtx = 2048
    private val lock = Any()

    override suspend fun load(path: String, cfg: LlmConfig) = withContext(Dispatchers.IO) {
        unload()
        availability(context, path)?.let { throw IllegalStateException(it) }
        val model = File(path)
        family = QnnModelInfo.family(model.name)
            ?: throw IllegalStateException("Unknown model family for ${model.name}: expected Qwen, Llama 3.x or Phi in the file name (needed to pick the chat template).")
        val tokenizer = ensureTokenizer(model)
        val nativeDir = context.applicationInfo.nativeLibraryDir
        // QNN looks up the Hexagon skel libraries via ADSP_LIBRARY_PATH and dlopens the rest via LD_LIBRARY_PATH.
        Os.setenv("ADSP_LIBRARY_PATH", nativeDir, true)
        val ld = (Os.getenv("LD_LIBRARY_PATH")?.split(':').orEmpty() + nativeDir).distinct().joinToString(":")
        Os.setenv("LD_LIBRARY_PATH", ld, true)
        maxCtx = cfg.contextSize
        DebugLog.i("ExecuTorch", "loading ${model.name} (${family}) tokenizer=${tokenizer.name}")
        val m = LlmModule(MODEL_TYPE_QNN_LLAMA, path, tokenizer.absolutePath, 0.2f, emptyList<String>())
        m.load()  // force load now so failures surface here, not at first generate()
        synchronized(lock) { module = m }
    }

    override fun generate(prompt: String, params: GenParams): Flow<String> = callbackFlow {
        val m = module ?: throw IllegalStateException("ExecuTorch model not loaded")
        val fam = family
        val full = QnnModelInfo.buildPrompt(fam, params.systemPrompt, prompt)
        val finished = java.util.concurrent.atomic.AtomicBoolean(false)
        val stopped = java.util.concurrent.atomic.AtomicBoolean(false)
        val buf = StringBuilder()
        val cb = object : LlmCallback {
            override fun onResult(result: String) {
                if (stopped.get()) return
                buf.append(result)
                val cut = QnnModelInfo.cutAtStop(fam, buf.toString())
                if (cut != null) {
                    // Emit what we have not yet emitted, then stop generation.
                    val already = buf.length - result.length
                    val tail = cut.substring(minOf(already, cut.length))
                    if (tail.isNotEmpty()) trySend(tail)
                    stopped.set(true)
                    m.stop()
                } else trySend(result)
            }
            override fun onError(errorCode: Int, message: String) {
                DebugLog.w("ExecuTorch", "error $errorCode: $message")
            }
        }
        val job = launch(Dispatchers.IO) {
            try {
                val seqLen = (full.length / 3 + params.maxTokens).coerceAtMost(maxCtx.coerceAtLeast(256))
                synchronized(lock) {
                    check(module === m) { "ExecuTorch model was unloaded" }
                    m.generate(full, seqLen, cb, /*echo=*/false, params.temperature.coerceAtLeast(0f), /*numBos=*/1, /*numEos=*/0)
                }
                finished.set(true)
                close()
            } catch (e: Throwable) {
                finished.set(true)
                close(if (stopped.get()) null else e)
            }
        }
        awaitClose { if (!finished.get() && job.isActive) m.stop() }
    }

    override fun unload() {
        val m = module ?: return
        runCatching { m.stop() }  // makes an in-flight generate() return so the lock below is released
        synchronized(lock) {
            if (module === m) module = null
            runCatching { m.close() }
        }
    }

    /** Finds a tokenizer next to the model, else downloads the matching one (verified by SHA256). */
    private fun ensureTokenizer(model: File): File {
        QnnModelInfo.tokenizerCandidates(model).firstOrNull { it.isFile && it.length() > 0 }?.let { return it }
        val src = QnnModelInfo.tokenizerSource(model.name) ?: throw IllegalStateException(
            "No tokenizer found. Put tokenizer.json (HuggingFace format) next to ${model.name} as '${QnnModelInfo.downloadTarget(model).name}'.")
        val target = QnnModelInfo.downloadTarget(model)
        val part = File(target.path + ".part")
        DebugLog.i("ExecuTorch", "downloading tokenizer ${src.url}")
        http.newCall(Request.Builder().url(src.url).build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Tokenizer download failed: HTTP ${r.code}")
            val md = MessageDigest.getInstance("SHA-256")
            part.outputStream().use { out ->
                r.body!!.byteStream().use { ins ->
                    val b = ByteArray(64 * 1024)
                    while (true) { val n = ins.read(b); if (n < 0) break; md.update(b, 0, n); out.write(b, 0, n) }
                }
            }
            val hex = md.digest().joinToString("") { "%02x".format(it) }
            if (part.length() != src.sizeBytes || hex != src.sha256) {
                part.delete(); throw IOException("Tokenizer download corrupt (size/SHA256 mismatch)")
            }
        }
        if (!part.renameTo(target)) throw IOException("Cannot move tokenizer into place")
        return target
    }

    companion object {
        /** JNI model_type_category for example::Runner (QNN llama); not exposed as a Kotlin constant in AAR 1.5.1. */
        private const val MODEL_TYPE_QNN_LLAMA = 4

        /** Native libraries that must be present in nativeLibraryDir (from the executorch-android-qnn + qnn-runtime AARs). */
        private val CORE_LIBS = listOf("libexecutorch.so", "libqnn_executorch_backend.so", "libQnnHtp.so", "libQnnSystem.so")

        /**
         * null if this backend can run, otherwise a human-readable reason. [modelPath] = the selected `.pte`;
         * if null the app's models/llm directory is scanned for any `.pte`.
         */
        fun availability(context: Context, modelPath: String? = null): String? {
            if (Build.SUPPORTED_ABIS.none { it == "arm64-v8a" }) return "ExecuTorch-QNN needs an arm64-v8a device."
            val soc = if (Build.VERSION.SDK_INT >= 31) {
                val mfr = Build.SOC_MANUFACTURER.orEmpty()
                if (!mfr.equals("QTI", true) && !mfr.contains("qualcomm", true))
                    return "Not a Qualcomm SoC (${mfr.ifBlank { "unknown" }} ${Build.SOC_MODEL}); the QNN backend only runs on Snapdragon."
                QnnModelInfo.normalizeSoc(Build.SOC_MODEL)
            } else return "Android 12+ is required for the QNN backend."
            val arch = QnnModelInfo.htpArch(soc)
                ?: return "Unsupported Snapdragon model '${Build.SOC_MODEL}' (supported: SM8350, SM8450, SM8550, SM8650, SM8750, SM8850 families)."
            val nativeDir = File(context.applicationInfo.nativeLibraryDir)
            val libs = CORE_LIBS + listOf("libQnnHtpV${arch}Stub.so", "libQnnHtpV${arch}Skel.so")
            val missing = libs.filterNot { File(nativeDir, it).isFile }
            if (missing.isNotEmpty()) return "Missing native libraries in this build: ${missing.joinToString()}. " +
                "Hexagon V$arch support needs a newer Qualcomm qnn-runtime AAR (see docs/QNN.md)."
            val model: File? = modelPath?.let(::File) ?: findPte(context)
            if (model == null) return "No ExecuTorch .pte model downloaded. Download a QNN model for $soc (see Models)."
            if (!model.isFile) return "Model file not found: ${model.path}"
            if (!model.name.endsWith(".pte")) return "The selected model is not an ExecuTorch .pte file (${model.name})."
            val tag = QnnModelInfo.socTag(model.name)
            if (tag != null && tag != soc) return "Model ${model.name} was compiled for $tag but this device is $soc."
            return null
        }

        private fun findPte(context: Context): File? {
            val base = context.getExternalFilesDir(null) ?: context.filesDir
            return File(base, "models/llm").listFiles { f -> f.name.endsWith(".pte") }?.firstOrNull()
        }
    }
}
