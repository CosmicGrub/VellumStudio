package com.vellum.studio.network

import android.graphics.Bitmap
import com.vellum.studio.model.ProjectRepository
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.time.Instant

/**
 * Minimal LAN sync bridge to the PC companion app — no pairing, no auth, same network only.
 * Deliberately the "driver-free" half of PC connectivity: see PC_CONNECTION.md at the repo root
 * for what this does and doesn't cover.
 *
 *   GET  /projects                       -> JSON list of project summaries
 *   GET  /projects/{id}/export.zip       -> zipped project (metadata.json + layer PNGs)
 *   GET  /projects/{id}/thumbnail.png    -> cached preview PNG
 *   GET  /mirror/frame.jpg               -> current flattened canvas of whatever's open right now,
 *                                            downscaled to <=1024px — poll this for a crude "live" view.
 */
class SyncServer(
    private val repository: ProjectRepository,
    port: Int = DEFAULT_PORT,
) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri.trimEnd('/')
        return try {
            when {
                uri.isEmpty() -> textResponse("Vellum Studio sync server is running.")
                uri == "/projects" -> projectsListResponse()
                uri.startsWith("/projects/") && uri.endsWith("/export.zip") -> exportZipResponse(uri)
                uri.startsWith("/projects/") && uri.endsWith("/thumbnail.png") -> thumbnailResponse(uri)
                uri == "/mirror/frame.jpg" -> mirrorFrameResponse()
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
            }
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Error: ${e.message}")
        }
    }

    private fun textResponse(s: String) = newFixedLengthResponse(Response.Status.OK, "text/plain", s)

    private fun projectsListResponse(): Response = runBlocking {
        val projects = repository.listProjects()
        val arr = buildJsonArray {
            for (p in projects) {
                add(
                    buildJsonObject {
                        put("id", p.id)
                        put("name", p.name)
                        // ISO-8601, not the raw epoch-millis Long this app uses internally: the PC
                        // companion's ProjectSummary.UpdatedAt is a DateTimeOffset, and
                        // System.Text.Json only deserializes that from a string by default - handing
                        // it a bare JSON number throws on every single project, every time, which
                        // silently broke the whole "browse projects from the PC" feature this was
                        // built for. Instant.toString() produces exactly the ISO-8601 format .NET's
                        // default DateTimeOffset parsing expects.
                        put("updatedAt", Instant.ofEpochMilli(p.updatedAt).toString())
                        put("width", p.widthPx)
                        put("height", p.heightPx)
                        put("thumbnailUrl", "/projects/${p.id}/thumbnail.png")
                    },
                )
            }
        }
        newFixedLengthResponse(Response.Status.OK, "application/json", arr.toString())
    }

    private fun exportZipResponse(uri: String): Response {
        val id = uri.removePrefix("/projects/").removeSuffix("/export.zip")
        val zip = runBlocking { repository.exportProjectZip(id) }
        val response = newFixedLengthResponse(Response.Status.OK, "application/zip", FileInputStream(zip), zip.length())
        response.addHeader("Content-Disposition", "attachment; filename=\"$id.zip\"")
        return response
    }

    private fun thumbnailResponse(uri: String): Response {
        val id = uri.removePrefix("/projects/").removeSuffix("/thumbnail.png")
        val file = repository.projectDir(id).resolve("thumbnail.png")
        if (!file.exists()) return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "No thumbnail")
        return newFixedLengthResponse(Response.Status.OK, "image/png", FileInputStream(file), file.length())
    }

    private fun mirrorFrameResponse(): Response {
        val engine = LiveCanvasBridge.activeEngine
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Nothing open right now")
        val flat = engine.flatten()
        val scale = 1024f / maxOf(flat.width, flat.height)
        val scaled = if (scale < 1f) {
            Bitmap.createScaledBitmap(flat, (flat.width * scale).toInt().coerceAtLeast(1), (flat.height * scale).toInt().coerceAtLeast(1), true)
        } else {
            flat
        }
        val bytes = ByteArrayOutputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, 82, out)
            out.toByteArray()
        }
        if (scaled !== flat) scaled.recycle()
        flat.recycle()
        return newFixedLengthResponse(Response.Status.OK, "image/jpeg", bytes.inputStream(), bytes.size.toLong())
    }

    companion object {
        const val DEFAULT_PORT = 8642
    }
}
