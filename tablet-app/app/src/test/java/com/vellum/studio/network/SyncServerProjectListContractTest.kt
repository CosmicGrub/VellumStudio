package com.vellum.studio.network

import android.app.Application
import com.vellum.studio.VellumApp
import com.vellum.studio.model.ProjectRepository
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.OffsetDateTime
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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

/**
 * The wire contract of `GET /projects`, the one response the PC companion parses
 * (pc-companion/VellumCompanion/Models/ProjectSummary.cs).
 *
 * Why this exists: the field the PC declares as a `DateTimeOffset` was once emitted as a bare
 * epoch-millis number. System.Text.Json only reads a DateTimeOffset from a JSON STRING, so that threw
 * on every project, every time, and silently broke the whole "browse projects from the PC" feature --
 * with no test on either side to notice (see the note above `updatedAt` in
 * SyncServer.projectsListResponse). The existing SyncServerTest only checks that the id appears in
 * the body. This pins the shape and the JSON types the PC's deserializer depends on.
 *
 * Scope: the tablet half of the contract. The PC half (an xUnit project parsing the same fixture) does
 * not exist and is a separate, cross-project item; until it does, the checks here are the type and
 * format rules a .NET DateTimeOffset / int / string property needs, stated explicitly.
 *
 * VellumApp + NATIVE graphics for the same reasons as SyncServerTest (createProject persists a real
 * thumbnail through the app singleton).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = VellumApp::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SyncServerProjectListContractTest {

    private val app: Application = RuntimeEnvironment.getApplication()
    private val repository = ProjectRepository(app)
    private lateinit var server: SyncServer

    @Before
    fun startServer() {
        server = SyncServer(repository, app, LOOPBACK, port = 0, guard = PairingGuard(TEST_PIN))
        server.start()
    }

    @After
    fun stopServer() {
        server.stop()
    }

    private fun get(path: String): HttpURLConnection =
        (URL("http://$LOOPBACK:${server.listeningPort}$path").openConnection() as HttpURLConnection).apply {
            setRequestProperty("X-Vellum-Pin", TEST_PIN)
        }

    private fun listBody(): String {
        val conn = get("/projects")
        assertEquals(200, conn.responseCode)
        assertTrue("content type: ${conn.contentType}", conn.contentType.startsWith("application/json"))
        return conn.inputStream.readBytes().toString(Charsets.UTF_8)
    }

    private fun createProject(name: String, w: Int, h: Int): String = runBlocking {
        val (meta, engine) = repository.createProject(name, w, h)
        engine.layers.forEach { it.bitmap.recycle() }
        meta.id
    }

    @Test fun `an empty library is an empty JSON array not an error or an object`() {
        val parsed = Json.parseToJsonElement(listBody())
        assertTrue("expected a JSON array, got $parsed", parsed is JsonArray)
        assertTrue(parsed.jsonArray.isEmpty())
    }

    @Test fun `each project is an object with exactly the fields the PC companion reads and the JSON types it needs`() {
        val id = createProject("Contract sketch", 96, 64)

        val array = Json.parseToJsonElement(listBody()).jsonArray
        assertEquals(1, array.size)
        val obj: JsonObject = array[0].jsonObject

        // No field silently renamed, dropped or added (the PC binds these by [JsonPropertyName]).
        assertEquals(setOf("id", "name", "updatedAt", "width", "height", "thumbnailUrl"), obj.keys)

        val idField = obj.getValue("id") as JsonPrimitive
        assertTrue("id must be a JSON string", idField.isString)
        assertEquals(id, idField.content)
        val nameField = obj.getValue("name") as JsonPrimitive
        assertTrue("name must be a JSON string", nameField.isString)
        assertEquals("Contract sketch", nameField.content)

        // Canvas size: JSON numbers (an int in .NET), never strings.
        val width = obj.getValue("width") as JsonPrimitive
        val height = obj.getValue("height") as JsonPrimitive
        assertFalse("width must be a JSON number", width.isString)
        assertFalse("height must be a JSON number", height.isString)
        assertEquals(96, width.int)
        assertEquals(64, height.int)

        val thumb = obj.getValue("thumbnailUrl") as JsonPrimitive
        assertTrue(thumb.isString)
        assertEquals("/projects/$id/thumbnail.png", thumb.content)
        // ...and the URL it advertises really serves the image.
        val thumbConn = get(thumb.content)
        assertEquals(200, thumbConn.responseCode)
        assertTrue(thumbConn.inputStream.readBytes().isNotEmpty())
    }

    @Test fun `updatedAt is an ISO-8601 UTC string that round-trips to the project's real modification time`() {
        createProject("Timestamp", 64, 64)
        val summary = runBlocking { repository.listProjects() }.single()

        val obj = Json.parseToJsonElement(listBody()).jsonArray.single().jsonObject
        val updatedAt = obj.getValue("updatedAt") as JsonPrimitive

        // THE regression: a bare number here is what broke the PC's DateTimeOffset property.
        assertTrue("updatedAt must be a JSON string, was ${updatedAt.content} (isString=${updatedAt.isString})", updatedAt.isString)

        // The shape .NET's default DateTimeOffset parsing accepts: date, 'T', time, optional fraction,
        // and an explicit offset ('Z' -- no offset would be read as local time and skew every project).
        val iso = Regex("""^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d{1,9})?Z$""")
        assertTrue("not ISO-8601 UTC: ${updatedAt.content}", iso.matches(updatedAt.content))

        // It is the SAME instant the repository reports, not merely well-formed.
        assertEquals(summary.updatedAt, Instant.parse(updatedAt.content).toEpochMilli())
        assertEquals(summary.updatedAt, OffsetDateTime.parse(updatedAt.content).toInstant().toEpochMilli())
    }

    @Test fun `several projects are listed newest first`() {
        val older = createProject("Older", 64, 64)
        Thread.sleep(20) // updatedAt is epoch millis; keep the two creations distinguishable
        val newer = createProject("Newer", 64, 64)

        val ids = Json.parseToJsonElement(listBody()).jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content }
        assertEquals(listOf(newer, older), ids)
    }

    private companion object {
        const val TEST_PIN = "123456"
        const val LOOPBACK = "127.0.0.1"
    }
}
