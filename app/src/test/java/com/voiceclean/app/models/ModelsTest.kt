package com.voiceclean.app.models

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ModelsTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun catalogSanity() {
        val ids = ModelCatalog.models.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        ModelCatalog.models.forEach {
            assertTrue(it.sizeBytes > 10_000_000)
            assertEquals(64, it.sha256?.length)
            assertTrue(it.fileName.endsWith(if (it.kind == ModelKind.LLM) ".gguf" else ".bin") || (it.kind == ModelKind.LLM && it.fileName.endsWith(".pte")))
            assertTrue(it.url.startsWith("https://huggingface.co/${it.repo}/resolve/main/"))
        }
        assertTrue(ModelCatalog.byKind(ModelKind.STT).size >= 4 && ModelCatalog.byKind(ModelKind.LLM).size >= 5)
    }

    @Test fun parseReferences() {
        val a = parseHfReference("https://huggingface.co/Qwen/Qwen3-4B-GGUF/blob/main/Qwen3-4B-Q8_0.gguf?download=true")!!
        assertEquals(HfFileRef("Qwen/Qwen3-4B-GGUF", "main", "Qwen3-4B-Q8_0.gguf"), a)
        assertEquals("https://huggingface.co/Qwen/Qwen3-4B-GGUF/resolve/main/Qwen3-4B-Q8_0.gguf", a.url)
        assertEquals(HfFileRef("a/b", "main", "x.gguf"), parseHfReference("a/b", "x.gguf"))
        assertEquals(HfFileRef("a/b", "main", "x.gguf"), parseHfReference("a/b/x.gguf"))
        assertNull(parseHfReference("https://example.com/a/b/c.gguf"))
        assertNull(parseHfReference("justone"))
        assertNull(parseHfReference("a/b"))
        assertEquals("x.gguf", safeModelFileName("sub/x.gguf", ModelKind.LLM))
        assertNull(safeModelFileName("x.bin", ModelKind.LLM))
        assertTrue(magicMatches(ModelKind.LLM, "GGUF".toByteArray()))
        assertTrue(magicMatches(ModelKind.STT, "lmgg".toByteArray()))
        assertFalse(magicMatches(ModelKind.STT, "GGUF".toByteArray()))
    }

    @Test fun contentRange() {
        assertEquals(Triple(5L, 9L, 10L), parseContentRange("bytes 5-9/10"))
        assertNull(parseContentRange("garbage"))
    }

    private val data = ByteArray(1_000_000) { (it * 31).toByte() }

    /** Range-aware server; optionally kills the first response mid-body. */
    private fun server(honorRange: Boolean = true, dropFirst: Boolean = false): MockWebServer {
        var calls = 0
        val s = MockWebServer()
        s.dispatcher = object : Dispatcher() {
            override fun dispatch(r: RecordedRequest): MockResponse {
                calls++
                val range = r.getHeader("Range")?.let { Regex("bytes=(\\d+)-").find(it)?.groupValues?.get(1)?.toInt() }
                val start = if (honorRange) range ?: 0 else 0
                val body = Buffer().write(data, start, data.size - start)
                val resp = MockResponse().setBody(body)
                if (honorRange && range != null) {
                    resp.setResponseCode(206).setHeader("Content-Range", "bytes $start-${data.size - 1}/${data.size}")
                }
                if (dropFirst && calls == 1) resp.setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
                return resp
            }
        }
        s.start()
        return s
    }

    private fun dl() = ResumableDownloader(OkHttpClient(), backoffMs = { 10 })

    @Test fun resumesFromPartFile() = runBlocking {
        val s = server()
        val part = tmp.newFile("m.part").apply { writeBytes(data.copyOf(400_000)) }
        dl().download(s.url("/f").toString(), part, data.size.toLong(), null, { _, _ -> })
        assertArrayEquals(data, part.readBytes())
        assertEquals("bytes=400000-", s.takeRequest().getHeader("Range"))
        s.shutdown()
    }

    @Test fun serverIgnoringRangeRestarts() = runBlocking {
        val s = server(honorRange = false)
        val part = tmp.newFile("m2.part").apply { writeBytes(ByteArray(1234) { 7 }) }
        dl().download(s.url("/f").toString(), part, data.size.toLong(), null, { _, _ -> })
        assertArrayEquals(data, part.readBytes())
        s.shutdown()
    }

    @Test fun retriesAfterNetworkDrop() = runBlocking {
        val s = server(dropFirst = true)
        val part = tmp.newFile("m3.part"); part.delete()
        var retried = false
        dl().download(s.url("/f").toString(), part, data.size.toLong(), null, { _, _ -> }, { retried = true })
        assertTrue(retried)
        assertArrayEquals(data, part.readBytes())
        s.shutdown()
    }

    @Test fun authFailureIsFatal() = runBlocking {
        val s = MockWebServer(); s.enqueue(MockResponse().setResponseCode(401)); s.start()
        val part = tmp.newFile("m4.part")
        try {
            dl().download(s.url("/f").toString(), part, 100, null, { _, _ -> })
            fail()
        } catch (e: FatalDownloadException) { assertTrue(e.message!!.contains("token")) }
        s.shutdown()
    }

    @Test fun managerVerifiesAndCommits() = runBlocking {
        val s = server()
        val dest = java.io.File(tmp.newFolder("llm"), "m.gguf")
        val dm = DownloadManager(OkHttpClient(), { null }, freeSpace = { Long.MAX_VALUE }, downloader = dl())
        val sha = java.security.MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
        val spec = DownloadSpec("t", s.url("/f").toString(), dest, data.size.toLong(), sha)
        val done = kotlinx.coroutines.CompletableDeferred<String>()
        val j = launch(kotlinx.coroutines.Dispatchers.Default) { done.complete(dm.completed.first()) }
        kotlinx.coroutines.delay(100)
        dm.start(spec)
        assertEquals("t", kotlinx.coroutines.withTimeout(10_000) { done.await() })
        assertTrue(dest.isFile); assertFalse(dm.partFile(dest).exists())
        assertTrue(dm.states.value.isEmpty())
        // bad checksum -> FAILED, nothing committed
        val dest2 = java.io.File(dest.parentFile, "bad.gguf")
        dm.start(DownloadSpec("bad", s.url("/f").toString(), dest2, data.size.toLong(), "0".repeat(64)))
        kotlinx.coroutines.withTimeout(10_000) { dm.states.first { it["bad"]?.status == DownloadStatus.FAILED } }
        assertFalse(dest2.exists())
        j.cancel(); s.shutdown()
    }

    @Test fun lowDiskFails() = runBlocking {
        val dest = java.io.File(tmp.newFolder("x"), "m.gguf")
        val dm = DownloadManager(OkHttpClient(), { null }, freeSpace = { 1000L }, downloader = dl())
        dm.start(DownloadSpec("d", "http://localhost:1/f", dest, 10_000_000, null))
        val st = kotlinx.coroutines.withTimeout(10_000) { dm.states.first { it["d"]?.status == DownloadStatus.FAILED } }
        assertTrue(st["d"]!!.message!!.contains("free storage"))
    }
}
