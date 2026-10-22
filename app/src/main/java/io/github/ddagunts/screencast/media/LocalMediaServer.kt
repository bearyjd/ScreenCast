// Fork modification by bearyjd: serves ONE user-chosen file to ONE chosen receiver over the LAN (token + peer gated).
package io.github.ddagunts.screencast.media

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.autohead.AutoHeadResponse
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import java.net.InetAddress
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Security model: bound to the Wi-Fi interface address only (caller-supplied), ephemeral port. A gate runs before
 * everything else (CORS, routing, permits) for EVERY method: the request must come from [allowedRemote] AND carry the
 * 128-bit path token, otherwise a body-less 404. Exactly one URL exists: GET/HEAD /m/{token}/media.<ext>.
 * Cross-origin reads are allowed only for [allowedOrigins] (echoed, never wildcard). Nothing identifying (file name,
 * bytes, peer address, token) is ever reported to [onResponse]/[onFirstOrigin].
 */
class LocalMediaServer(
    private val source: LocalMediaSource,
    private val contentType: String,
    val token: String,
    private val allowedRemote: InetAddress,
    private val bindHost: String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxConcurrent: Int = LocalMediaPolicy.MAX_CONCURRENT,
    private val allowedOrigins: Set<String> = LocalMediaPolicy.ALLOWED_ORIGINS,
    private val writeTimeoutMs: Long = LocalMediaPolicy.WRITE_TIMEOUT_MS,
    private val onFirstOrigin: (String) -> Unit = {},
    private val onResponse: (Int) -> Unit = {},
) {
    private val fileName = "media." + requireNotNull(LocalMediaPolicy.extensionFor(contentType)) { "unsupported type" }
    val path: String = "/m/$token/$fileName"
    private val permits = Semaphore(maxConcurrent)
    private val originReported = AtomicBoolean(false)
    @Volatile private var lastActivity = clock()
    private var engine: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    /** Starts listening and returns the ephemeral port actually bound. */
    suspend fun start(): Int {
        val server = embeddedServer(CIO, configure = {
            connector { port = 0; host = bindHost }
            connectionIdleTimeoutSeconds = 30
        }) {
            install(AutoHeadResponse)
            intercept(ApplicationCallPipeline.Plugins) {
                // Refusals are silent: no log callback per probe from non-receiver peers.
                if (!admitted(call)) { call.respond(HttpStatusCode.NotFound); finish(); return@intercept }
                call.response.header("X-Content-Type-Options", "nosniff")
                call.response.header(HttpHeaders.Vary, "Origin")
                val origin = call.request.headers[HttpHeaders.Origin]
                val allowed = origin != null && origin in allowedOrigins
                if (origin != null && originReported.compareAndSet(false, true)) onFirstOrigin(origin.filter { it in ' '..'~' }.take(100))
                if (allowed) addCorsHeaders(call, origin.orEmpty())
                if (call.request.httpMethod == HttpMethod.Options) {
                    if (allowed) { call.response.header(HttpHeaders.AccessControlAllowMethods, "GET, HEAD, OPTIONS")
                        call.response.header(HttpHeaders.AccessControlAllowHeaders, "Range")
                        call.response.header(HttpHeaders.AccessControlMaxAge, "600")
                        reply(call, HttpStatusCode.NoContent) } else reply(call, HttpStatusCode.NotFound)
                    finish()
                }
            }
            routing { get(path) { handle(call) } }
        }
        engine = server
        server.start(wait = false)
        return server.engine.resolvedConnectors().first().port
    }

    fun stop() {
        val running = engine; engine = null
        running?.stop(200, 1000)
    }

    fun activeRequests(): Int = maxConcurrent - permits.availablePermits()
    /** Milliseconds since the last authorised request started, ended or made write progress (in-flight alone does not count). */
    fun idleMillis(): Long = clock() - lastActivity

    private fun admitted(call: ApplicationCall): Boolean {
        if (!peerAllowed(call.request.local.remoteAddress)) return false
        val segments = call.request.path().split('/')
        return segments.size == 4 && segments[0].isEmpty() && segments[1] == "m" &&
            tokenMatches(segments[2], token) && segments[3] == fileName
    }

    private fun addCorsHeaders(call: ApplicationCall, origin: String) {
        call.response.header(HttpHeaders.AccessControlAllowOrigin, origin)
        call.response.header(HttpHeaders.AccessControlExposeHeaders, "Content-Range, Content-Length, Accept-Ranges, Content-Type")
    }

    private fun peerAllowed(remote: String): Boolean = try {
        // Literal only: a non-literal would trigger DNS, so refuse anything not already an IP.
        remote.isNotEmpty() && (remote[0].isDigit() || ':' in remote) && InetAddress.getByName(remote) == allowedRemote
    } catch (_: java.net.UnknownHostException) { false }

    private suspend fun reply(call: ApplicationCall, status: HttpStatusCode) {
        call.respond(status); onResponse(status.value)
    }

    private suspend fun handle(call: ApplicationCall) {
        if (!permits.tryAcquire()) {
            call.response.header(HttpHeaders.RetryAfter, "1")
            reply(call, HttpStatusCode.ServiceUnavailable); return
        }
        lastActivity = clock()
        try { respondMedia(call) } finally { lastActivity = clock(); permits.release() }
    }

    private suspend fun respondMedia(call: ApplicationCall) {
        val total = source.length
        call.response.header(HttpHeaders.AcceptRanges, "bytes")
        val range = ByteRangeParser.parse(call.request.headers[HttpHeaders.Range], total)
        if (range is ByteRange.Unsatisfiable) {
            call.response.header(HttpHeaders.ContentRange, "bytes */$total")
            reply(call, HttpStatusCode.RequestedRangeNotSatisfiable); return
        }
        val (start, end) = if (range is ByteRange.Partial) range.start to range.end else 0L to total - 1
        val status = if (range is ByteRange.Partial) HttpStatusCode.PartialContent else HttpStatusCode.OK
        if (range is ByteRange.Partial) call.response.header(HttpHeaders.ContentRange, "bytes $start-$end/$total")
        call.response.status(status)
        call.respond(object : OutgoingContent.WriteChannelContent() {
            override val contentLength = end - start + 1
            override val contentType = ContentType.parse(this@LocalMediaServer.contentType)
            override suspend fun writeTo(channel: ByteWriteChannel) =
                pumpRange(source, start, end, LocalMediaPolicy.BUFFER_BYTES, writeTimeoutMs, kotlinx.coroutines.Dispatchers.IO,
                    { lastActivity = clock() }) { b, o, n -> channel.writeFully(b, o, n); channel.flush() }
        })
        onResponse(status.value)
    }

    companion object {
        fun newToken(): String {
            val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }
        fun tokenMatches(supplied: String?, expected: String): Boolean {
            if (supplied == null) return false
            return MessageDigest.isEqual(supplied.toByteArray(Charsets.UTF_8), expected.toByteArray(Charsets.UTF_8))
        }
    }
}
