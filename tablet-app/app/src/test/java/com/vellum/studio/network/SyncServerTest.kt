package com.vellum.studio.network

import android.app.Application
import com.vellum.studio.VellumApp
import com.vellum.studio.model.ProjectRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * Regression coverage for the SyncServer path-traversal hole: the project id used to be sliced out of
 * the URL and joined to the projects root unvalidated, so `GET /projects/../export.zip` zipped the
 * PARENT of the projects directory -- the user's imported photos (photo_templates/), palettes,
 * custom brushes, Academy progress and every project -- to any peer on the LAN.
 *
 * Every hostile request here goes over a RAW socket, not [HttpURLConnection]: the JDK client
 * normalizes dot segments before sending, so `/projects/../export.zip` would arrive as `/export.zip`
 * and never exercise the bug. NanoHTTPD 2.3.1 percent-decodes the request URI but does not normalize
 * it, so the literal `..` (raw or `%2e%2e`) reaches the handler exactly as an attacker would send it.
 * The one happy-path test does use [HttpURLConnection] -- there is nothing hostile in a UUID path, and
 * it also decodes the chunked transfer encoding the streamed zip uses.
 *
 * Uses [VellumApp] as the Robolectric application for the same reason ProjectRepositoryTest does
 * (persist()'s thumbnail step reads [VellumApp.instance]), and `@GraphicsMode(NATIVE)` so the layer
 * PNGs written by persist() are real PNG bytes rather than Robolectric's legacy stand-ins.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = VellumApp::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SyncServerTest {

    private val app: Application = RuntimeEnvironment.getApplication()
    private val repository = ProjectRepository(app)
    private lateinit var server: SyncServer
    private val port get() = server.listeningPort

    @Before
    fun startServer() {
        // Port 0: the OS picks a free ephemeral port so the test never collides with a real server.
        server = SyncServer(repository, app, port = 0)
        server.start()
    }

    @After
    fun stopServer() {
        server.stop()
    }

    private class RawResponse(val status: Int, val headers: String, val body: String)

    /** Sends [requestLine] verbatim (no client-side normalization) and reads the response to EOF. */
    private fun raw(requestLine: String): RawResponse {
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 10_000
            socket.getOutputStream().apply {
                write("$requestLine\r\nHost: localhost\r\nConnection: close\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
                flush()
            }
            val bytes = ByteArrayOutputStream().also { socket.getInputStream().copyTo(it) }.toByteArray()
            val text = String(bytes, Charsets.ISO_8859_1)
            val status = text.lineSequence().first().split(' ')[1].toInt()
            return RawResponse(status, text.substringBefore("\r\n\r\n"), text.substringAfter("\r\n\r\n", ""))
        }
    }

    private fun get(path: String) = raw("GET $path HTTP/1.1")

    /** A file the traversal would have exposed: it lives in the projects root's PARENT. */
    private fun plantSensitiveSibling(): File {
        val photoTemplates = File(app.getExternalFilesDir(null), "photo_templates").apply { mkdirs() }
        return File(photoTemplates, "secret.jpg").apply { writeText("not for the LAN") }
    }

    private fun createProjectId(): String = runBlocking {
        val (meta, engine) = repository.createProject("Sync test", 64, 64)
        engine.layers.forEach { it.bitmap.recycle() }
        meta.id
    }

    @Test
    fun `dot-dot ids return 404 on both routes and never leak the parent directory`() {
        plantSensitiveSibling()
        val hostile = listOf(
            "/projects/../export.zip",
            "/projects/%2e%2e/export.zip",
            "/projects/%2E%2E/export.zip",
            "/projects/../thumbnail.png",
            "/projects/%2e%2e/thumbnail.png",
            "/projects/../photo_templates/x/export.zip",
            "/projects/../../export.zip",
            "/projects/%2e%2e%2fphoto_templates%2fx/export.zip",
            "/projects/./export.zip",
            "/projects//export.zip",
        )
        for (path in hostile) {
            val r = get(path)
            assertEquals("$path must be 404", 404, r.status)
            assertFalse("$path must not return any zip bytes", r.body.startsWith("PK"))
        }
    }

    @Test
    fun `CRLF and control character ids return 404`() {
        val id = createProjectId()
        val hostile = listOf(
            "/projects/$id%0d%0a/export.zip", // a real id with a decoded CRLF appended
            "/projects/%0d%0a/export.zip",
            "/projects/$id%0a/thumbnail.png",
            "/projects/$id%00/export.zip",
            "/projects/$id%20/export.zip",
        )
        for (path in hostile) {
            assertEquals("$path must be 404", 404, get(path).status)
        }
    }

    @Test
    fun `well-formed but unknown UUID returns 404 not an empty zip`() {
        val r = get("/projects/00000000-0000-0000-0000-000000000000/export.zip")
        assertEquals(404, r.status)
    }

    @Test
    fun `trash folder and non-UUID sibling directories are unreachable`() {
        val root = File(app.getExternalFilesDir(null), "projects")
        File(root, ".trash").apply { mkdirs() }.also { File(it, "metadata.json").writeText("{}") }
        File(root, "not-a-uuid").apply { mkdirs() }
        for (name in listOf(".trash", "not-a-uuid")) {
            assertEquals("$name export", 404, get("/projects/$name/export.zip").status)
            assertEquals("$name thumbnail", 404, get("/projects/$name/thumbnail.png").status)
        }
    }

    @Test
    fun `non GET or HEAD methods are refused with 405`() {
        val id = createProjectId()
        for (method in listOf("POST", "PUT", "DELETE")) {
            assertEquals("$method /projects", 405, raw("$method /projects HTTP/1.1").status)
            assertEquals("$method export", 405, raw("$method /projects/$id/export.zip HTTP/1.1").status)
        }
    }

    @Test
    fun `HEAD on an export is answered without a body`() {
        val id = createProjectId()
        val r = raw("HEAD /projects/$id/export.zip HTTP/1.1")
        assertEquals(200, r.status)
        assertEquals("", r.body)
        // NanoHTTPD prints a chunked response's length (-1) verbatim on HEAD; .NET HttpClient (the
        // PC companion) rejects that as malformed, so a HEAD must carry a valid length or none.
        val lengths = Regex("""(?im)^content-length:\h*(\S+)\h*$""").findAll(r.headers).map { it.groupValues[1] }.toList()
        assertTrue("Content-Length must be a non-negative integer, was $lengths", lengths.all { it.toLongOrNull()?.let { n -> n >= 0 } == true })
        assertFalse("HEAD must not advertise chunked framing it will not send", r.headers.contains("chunked", ignoreCase = true))
    }

    /**
     * Starts a server whose zip writer is [writer], for driving the mid-stream failure paths. The
     * writers below mirror the mechanism of a real failure: ProjectRepository.exportProjectZipTo runs
     * inside ZipOutputStream(out).use{}, so an unreadable layer mid-export unwinds through close(),
     * which finish()es a valid-looking archive and closes the sink it was given.
     */
    private fun <T> withFailingExport(writer: (String, java.io.OutputStream) -> Boolean, block: (String, Int) -> T): T {
        val id = createProjectId()
        val failing = SyncServer(repository, app, port = 0, zipWriter = writer)
        failing.start()
        try {
            return block(id, failing.listeningPort)
        } finally {
            failing.stop()
        }
    }

    private fun assertDownloadErrors(id: String, port: Int) {
        val conn = URL("http://127.0.0.1:$port/projects/$id/export.zip").openConnection() as HttpURLConnection
        conn.readTimeout = 10_000
        assertEquals("headers are already committed as 200 before the zip fails", 200, conn.responseCode)
        try {
            conn.inputStream.readBytes()
        } catch (expected: java.io.IOException) {
            return
        }
        org.junit.Assert.fail("a failed export must surface as a broken response, not a cleanly terminated (truncated) zip")
    }

    @Test
    fun `mid-stream zip failure aborts the response instead of ending as a clean truncated zip`() {
        // A layer that vanishes / can't be read after one entry already went out. The IOException is
        // thrown INSIDE use{}, so close() runs finish() and closes the sink BEFORE the exception
        // reaches SyncServer's catch: the regression this pins is the reader seeing EOF with no
        // failure recorded and NanoHTTPD sending the chunk terminator. The race was probabilistic
        // (~17% of runs in the review), so repeat it.
        repeat(40) {
            withFailingExport({ _, out ->
                java.util.zip.ZipOutputStream(out).use { zos ->
                    zos.putNextEntry(ZipEntry("metadata.json"))
                    zos.write("{}".toByteArray())
                    zos.closeEntry()
                    throw java.io.FileNotFoundException("layers/gone.png")
                }
                true
            }) { id, port -> assertDownloadErrors(id, port) }
        }
    }

    @Test
    fun `failure before the first byte and a vanished project both abort the response`() {
        withFailingExport({ _, _ -> throw IllegalStateException("boom") }) { id, port -> assertDownloadErrors(id, port) }
        withFailingExport({ _, _ -> false }) { id, port -> assertDownloadErrors(id, port) }
    }

    @Test
    fun `the exception message of a failed export never reaches the peer`() {
        withFailingExport({ _, out ->
            java.util.zip.ZipOutputStream(out).use { throw java.io.IOException("read failed at /secret/absolute/path") }
        }) { id, port ->
            Socket("127.0.0.1", port).use { s ->
                s.soTimeout = 10_000
                s.getOutputStream().write("GET /projects/$id/export.zip HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n".toByteArray())
                val text = String(s.getInputStream().readBytes(), Charsets.ISO_8859_1)
                assertFalse(text.contains("/secret/absolute/path"))
            }
        }
    }

    @Test
    fun `internal failures return a generic 500 that does not leak the exception message`() {
        // A context whose external-files lookup throws with an absolute path in the message makes
        // every repository call fail the way a real storage fault would.
        val brokenContext = object : android.content.ContextWrapper(app) {
            override fun getExternalFilesDir(type: String?): File = throw IllegalStateException("disk fault at /secret/absolute/path")
        }
        val exploding = SyncServer(ProjectRepository(brokenContext), app, port = 0)
        exploding.start()
        try {
            Socket("127.0.0.1", exploding.listeningPort).use { s ->
                s.soTimeout = 10_000
                s.getOutputStream().write("GET /projects HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n".toByteArray())
                val text = String(s.getInputStream().readBytes(), Charsets.ISO_8859_1)
                assertTrue(text.startsWith("HTTP/1.1 500"))
                assertTrue(text.endsWith("Internal error"))
                assertFalse("the real exception message must not reach the peer", text.contains("/secret/absolute/path"))
            }
        } finally {
            exploding.stop()
        }
    }

    @Test
    fun `happy path - project list, thumbnail and streamed export zip still work`() {
        val id = createProjectId()

        val list = URL("http://127.0.0.1:$port/projects").openConnection() as HttpURLConnection
        assertEquals(200, list.responseCode)
        assertTrue(list.inputStream.readBytes().toString(Charsets.UTF_8).contains(id))

        val thumb = URL("http://127.0.0.1:$port/projects/$id/thumbnail.png").openConnection() as HttpURLConnection
        assertEquals(200, thumb.responseCode)
        assertTrue(thumb.inputStream.readBytes().isNotEmpty())

        val zipConn = URL("http://127.0.0.1:$port/projects/$id/export.zip").openConnection() as HttpURLConnection
        assertEquals(200, zipConn.responseCode)
        assertEquals("application/zip", zipConn.contentType)
        val entries = mutableMapOf<String, ZipEntry>()
        ZipInputStream(zipConn.inputStream).use { zis ->
            while (true) {
                val e = zis.nextEntry ?: break
                zis.readBytes() // drain, which also verifies each entry's CRC/size
                entries[e.name] = e
            }
        }
        assertTrue("metadata.json in zip: ${entries.keys}", entries.containsKey("metadata.json"))
        val layerEntries = entries.filterKeys { it.startsWith("layers/") && it.endsWith(".png") }
        assertTrue("layer PNG in zip: ${entries.keys}", layerEntries.isNotEmpty())
        // Entry names must be zip-style '/' paths and never escape the project folder.
        assertTrue(entries.keys.none { it.contains('\\') || it.startsWith("..") || it.startsWith("/") })
    }

    @Test
    fun `export stores PNG layers uncompressed and leaves no temp file behind`() {
        val id = createProjectId()
        val out = ByteArrayOutputStream()
        assertTrue(repository.exportProjectZipTo(id, out))

        // ZipInputStream can't report the method of a STORED-with-known-size entry reliably across
        // JDKs, so inspect via java.util.zip.ZipFile on a scratch copy (the file is test-scoped).
        val scratch = File.createTempFile("vellum-export-test", ".zip")
        try {
            scratch.writeBytes(out.toByteArray())
            java.util.zip.ZipFile(scratch).use { zf ->
                val pngs = zf.entries().toList().filter { it.name.endsWith(".png") }
                assertTrue(pngs.isNotEmpty())
                for (e in pngs) assertEquals("${e.name} should be STORED", ZipEntry.STORED, e.method)
                val meta = zf.getEntry("metadata.json")
                assertEquals("metadata.json is text, deflating it is fine", ZipEntry.DEFLATED, meta.method)
            }
        } finally {
            scratch.delete()
        }

        val leftovers = app.cacheDir.listFiles { f -> f.name.startsWith("export_") }.orEmpty()
        assertTrue("no export_*.zip may be left in cacheDir: ${leftovers.toList()}", leftovers.isEmpty())

        // And the same through the server: a full download must not create one either.
        val conn = URL("http://127.0.0.1:$port/projects/$id/export.zip").openConnection() as HttpURLConnection
        conn.inputStream.readBytes()
        assertTrue(app.cacheDir.listFiles { f -> f.name.startsWith("export_") }.orEmpty().isEmpty())
    }
}
