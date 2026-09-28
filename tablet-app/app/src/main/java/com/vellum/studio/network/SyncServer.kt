package com.vellum.studio.network

import android.content.Context
import android.graphics.Bitmap
import com.vellum.studio.model.ProjectRepository
import com.vellum.studio.util.DiagnosticLog
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.time.Instant
import java.util.Collections
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Minimal LAN sync bridge to the PC companion app. Deliberately the "driver-free" half of PC
 * connectivity: see PC_CONNECTION.md at the repo root for what this does and doesn't cover.
 *
 * Plain HTTP, NOT encrypted: the PIN keeps other people on the same network from browsing the
 * canvases, but anyone who can sniff the network can read the traffic (and the PIN with it). TLS is
 * deliberately not part of this; the Connect screen says so rather than promising more.
 *
 * Every request must carry the session PIN (see [PairingGuard]) as an `X-Vellum-Pin` header, or a
 * `?pin=` query parameter for a browser polling the mirror; without it the answer is 401, and after
 * five wrong PINs it is 429 until the server is restarted. The check runs before routing, so a peer
 * without the PIN cannot tell a real route from a made-up one.
 *
 * Reachable only on [bindAddress] (the Wi-Fi/LAN address), never the wildcard, and it stops itself
 * after [idleTimeoutMs] with no authenticated request ([idleRemainingMs] drives the countdown on the
 * Connect screen; [stoppedForIdle] says why it went away).
 *
 *   GET  /                               -> liveness text
 *   GET  /projects                       -> JSON list of project summaries
 *   GET  /projects/{id}/export.zip       -> zipped project (metadata.json + layer PNGs), streamed
 *                                            chunked; {id} must be a real project UUID, else 404
 *   GET  /projects/{id}/thumbnail.png    -> cached preview PNG
 *   GET  /mirror/frame.jpg               -> current flattened canvas of whatever's open right now,
 *                                            downscaled to <=1024px — poll this for a crude "live" view.
 *
 * GET and HEAD only; anything else is 405. Unknown routes and unknown/malformed project ids are 404,
 * internal failures are a generic 500 (details go to [DiagnosticLog], never to the peer).
 */
