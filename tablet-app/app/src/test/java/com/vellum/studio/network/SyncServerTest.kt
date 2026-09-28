package com.vellum.studio.network

import android.app.Application
import com.vellum.studio.VellumApp
import com.vellum.studio.model.ProjectRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
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
import java.util.concurrent.atomic.AtomicLong
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

    /**
     * Every server under test binds loopback only (the same "explicit address, never the wildcard"
     * contract production has) and uses a fixed PIN so requests can carry it.
     */
    private fun newServer(
        repo: ProjectRepository = repository,
        guard: PairingGuard = PairingGuard(TEST_PIN),
        idleTimeoutMs: Long = SyncServer.DEFAULT_IDLE_TIMEOUT_MS,
        clockMs: () -> Long = { System.nanoTime() / 1_000_000 },
        zipWriter: ((String, java.io.OutputStream) -> Boolean)? = null,
    ): SyncServer {
        // Port 0: the OS picks a free ephemeral port so the test never collides with a real server.
        val writer = zipWriter ?: repo::exportProjectZipTo
        return SyncServer(repo, app, LOOPBACK, port = 0, guard = guard, idleTimeoutMs = idleTimeoutMs, clockMs = clockMs, zipWriter = writer)
    }

    @Before
    fun startServer() {
        server = newServer()
        server.start()
    }

    @After
    fun stopServer() {
        server.stop()
    }

    private class RawResponse(val status: Int, val headers: String, val body: String)

    /**
     * Sends [requestLine] verbatim (no client-side normalization) and reads the response to EOF. Carries
     * [pin] as X-Vellum-Pin unless it is null (the unauthenticated case the auth tests need).
     */
    private fun raw(requestLine: String, pin: String? = TEST_PIN, target: SyncServer = server): RawResponse {
        Socket(LOOPBACK, target.listeningPort).use { socket ->
            socket.soTimeout = 10_000
            val pinHeader = if (pin != null) "X-Vellum-Pin: $pin\r\n" else ""
            socket.getOutputStream().apply {
                write("$requestLine\r\nHost: localhost\r\n${pinHeader}Connection: close\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
                flush()
            }
            val bytes = ByteArrayOutputStream().also { socket.getInputStream().copyTo(it) }.toByteArray()
            val text = String(bytes, Charsets.ISO_8859_1)
            val status = text.lineSequence().first().split(' ')[1].toInt()
            return RawResponse(status, text.substringBefore("\r\n\r\n"), text.substringAfter("\r\n\r\n", ""))
        }
    }

    private fun get(path: String, pin: String? = TEST_PIN) = raw("GET $path HTTP/1.1", pin)

    private fun authedConnection(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply { setRequestProperty("X-Vellum-Pin", TEST_PIN) }

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
        val failing = newServer(zipWriter = writer)
        failing.start()
        try {
            return block(id, failing.listeningPort)
        } finally {
            failing.stop()
        }
    }

    private fun assertDownloadErrors(id: String, port: Int) {
        val conn = authedConnection("http://127.0.0.1:$port/projects/$id/export.zip")
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
                s.getOutputStream().write("GET /projects/$id/export.zip HTTP/1.1\r\nHost: x\r\nX-Vellum-Pin: $TEST_PIN\r\nConnection: close\r\n\r\n".toByteArray())
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
        val exploding = newServer(ProjectRepository(brokenContext))
        exploding.start()
        try {
            Socket("127.0.0.1", exploding.listeningPort).use { s ->
                s.soTimeout = 10_000
                s.getOutputStream().write("GET /projects HTTP/1.1\r\nHost: x\r\nX-Vellum-Pin: $TEST_PIN\r\nConnection: close\r\n\r\n".toByteArray())
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

        val list = authedConnection("http://127.0.0.1:$port/projects")
        assertEquals(200, list.responseCode)
        assertTrue(list.inputStream.readBytes().toString(Charsets.UTF_8).contains(id))

        val thumb = authedConnection("http://127.0.0.1:$port/projects/$id/thumbnail.png")
        assertEquals(200, thumb.responseCode)
        assertTrue(thumb.inputStream.readBytes().isNotEmpty())

        val zipConn = authedConnection("http://127.0.0.1:$port/projects/$id/export.zip")
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
        val conn = authedConnection("http://127.0.0.1:$port/projects/$id/export.zip")
        conn.inputStream.readBytes()
        assertTrue(app.cacheDir.listFiles { f -> f.name.startsWith("export_") }.orEmpty().isEmpty())
    }

    // ---- pairing PIN, lockout, bind address, idle stop -------------------------------------------

    @Test
    fun `every route refuses a request without the PIN with 401 and leaks nothing`() {
        val id = createProjectId()
        val routes = listOf(
            "/", "/projects", "/mirror/frame.jpg", "/no/such/route",
            "/projects/$id/export.zip", "/projects/$id/thumbnail.png",
            // Bad ids must look identical to good ones without the PIN: no 404-vs-401 oracle.
            "/projects/../export.zip", "/projects/00000000-0000-0000-0000-000000000000/export.zip",
        )
        for (path in routes) {
            val r = get(path, pin = null)
            assertEquals("$path without a PIN", 401, r.status)
            assertTrue("$path must ask for the PIN", r.headers.contains("WWW-Authenticate: VellumPin", ignoreCase = true))
            assertFalse("$path must not echo the project id", r.body.contains(id))
            assertFalse("$path must not return zip/PNG bytes", r.body.startsWith("PK") || r.body.startsWith("\u0089PNG"))
        }
        // Auth runs ahead of the method check too: an unauthenticated POST must not learn "405".
        assertEquals(401, raw("POST /projects HTTP/1.1", pin = null).status)
        assertEquals(401, raw("HEAD /projects/$id/export.zip HTTP/1.1", pin = null).status)
    }

    @Test
    fun `wrong PIN is refused 401 and the right PIN works by header or query parameter`() {
        val id = createProjectId()
        val wrong = get("/projects", pin = "000000")
        assertEquals(401, wrong.status)
        assertFalse("the real PIN must never be echoed back", wrong.body.contains(TEST_PIN) || wrong.headers.contains(TEST_PIN))

        assertNull(server.lastClient)
        val ok = get("/projects")
        assertEquals(200, ok.status)
        assertTrue(ok.body.contains(id))
        assertEquals("the address of the authenticated peer is recorded", "127.0.0.1", server.lastClient)

        // The mirror is meant to be pollable from a browser, which cannot set a custom header.
        assertEquals(200, get("/projects?pin=$TEST_PIN", pin = null).status)
        assertEquals(401, get("/projects?pin=000000", pin = null).status)
    }

    @Test
    fun `five wrong PINs lock the server and even the correct PIN is then refused with 429`() {
        val wrongPins = listOf("000000", "111111", "222222", "333333", "444444")
        for (w in wrongPins) assertEquals("guess $w", 401, get("/projects", pin = w).status)
        assertTrue(server.isLockedOut)

        assertEquals("correct PIN after lockout", 429, get("/projects").status)
        assertEquals("another guess after lockout", 429, get("/projects", pin = "555555").status)
        assertEquals("no PIN after lockout", 429, get("/", pin = null).status)
    }

    @Test
    fun `four wrong PINs do not lock and requests without a PIN never count as failures`() {
        repeat(20) { assertEquals(401, get("/projects", pin = null).status) }
        repeat(4) { assertEquals(401, get("/projects", pin = "00000$it").status) }
        assertFalse(server.isLockedOut)
        assertEquals(200, get("/projects").status)
    }

    @Test
    fun `concurrent wrong guesses cannot exceed the failure budget`() {
        // 16 simultaneous wrong guesses (within the 8 running + 16 queued connection budget). With a
        // check-then-increment race, more than five of them would see "not locked yet" and get a 401.
        val statuses = java.util.Collections.synchronizedList(mutableListOf<Int>())
        val go = java.util.concurrent.CountDownLatch(1)
        val threads = (0 until 16).map { i ->
            Thread {
                go.await()
                statuses += get("/projects", pin = "9000%02d".format(i)).status
            }.apply { start() }
        }
        go.countDown()
        threads.forEach { it.join(30_000) }
        assertEquals(16, statuses.size)
        assertEquals("exactly five guesses are ever evaluated", 5, statuses.count { it == 401 })
        assertEquals(11, statuses.count { it == 429 })
    }

    @Test
    fun `server bound to one address is not reachable through another local address`() {
        val alias = "127.0.0.2" // still loopback, but a different address than LOOPBACK
        fun canConnect(port: Int) = try {
            Socket().use { it.connect(java.net.InetSocketAddress(alias, port), 3_000) }
            true
        } catch (e: java.io.IOException) {
            false
        }

        // Control: the same server class bound to the wildcard IS reachable through the alias. If the
        // platform has no 127.0.0.2 the test can't say anything, so skip rather than pass vacuously.
        val wildcard = newWildcardServer().apply { start() }
        val aliasWorks = try { canConnect(wildcard.listeningPort) } finally { wildcard.stop() }
        assumeTrue("127.0.0.2 is not connectable on this platform", aliasWorks)

        assertFalse("a server bound to $LOOPBACK must refuse connections to $alias", canConnect(port))
    }

    private fun newWildcardServer() = SyncServer(repository, app, "0.0.0.0", port = 0, guard = PairingGuard(TEST_PIN))

    @Test
    fun `only authenticated requests reset the idle deadline`() {
        val now = AtomicLong(0)
        val s = newServer(idleTimeoutMs = 100_000, clockMs = { now.get() }).apply { start() }
        try {
            now.set(40_000)
            assertEquals(60_000, s.idleRemainingMs())
            assertEquals(401, raw("GET /projects HTTP/1.1", pin = null, target = s).status)
            assertEquals(401, raw("GET /projects HTTP/1.1", pin = "000000", target = s).status)
            assertEquals("unauthenticated traffic must not keep the server alive", 60_000, s.idleRemainingMs())
            assertEquals(200, raw("GET /projects HTTP/1.1", target = s).status)
            assertEquals("an authenticated request restarts the countdown", 100_000, s.idleRemainingMs())
        } finally {
            s.stop()
        }
    }

    @Test
    fun `server stops itself after the idle timeout and records that it was the timer`() {
        val now = AtomicLong(0)
        val s = newServer(idleTimeoutMs = 1_000, clockMs = { now.get() }).apply { start() }
        try {
            assertTrue(s.isAlive)
            assertFalse(s.stoppedForIdle)
            now.set(1_001)
            waitUntil { !s.isAlive }
            assertFalse("the idle timer must actually stop the listener", s.isAlive)
            assertTrue(s.stoppedForIdle)
            try {
                Socket().use { it.connect(java.net.InetSocketAddress(LOOPBACK, s.listeningPort), 3_000) }
                fail("a stopped server must refuse connections")
            } catch (expected: java.io.IOException) {
                // refused, as it should be
            }
        } finally {
            s.stop() // idempotent: the timer already stopped it
        }
    }

    @Test
    fun `authenticated use keeps the server up past the original deadline`() {
        val now = AtomicLong(0)
        val s = newServer(idleTimeoutMs = 1_000, clockMs = { now.get() }).apply { start() }
        try {
            now.set(900)
            assertEquals(200, raw("GET /projects HTTP/1.1", target = s).status) // deadline is now 1_900
            now.set(1_800)
            Thread.sleep(800) // several idle-timer ticks (250 ms period) against a not-yet-due deadline
            assertTrue("used 900 ms ago, must still be up", s.isAlive)
            assertFalse(s.stoppedForIdle)
        } finally {
            s.stop()
        }
        assertFalse("a user-initiated stop is not an idle stop", s.stoppedForIdle)
    }

    private fun waitUntil(timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(25)
    }

    companion object {
        const val TEST_PIN = "123456"
        const val LOOPBACK = "127.0.0.1"
    }
}
