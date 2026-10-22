// Fork modification by bearyjd: loopback tests for the single-file, token- and peer-gated media server.
package io.github.ddagunts.screencast.media

import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class LocalMediaServerTest {
    private val loopback: InetAddress = InetAddress.getByName("127.0.0.1")
    private val media = tempMediaFile(300_000)
    private val file = media.first
    private val data = media.second
    private val servers = mutableListOf<LocalMediaServer>()
    private val seenOrigins = java.util.Collections.synchronizedList(mutableListOf<String>())
    private val statuses = java.util.Collections.synchronizedList(mutableListOf<Int>())

    private fun serve(source: LocalMediaSource = fileSource(file), allowed: InetAddress = loopback,
                      type: String = "video/mp4", max: Int = 4, clock: () -> Long = System::currentTimeMillis,
                      origins: Set<String> = LocalMediaPolicy.ALLOWED_ORIGINS): Pair<LocalMediaServer, Int> {
        val server = LocalMediaServer(source, type, "tok", allowed, "127.0.0.1", clock, max, allowedOrigins = origins,
            onFirstOrigin = { seenOrigins += it }, onResponse = { statuses += it })
        servers += server
        return server to runBlocking { server.start() }
    }
    @After fun tearDown() { servers.forEach { it.stop() } }

    @Test fun servesExactlyTheChosenFileByteForByteWithMediaHeaders() {
        val (server, port) = serve()
        val r = rawHttp(port, "GET", server.path, mapOf("Origin" to "https://www.gstatic.com"))
        assertEquals(200, r.status)
        assertArrayEquals(data, r.body)
        assertEquals("video/mp4", r.headers["content-type"])
        assertEquals("300000", r.headers["content-length"])
        assertEquals("bytes", r.headers["accept-ranges"])
        assertEquals("https://www.gstatic.com", r.headers["access-control-allow-origin"])
        val exposed = r.headers["access-control-expose-headers"].orEmpty().lowercase()
        for (h in listOf("content-range", "content-length", "accept-ranges")) assertTrue(h, h in exposed)
    }
    @Test fun pathUsesTypeSpecificFileName() {
        assertTrue(serve(type = "audio/mpeg").first.path.endsWith("/media.mp3"))
        assertTrue(serve(type = "audio/mp4").first.path.endsWith("/media.m4a"))
        assertTrue(serve().first.path.startsWith("/m/tok/"))
    }
    @Test fun headReturnsHeadersWithoutBody() {
        val (server, port) = serve()
        val r = rawHttp(port, "HEAD", server.path)
        assertEquals(200, r.status)
        assertEquals("300000", r.headers["content-length"])
        assertEquals("bytes", r.headers["accept-ranges"])
        assertEquals(0, r.body.size)
    }
    @Test fun headWithRangeReportsPartialLengthWithoutBody() {
        val (server, port) = serve()
        val r = rawHttp(port, "HEAD", server.path, mapOf("Range" to "bytes=10-19"))
        assertEquals(206, r.status)
        assertEquals("10", r.headers["content-length"])
        assertEquals(0, r.body.size)
    }
    @Test fun singleByteRange() {
        val (server, port) = serve()
        val r = rawHttp(port, "GET", server.path, mapOf("Range" to "bytes=0-0"))
        assertEquals(206, r.status)
        assertEquals("bytes 0-0/300000", r.headers["content-range"])
        assertArrayEquals(data.copyOfRange(0, 1), r.body)
    }
    @Test fun openEndedAndSuffixRanges() {
        val (server, port) = serve()
        val open = rawHttp(port, "GET", server.path, mapOf("Range" to "bytes=299990-"))
        assertEquals(206, open.status)
        assertEquals("bytes 299990-299999/300000", open.headers["content-range"])
        assertArrayEquals(data.copyOfRange(299_990, 300_000), open.body)
        val suffix = rawHttp(port, "GET", server.path, mapOf("Range" to "bytes=-1000"))
        assertEquals("bytes 299000-299999/300000", suffix.headers["content-range"])
        assertArrayEquals(data.copyOfRange(299_000, 300_000), suffix.body)
    }
    @Test fun middleRangeCrossingBufferBoundariesIsExact() {
        val (server, port) = serve()
        val r = rawHttp(port, "GET", server.path, mapOf("Range" to "bytes=1000-250000"))
        assertEquals(206, r.status)
        assertArrayEquals(data.copyOfRange(1000, 250_001), r.body)
    }
    @Test fun endBeyondEofClampsAndStartBeyondEofIs416() {
        val (server, port) = serve()
        val clamp = rawHttp(port, "GET", server.path, mapOf("Range" to "bytes=299999-999999"))
        assertEquals(206, clamp.status)
        assertEquals(1, clamp.body.size)
        val bad = rawHttp(port, "GET", server.path, mapOf("Range" to "bytes=300000-"))
        assertEquals(416, bad.status)
        assertEquals("bytes */300000", bad.headers["content-range"])
        assertEquals(416, rawHttp(port, "GET", server.path, mapOf("Range" to "bytes=zz")).status)
    }
    @Test fun multiRangeIsAnsweredWithFullBody() {
        val (server, port) = serve()
        val r = rawHttp(port, "GET", server.path, mapOf("Range" to "bytes=0-1,10-11"))
        assertEquals(200, r.status)
        assertArrayEquals(data, r.body)
    }
    @Test fun wrongTokenWrongNameAndOtherRoutesAre404() {
        val (server, port) = serve()
        val good = server.path
        for (path in listOf(good.replace("/tok/", "/nope/"), good.replace("media.mp4", "other.mp4"), "$good/", "/", "/m/", "/m/tok",
            "/health", "/c/tok/media.mp4", "/../etc/passwd", "/m/tok/../tok/media.mp4x")) {
            val r = rawHttp(port, "GET", path)
            assertEquals(path, 404, r.status)
            assertFalse(path, String(r.body).contains("tok"))
        }
    }
    @Test fun otherMethodsAreNotServed() {
        val (server, port) = serve()
        for (m in listOf("POST", "PUT", "DELETE")) {
            val r = rawHttp(port, m, server.path)
            assertTrue(m, r.status == 404 || r.status == 405)
            assertFalse(m, String(r.body).contains("media"))
        }
    }
    @Test fun requestsFromAnyOtherPeerAre404EvenWithCorrectToken() {
        val (server, port) = serve(allowed = InetAddress.getByName("10.9.9.9"))
        val r = rawHttp(port, "GET", server.path, mapOf("Range" to "bytes=0-9"))
        assertEquals(404, r.status)
        assertEquals(0, r.headers["content-range"]?.length ?: 0)
        assertFalse(String(r.body).contains("media"))
        assertEquals(404, rawHttp(port, "HEAD", server.path).status)
    }
    @Test fun preflightFromAllowListedOriginAllowsRange() {
        val (server, port) = serve()
        val r = rawHttp(port, "OPTIONS", server.path, mapOf("Origin" to "https://www.gstatic.com",
            "Access-Control-Request-Method" to "GET", "Access-Control-Request-Headers" to "range"))
        assertTrue(r.status in 200..204)
        assertEquals("https://www.gstatic.com", r.headers["access-control-allow-origin"])
        assertTrue(r.headers["access-control-allow-headers"].orEmpty().lowercase().contains("range"))
    }
    @Test fun noCorsHeadersForUnknownOrAbsentOrigin() {
        val (server, port) = serve()
        for (h in listOf(mapOf("Origin" to "https://evil.example"), mapOf("Origin" to "https://www.google.com"), mapOf("Origin" to "null"), mapOf("Origin" to "https://www.gstatic.com.evil.example"),
            mapOf("Origin" to "http://www.gstatic.com"), emptyMap())) {
            val r = rawHttp(port, "GET", server.path, h + mapOf("Range" to "bytes=0-9"))
            assertEquals(206, r.status)
            assertTrue(h.toString(), r.headers.keys.none { it.startsWith("access-control-") })
        }
        val pre = rawHttp(port, "OPTIONS", server.path, mapOf("Origin" to "https://evil.example", "Access-Control-Request-Method" to "GET"))
        assertEquals(404, pre.status)
        assertTrue(pre.headers.keys.none { it.startsWith("access-control-") })
    }
    @Test fun corsExposesRangeHeadersOnPartialResponsesForAllowedOrigin() {
        val (server, port) = serve()
        val r = rawHttp(port, "GET", server.path, mapOf("Origin" to "https://www.gstatic.com", "Range" to "bytes=0-9"))
        val exposed = r.headers["access-control-expose-headers"].orEmpty().lowercase()
        for (h in listOf("content-range", "content-length", "accept-ranges")) assertTrue(h, h in exposed)
    }
    @Test fun nonReceiverPeerGetsNoCorsEvenForPreflight() {
        val (server, port) = serve(allowed = InetAddress.getByName("10.9.9.9"))
        val r = rawHttp(port, "OPTIONS", server.path, mapOf("Origin" to "https://www.gstatic.com", "Access-Control-Request-Method" to "GET"))
        assertEquals(404, r.status)
        assertTrue(r.headers.keys.none { it.startsWith("access-control-") })
        assertTrue(seenOrigins.isEmpty())
    }
    @Test fun firstOriginIsReportedOncePerSessionAndSanitised() {
        val (server, port) = serve()
        rawHttp(port, "GET", server.path)
        assertTrue(seenOrigins.isEmpty())
        rawHttp(port, "GET", server.path, mapOf("Origin" to "https://www.gstatic.com", "Range" to "bytes=0-0"))
        rawHttp(port, "GET", server.path, mapOf("Origin" to "https://other.example", "Range" to "bytes=0-0"))
        assertEquals(listOf("https://www.gstatic.com"), seenOrigins.toList())
    }
    @Test fun pathWithWrongTokenNeverReportsOriginOrHoldsPermit() {
        val (server, port) = serve(max = 1)
        repeat(20) { assertEquals(404, rawHttp(port, "GET", "/m/bad/media.mp4", mapOf("Origin" to "https://www.gstatic.com")).status) }
        assertEquals(0, server.activeRequests()); assertTrue(seenOrigins.isEmpty())
    }
    @Test fun readBufferIsBoundedAndFileIsNeverFullyBuffered() {
        val source = FakeSource(ByteArray(2_000_000) { it.toByte() })
        val (server, port) = serve(source)
        val r = rawHttp(port, "GET", server.path)
        assertEquals(2_000_000, r.body.size)
        assertTrue("chunk ${source.maxChunk}", source.maxChunk in 1..LocalMediaPolicy.BUFFER_BYTES)
    }
    @Test fun concurrencyIsBoundedWith503() {
        val gate = CountDownLatch(1)
        val (server, port) = serve(FakeSource(ByteArray(1000), gate), max = 1)
        var first: RawResponse? = null
        val t = thread { first = rawHttp(port, "GET", server.path) }
        val deadline = System.currentTimeMillis() + 5000
        while (server.activeRequests() == 0 && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals(1, server.activeRequests())
        assertEquals(503, rawHttp(port, "GET", server.path).status)
        gate.countDown(); t.join(5000)
        assertEquals(200, first?.status)
        assertEquals(0, server.activeRequests())
    }
    @Test fun activityTrackingFeedsIdleClock() {
        val clock = FakeClock(1_000)
        val (server, port) = serve(clock = clock)
        clock.now = 5_000
        assertEquals(4_000, server.idleMillis())
        rawHttp(port, "GET", server.path, mapOf("Range" to "bytes=0-0"))
        assertEquals(0, server.idleMillis())
        clock.now = 9_000
        assertEquals(4_000, server.idleMillis())
    }
    @Test fun wrongPeerOrTokenDoesNotCountAsActivity() {
        val clock = FakeClock(0)
        val (server, port) = serve(clock = clock)
        clock.now = 7_000
        rawHttp(port, "GET", "/m/bad/media.mp4")
        assertEquals(7_000, server.idleMillis())
    }
    @Test fun statusCodesAreReportedForLogging() {
        val (server, port) = serve()
        rawHttp(port, "GET", server.path); rawHttp(port, "GET", server.path, mapOf("Range" to "bytes=999999-"))
        assertEquals(listOf(200, 416), statuses.toList())
    }
    @Test fun stopClosesListener() {
        val (server, port) = serve()
        server.stop(); server.stop()
        assertThrows(java.io.IOException::class.java) { rawHttp(port, "GET", server.path) }
    }
    @Test fun stuckInFlightRequestStillAgesTowardIdle() {
        val gate = CountDownLatch(1)
        val clock = FakeClock(1_000)
        val (server, port) = serve(FakeSource(ByteArray(1000), gate), clock = clock)
        val t = thread { runCatching { rawHttp(port, "GET", server.path) } }
        val deadline = System.currentTimeMillis() + 5000
        while (server.activeRequests() == 0 && System.currentTimeMillis() < deadline) Thread.sleep(10)
        clock.now = 9_000
        assertEquals(8_000, server.idleMillis())
        gate.countDown(); t.join(5000)
    }
    @Test fun clientDisconnectReleasesPermit() {
        val (server, port) = serve(ZeroSource(2_000_000_000L), max = 1)
        java.net.Socket("127.0.0.1", port).use { sock ->
            sock.getOutputStream().write("GET ${server.path} HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
            sock.getOutputStream().flush()
            val buf = ByteArray(1024); assertTrue(sock.getInputStream().read(buf) > 0)
            sock.setSoLinger(true, 0)
        }
        val deadline = System.currentTimeMillis() + 10_000
        while (server.activeRequests() != 0 && System.currentTimeMillis() < deadline) Thread.sleep(25)
        assertEquals(0, server.activeRequests())
    }
    @Test fun sourceEndingEarlyTruncatesConnectionAndReleasesPermit() {
        val (server, port) = serve(ShortSource(1000, 500), max = 1)
        val r = runCatching { rawHttp(port, "GET", server.path) }.getOrNull()
        assertTrue(r == null || r.body.size < 1000)
        val deadline = System.currentTimeMillis() + 5000
        while (server.activeRequests() != 0 && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals(0, server.activeRequests())
    }
    @Test fun stopDuringInFlightRequestReturnsPromptly() {
        val gate = CountDownLatch(1)
        val (server, port) = serve(FakeSource(ByteArray(1000), gate))
        val t = thread { runCatching { rawHttp(port, "GET", server.path) } }
        val deadline = System.currentTimeMillis() + 5000
        while (server.activeRequests() == 0 && System.currentTimeMillis() < deadline) Thread.sleep(10)
        val started = System.currentTimeMillis()
        server.stop()
        assertTrue(System.currentTimeMillis() - started < 5000)
        gate.countDown(); t.join(5000)
    }
    @Test fun twoParallelRangeRequestsAreByteExact() {
        val (server, port) = serve()
        var a: RawResponse? = null; var b: RawResponse? = null
        val ta = thread { a = rawHttp(port, "GET", server.path, mapOf("Range" to "bytes=0-149999")) }
        val tb = thread { b = rawHttp(port, "GET", server.path, mapOf("Range" to "bytes=150000-")) }
        ta.join(10_000); tb.join(10_000)
        assertArrayEquals(data.copyOfRange(0, 150_000), a?.body)
        assertArrayEquals(data.copyOfRange(150_000, 300_000), b?.body)
    }
    @Test fun gateRefusalsProduceNoLogCallback() {
        val (server, port) = serve()
        rawHttp(port, "GET", "/m/bad/media.mp4"); rawHttp(port, "GET", "/"); rawHttp(port, "OPTIONS", "/m/bad/media.mp4", mapOf("Origin" to "https://www.gstatic.com"))
        val (other, otherPort) = serve(allowed = InetAddress.getByName("10.9.9.9"))
        rawHttp(otherPort, "GET", other.path)
        assertTrue(statuses.toList().toString(), statuses.isEmpty())
    }
    @Test fun everyAdmittedResponseCarriesNosniffAndVaryOrigin() {
        val (server, port) = serve()
        for (h in listOf(emptyMap(), mapOf("Origin" to "https://evil.example"), mapOf("Origin" to "https://www.gstatic.com"), mapOf("Range" to "bytes=999999-"))) {
            val r = rawHttp(port, "GET", server.path, h)
            assertEquals(h.toString(), "nosniff", r.headers["x-content-type-options"])
            assertEquals(h.toString(), "Origin", r.headers["vary"])
        }
        assertEquals("nosniff", rawHttp(port, "HEAD", server.path).headers["x-content-type-options"])
    }
}
