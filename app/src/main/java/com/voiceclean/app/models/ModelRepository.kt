package com.voiceclean.app.models

import android.content.Context
import com.voiceclean.app.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

data class ModelEntry(
    val model: CatalogModel,
    val installed: Boolean,
    val installedBytes: Long,
    /** Bytes of a leftover .part file when no transfer is running (resumable). */
    val partialBytes: Long,
    val download: DownloadProgress?,
    val active: Boolean,
)

/**
 * Source of truth for models: catalog + custom entries, installed state, active selection and deletion.
 *
 * Other modules: use [activeSttPath] / [activeLlmPath] (null = nothing selected / file missing).
 */
class ModelRepository(
    context: Context,
    private val settings: SettingsRepository,
    private val downloads: DownloadManager,
    private val http: OkHttpClient,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    rootDirOverride: File? = null,
) {
    private val appContext = context.applicationContext
    val rootDir: File = rootDirOverride ?: File(appContext.getExternalFilesDir(null) ?: appContext.filesDir, "models")
    private val customFile = File(appContext.filesDir, "custom_models.json")

    /** Bumped whenever files on disk or the custom list change. */
    private val diskTick = MutableStateFlow(0)
    private val customModels = MutableStateFlow(loadCustom())

    init {
        rootDir.mkdirs()
        // Auto-select a freshly downloaded model if nothing valid is active for its kind.
        scope.launch {
            downloads.completed.collect { id ->
                diskTick.value++
                val m = find(id) ?: return@collect
                val s = settings.settings.first()
                val cur = if (m.kind == ModelKind.STT) s.activeSttModel else s.activeLlmModel
                val curModel = find(cur)
                if (curModel == null || !fileFor(curModel).exists()) select(m)
            }
        }
    }

    fun fileFor(m: CatalogModel) = File(File(rootDir, m.kind.dir), m.fileName)
    fun find(id: String): CatalogModel? = ModelCatalog.byId(id) ?: customModels.value.firstOrNull { it.id == id }
    fun specFor(m: CatalogModel) = DownloadSpec(m.id, m.url, fileFor(m), m.sizeBytes, m.sha256)

    val entries: Flow<List<ModelEntry>> = combine(
        customModels, downloads.states, settings.settings, diskTick,
    ) { custom, dl, s, _ ->
        (ModelCatalog.models + custom).map { m ->
            val f = fileFor(m)
            val installed = f.isFile
            val partial = if (installed) 0L else downloads.partFile(f).takeIf { it.isFile }?.length() ?: 0L
            ModelEntry(
                m, installed, if (installed) f.length() else 0L, partial, dl[m.id],
                active = installed && (if (m.kind == ModelKind.STT) s.activeSttModel else s.activeLlmModel) == m.id,
            )
        }
    }.distinctUntilChanged()

    /** Total bytes used under models/ (including partial downloads). */
    val storageUsedBytes: Flow<Long> = combine(diskTick, downloads.states) { _, _ -> }
        .map { withContext(Dispatchers.IO) { rootDir.walkTopDown().filter { it.isFile }.sumOf { it.length() } } }
        .distinctUntilChanged()

    val hfToken: Flow<String> get() = settings.hfToken
    suspend fun setHfToken(v: String) = settings.setHfToken(v)

    /** Absolute path of the active whisper model file, or null. Emits on selection/download/delete changes. */
    fun activeSttPath(): Flow<String?> = activePath(ModelKind.STT)

    /** Absolute path of the active GGUF LLM file, or null. */
    fun activeLlmPath(): Flow<String?> = activePath(ModelKind.LLM)

    private fun activePath(kind: ModelKind): Flow<String?> =
        combine(settings.settings.map { if (kind == ModelKind.STT) it.activeSttModel else it.activeLlmModel }, customModels, diskTick) { id, _, _ ->
            find(id)?.let(::fileFor)?.takeIf { it.isFile }?.absolutePath
        }.distinctUntilChanged()

    suspend fun select(m: CatalogModel) {
        if (m.kind == ModelKind.STT) settings.setActiveSttModel(m.id) else settings.setActiveLlmModel(m.id)
    }

    fun download(m: CatalogModel) = downloads.start(specFor(m))
    fun pause(m: CatalogModel) = downloads.pause(specFor(m))
    fun cancel(m: CatalogModel) { downloads.cancel(specFor(m)); diskTick.value++ }
    fun dismissError(m: CatalogModel) = downloads.clearFailed(m.id)

    /** Deletes the model file (and any .part). Clears the active selection if it pointed here. */
    suspend fun delete(m: CatalogModel) {
        withContext(Dispatchers.IO) {
            downloads.cancel(specFor(m))
            fileFor(m).delete()
            downloads.partFile(fileFor(m)).delete()
        }
        val s = settings.settings.first()
        if (m.kind == ModelKind.STT && s.activeSttModel == m.id) settings.setActiveSttModel("")
        if (m.kind == ModelKind.LLM && s.activeLlmModel == m.id) settings.setActiveLlmModel("")
        if (m.custom) {
            customModels.value = customModels.value.filterNot { it.id == m.id }
            saveCustom()
        }
        diskTick.value++
    }

    /**
     * Validates a Hugging Face reference (ranged GET of the first bytes: checks existence, auth, size, magic bytes)
     * and registers it as a custom model (not downloaded yet).
     */
    suspend fun addCustom(input: String, fileName: String?, kind: ModelKind): Result<CatalogModel> = withContext(Dispatchers.IO) {
        runCatching {
            val ref = parseHfReference(input, fileName)
                ?: throw IOException("Could not parse. Use a huggingface.co file URL, or 'owner/repo' plus a file name.")
            val local = safeModelFileName(ref.file, kind)
                ?: throw IOException("File must end in ${if (kind == ModelKind.LLM) ".gguf" else ".bin"}.")
            val token = settings.hfToken.first()
            val rb = Request.Builder().url(ref.url).header("Range", "bytes=0-15").header("Accept-Encoding", "identity")
            if (token.isNotBlank()) rb.header("Authorization", "Bearer $token")
            val (head, total) = http.newCall(rb.build()).execute().use { r ->
                when (r.code) {
                    200, 206 -> {}
                    401, 403 -> throw IOException("Access denied (HTTP ${r.code}). Gated repo? Add a Hugging Face token and accept the license on the model page.")
                    404 -> throw IOException("Not found (HTTP 404). Check repo and file name.")
                    else -> throw IOException("HTTP ${r.code}")
                }
                val bytes = r.body?.source()?.let { src -> ByteArray(4).also { src.readFully(it) } } ?: ByteArray(0)
                val tot = parseContentRange(r.header("Content-Range"))?.third ?: r.body?.contentLength() ?: -1L
                bytes to tot
            }
            if (!magicMatches(kind, head)) throw IOException("Not a valid ${if (kind == ModelKind.LLM) "GGUF" else "whisper.cpp ggml"} file.")
            if (total <= 0) throw IOException("Server did not report file size.")
            val existing = ModelCatalog.models.firstOrNull { it.repo == ref.repo && it.fileName == local }
            if (existing != null) return@runCatching existing
            val id = "custom-" + Integer.toHexString((ref.repo + "/" + ref.revision + "/" + ref.file).hashCode())
            val m = CatalogModel(
                id, kind, local.removeSuffix(if (kind == ModelKind.LLM) ".gguf" else ".bin"), ref.repo, local, total,
                Regex("""(?i)(IQ\d_\w+|Q\d_\w+|F16|BF16|F32)""").find(local)?.value?.uppercase() ?: "custom",
                (total * 1.25 / 1_048_576).toInt(), "Custom: ${ref.repo}/${ref.file}", emptySet(),
                revision = ref.revision, custom = true,
                urlOverride = ref.url,
            )
            customModels.value = customModels.value.filterNot { it.id == id } + m
            saveCustom()
            m
        }
    }

    private fun loadCustom(): List<CatalogModel> = try {
        if (!customFile.exists()) emptyList() else {
            val arr = JSONArray(customFile.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                CatalogModel(
                    o.getString("id"), ModelKind.valueOf(o.getString("kind")), o.getString("name"), o.getString("repo"),
                    o.getString("file"), o.getLong("size"), o.optString("quant", "custom"), o.optInt("ram", 0),
                    o.optString("desc", ""), emptySet(), revision = o.optString("rev", "main"), custom = true,
                    urlOverride = o.optString("url").ifBlank { null },
                )
            }
        }
    } catch (e: Exception) { emptyList() }

    private fun saveCustom() {
        val arr = JSONArray()
        customModels.value.forEach { m ->
            arr.put(JSONObject().put("id", m.id).put("kind", m.kind.name).put("name", m.displayName).put("repo", m.repo)
                .put("file", m.fileName).put("size", m.sizeBytes).put("quant", m.quant).put("ram", m.ramHintMb)
                .put("desc", m.description).put("rev", m.revision).put("url", m.urlOverride ?: ""))
        }
        customFile.writeText(arr.toString())
    }
}