class SyncServer(
    private val repository: ProjectRepository,
    private val appContext: Context,
    // Required, with no wildcard default: NanoHTTPD(port) alone binds every interface, which is how
    // this server used to be reachable over cellular/hotspot/VPN adapters as well as the Wi-Fi.
    val bindAddress: String,
    port: Int = DEFAULT_PORT,
    private val guard: PairingGuard = PairingGuard(),
    private val idleTimeoutMs: Long = DEFAULT_IDLE_TIMEOUT_MS,
    // Seam so a test can move time without sleeping; production is the monotonic clock.
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000 },
    // Seam so a test can make the export fail mid-stream (a real failure needs a file vanishing
    // between the two passes over it); production always uses the repository.
    private val zipWriter: (String, OutputStream) -> Boolean = repository::exportProjectZipTo,
) : NanoHTTPD(bindAddress, port) {

    /** The PIN the companion must present; regenerated with every new server instance (every Start). */
    val pin: String get() = guard.pin

    /** True once five wrong PINs have been seen: everything then answers 429 until a fresh Start. */
    val isLockedOut: Boolean get() = guard.isLocked

    /** Address of the last peer that presented the right PIN, for the "connected" line on the Connect screen. */
    @Volatile
    var lastClient: String? = null
        private set

    /** Set when the idle timer (not the user) shut the server down, so the screen can explain it. */
    @Volatile
    var stoppedForIdle: Boolean = false
        private set

    @Volatile
    private var lastActivityMs = clockMs()

    private val stopped = AtomicBoolean(false)

    // One daemon thread that only ever asks "has it been idle too long?". stop() uses shutdown(), not
    // shutdownNow(): stop() can run on this very thread, and an interrupt landing on it would poison
    // the DiagnosticLog file write that follows.
    private val idleWatch: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor(daemonThreads("SyncServer-idle"))

    // Zip writers run here, off the connection thread, feeding a pipe that the response streams from
    // (see exportZipResponse). Sized to MAX_CONNECTIONS so a writer can never sit queued behind
    // connection threads that are themselves waiting on it: there are never more zips in flight than
    // connections.
    private val zipExecutor: ExecutorService = Executors.newFixedThreadPool(MAX_CONNECTIONS, daemonThreads("SyncServer-zip"))

    init {
        setAsyncRunner(BoundedAsyncRunner())
    }

    override fun start(timeout: Int, daemon: Boolean) {
        super.start(timeout, daemon)
        // The idle clock starts when the server does, not when the object was built.
        touch()
        val period = (idleTimeoutMs / 4).coerceIn(25L, 5_000L)
        idleWatch.scheduleWithFixedDelay({ stopIfIdle() }, period, period, TimeUnit.MILLISECONDS)
        DiagnosticLog.log(appContext, TAG, "Sync server started on $bindAddress:$listeningPort")
    }

    override fun stop() {
        // Idempotent: the idle timer and the Connect screen's dispose can both call this.
        if (!stopped.compareAndSet(false, true)) return
        super.stop()
        zipExecutor.shutdownNow()
        idleWatch.shutdown()
        DiagnosticLog.log(appContext, TAG, "Sync server stopped")
    }

    /** Milliseconds until the idle timer stops the server; 0 once it is due. */
    fun idleRemainingMs(): Long = (lastActivityMs + idleTimeoutMs - clockMs()).coerceAtLeast(0L)

    private fun touch() {
        lastActivityMs = clockMs()
    }

    private fun stopIfIdle() {
        if (stopped.get() || idleRemainingMs() > 0) return
        stoppedForIdle = true
        DiagnosticLog.log(appContext, TAG, "Stopping sync server: idle for ${idleTimeoutMs / 1000}s")
        stop()
    }

    override fun serve(session: IHTTPSession): Response {
        val peer = session.headers["remote-addr"] ?: "unknown"
        // Auth is the first thing that happens, before the method check and routing: a peer without the
        // PIN must not learn anything, not even which paths exist (404 vs 405 vs 401).
        authorize(session, peer)?.let { return it }
        // GET/HEAD only: every route is a read, so refuse anything else up front instead of letting
        // a POST/PUT/DELETE fall through to a route that ignores the verb.
        if (session.method != Method.GET && session.method != Method.HEAD) {
            DiagnosticLog.log(appContext, TAG, "Rejected ${session.method} from $peer")
            return newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, MIME_PLAINTEXT, "Method not allowed")
                .apply { addHeader("Allow", "GET, HEAD") }
        }
        val uri = session.uri.trimEnd('/')
        return try {
            when {
                uri.isEmpty() -> textResponse("Vellum Studio sync server is running.")
                uri == "/projects" -> projectsListResponse()
                uri == "/mirror/frame.jpg" -> mirrorFrameResponse()
                uri.startsWith("/projects/") -> projectRoute(uri, session.method == Method.HEAD, peer)
                else -> notFound()
            }
        } catch (e: Exception) {
            // The real message can carry absolute paths, so it goes to the on-device log, never the peer.
            DiagnosticLog.log(appContext, TAG, "Request failed for ${printable(uri)} from $peer: ${e::class.java.simpleName}: ${e.message}")
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Internal error")
        }
    }

    /** Null when the request carries the right PIN (and counts as activity); otherwise the refusal to send. */
    private fun authorize(session: IHTTPSession, peer: String): Response? {
        val presented = session.headers["x-vellum-pin"] ?: session.parameters["pin"]?.firstOrNull()
        return when (guard.check(presented)) {
            PairingGuard.Verdict.OK -> {
                // Only a request that authenticated counts as use: unauthenticated traffic must not be
                // able to keep the server (and its exposure) alive past the idle timeout.
                touch()
                lastClient = peer
                null
            }
            PairingGuard.Verdict.MISSING -> unauthorized("PIN required")
            PairingGuard.Verdict.WRONG -> {
                // The presented value is never logged (a near-miss PIN is still most of a PIN). Logging
                // is bounded to the handful of failures before the lockout so a peer can't fill the log.
                val locked = if (guard.isLocked) "; locked out until sync is restarted" else ""
                DiagnosticLog.log(appContext, TAG, "Wrong PIN from $peer (${guard.failureCount}/${PairingGuard.MAX_FAILURES})$locked")
                unauthorized("Wrong PIN")
            }
            PairingGuard.Verdict.LOCKED -> newFixedLengthResponse(
                Response.Status.TOO_MANY_REQUESTS,
                MIME_PLAINTEXT,
                "Too many wrong PINs. Stop and start Wi-Fi sync on the tablet for a new PIN.",
            )
        }
    }

    private fun unauthorized(message: String) = newFixedLengthResponse(Response.Status.UNAUTHORIZED, MIME_PLAINTEXT, message)
        .apply { addHeader("WWW-Authenticate", "VellumPin") }

    private fun notFound() = newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")

    /** Peer-supplied text for the log: control chars (a decoded CRLF) would forge log lines. */
    private fun printable(s: String) = s.take(96).map { if (it.isISOControl()) '?' else it }.joinToString("")

    /**
     * `/projects/{id}/export.zip` and `/projects/{id}/thumbnail.png`, matched by exact path segments
     * (not removePrefix/removeSuffix, which is what let `/projects/../export.zip` produce id `..`).
     * NanoHTTPD 2.3.1 percent-decodes the URI but does not normalize dot segments, so `%2e%2e` and a
     * raw `..` both arrive here as literal `..`; anything that isn't a real, existing project id gets
     * the same 404 as an unknown route, so a probe can't tell "bad id" from "no such project".
     */
    private fun projectRoute(uri: String, headOnly: Boolean, peer: String): Response {
        val parts = uri.split('/') // ["", "projects", id, leaf]
        if (parts.size != 4 || (parts[3] != "export.zip" && parts[3] != "thumbnail.png")) return notFound()
        val id = parts[2]
        val dir = repository.resolveProjectDir(id)
        if (dir == null) {
            DiagnosticLog.log(appContext, TAG, "Rejected project id from $peer: ${printable(id)}")
            return notFound()
        }
        return if (parts[3] == "export.zip") exportZipResponse(id, headOnly) else thumbnailResponse(dir, headOnly)
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

    /**
     * Streams the zip through a pipe instead of building a temp file first: a worker writes the zip
     * into the pipe while the chunked response reads it out, so headers go out immediately (the PC
     * client's 10 s timeout is on headers) and nothing is left in cacheDir. Chunked rather than
     * fixed-length because the size isn't known up front.
     *
     * HEAD gets a fixed-length empty response, not the chunked one: NanoHTTPD 2.3.1 only emits
     * `Transfer-Encoding: chunked` for non-HEAD requests, and for HEAD a chunked response (length -1)
     * falls through to printing the raw `Content-Length: -1`, which a strict client (.NET
     * HttpClient, i.e. the PC companion) rejects as malformed. The real length is unknowable without
     * building the zip, so `Content-Length: 0` -- valid, and no zip work -- is the honest answer.
     */
    private fun exportZipResponse(id: String, headOnly: Boolean): Response {
        val response = if (headOnly) {
            newFixedLengthResponse(Response.Status.OK, "application/zip", "")
        } else {
            newChunkedResponse(Response.Status.OK, "application/zip", startZipPipe(id))
        }
        // id is UUID-validated, so it is safe to put in a header.
        response.addHeader("Content-Disposition", "attachment; filename=\"$id.zip\"")
        return response
    }

    private fun startZipPipe(id: String): InputStream {
        val pipe = ZipPipe(onRead = ::touch)
        zipExecutor.execute {
            try {
                // The writer gets a sink whose close() is a no-op: ZipOutputStream.use{} in the
                // writer closes its underlying stream even when it is unwinding from an exception
                // (close() -> finish() writes a VALID central directory for the entries so far, then
                // closes the sink). With the real sink that close reached the reader BEFORE the catch
                // below could set `failure`, so the reader saw a clean EOF and NanoHTTPD sent the
                // chunk terminator -- the PC got HTTP 200 and a well-formed zip silently missing the
                // layers after the failure. Only this finally closes the real sink, and only after
                // `failure` is recorded, so EOF-with-failure is always observable.
                val ok = zipWriter(id, NonClosingOutputStream(pipe.sink))
                // Only false if the project vanished between resolveProjectDir and here.
                if (!ok) pipe.failure = IOException("Project disappeared")
            } catch (t: Throwable) {
                // Headers are already on the wire; flag the pipe so the reader errors out and
                // NanoHTTPD drops the connection without a clean chunk terminator, instead of the
                // client receiving a truncated zip that looks complete.
                pipe.failure = t
                DiagnosticLog.log(appContext, TAG, "Zip export of $id failed: ${t::class.java.simpleName}: ${t.message}")
            } finally {
                runCatching { pipe.sink.close() }
            }
        }
        return pipe
    }

    private fun thumbnailResponse(dir: File, headOnly: Boolean): Response {
        val file = File(dir, "thumbnail.png")
        if (!file.exists()) return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "No thumbnail")
        val body: InputStream = if (headOnly) ByteArrayInputStream(ByteArray(0)) else FileInputStream(file)
        return newFixedLengthResponse(Response.Status.OK, "image/png", body, file.length())
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

    /**
     * The read side of the export pipe. [failure] is set by the writer BEFORE it closes the sink, so a
     * clean end-of-stream with a failure recorded is turned into an IOException here -- a plain
     * PipedOutputStream.close() would otherwise look like a normal, complete response.
     */
    private class NonClosingOutputStream(private val delegate: OutputStream) : OutputStream() {
        override fun write(b: Int) = delegate.write(b)

        override fun write(b: ByteArray, off: Int, len: Int) = delegate.write(b, off, len)

        override fun flush() = delegate.flush()

        // Flush what the zip trailer wrote, but leave the real sink to the executor's finally.
        override fun close() = delegate.flush()
    }

    private class ZipPipe(private val onRead: () -> Unit) : InputStream() {
        private val source = PipedInputStream(PIPE_BYTES)
        val sink = PipedOutputStream(source)

        @Volatile
        var failure: Throwable? = null

        // Each read is the PC actually pulling bytes, so a download that outlasts the idle timeout keeps
        // the session alive instead of being cut off mid-file.
        override fun read(): Int = source.read().also { onRead(); if (it < 0) checkFailure() }

        override fun read(b: ByteArray, off: Int, len: Int): Int = source.read(b, off, len).also { onRead(); if (it < 0) checkFailure() }

        override fun available(): Int = source.available()

        // Closing the read end (client disconnect) makes the writer's next write throw, so an
        // abandoned download stops the zip work instead of finishing it into the void.
        override fun close() = source.close()

        private fun checkFailure() {
            failure?.let { throw IOException("Zip export aborted", it) }
        }
    }

    /**
     * NanoHTTPD's default runner spawns an unbounded thread per connection, so any peer on the LAN
     * could exhaust the process by opening sockets. This caps concurrent connection handlers at
     * [MAX_CONNECTIONS] with a short queue; past that the connection is closed immediately (the
     * client just retries) rather than piling up threads.
     */
    private class BoundedAsyncRunner : AsyncRunner {
        private val running = Collections.synchronizedSet(HashSet<ClientHandler>())
        private val executor = ThreadPoolExecutor(
            MAX_CONNECTIONS, MAX_CONNECTIONS, 30, TimeUnit.SECONDS,
            LinkedBlockingQueue(CONNECTION_QUEUE),
            daemonThreads("SyncServer-conn"),
        ).apply { allowCoreThreadTimeOut(true) }

        override fun exec(code: ClientHandler) {
            running.add(code)
            try {
                executor.execute {
                    try {
                        code.run()
                    } finally {
                        running.remove(code)
                    }
                }
            } catch (e: RejectedExecutionException) {
                running.remove(code)
                code.close()
            }
        }

        override fun closed(clientHandler: ClientHandler) {
            running.remove(clientHandler)
        }

        override fun closeAll() {
            synchronized(running) { running.toList() }.forEach { it.close() }
            executor.shutdownNow()
        }
    }

    companion object {
        const val DEFAULT_PORT = 8642

        /** Ten minutes: long enough to browse and pull a few canvases, short enough not to be left open all day. */
        const val DEFAULT_IDLE_TIMEOUT_MS = 10 * 60 * 1000L
        private const val TAG = "SyncServer"
        private const val MAX_CONNECTIONS = 8
        private const val CONNECTION_QUEUE = 16
        private const val PIPE_BYTES = 64 * 1024

        private fun daemonThreads(prefix: String): ThreadFactory {
            val n = AtomicInteger()
            return ThreadFactory { r -> Thread(r, "$prefix-${n.incrementAndGet()}").apply { isDaemon = true } }
        }
    }
}
