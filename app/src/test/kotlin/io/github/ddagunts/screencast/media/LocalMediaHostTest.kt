// Fork modification by bearyjd: lifecycle (fd close, idempotence, ids, races, idle and lifetime bounds) of the local-media host.
package io.github.ddagunts.screencast.media

import java.net.InetAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class LocalMediaHostTest {
    private val receiver: InetAddress = InetAddress.getByName("127.0.0.1")
    private val clock = FakeClock(0)
    private var hook: suspend () -> Unit = {}
    private val host = LocalMediaHost(clock, idleTimeoutMs = 1_000, maxLifetimeMs = 10_000,
        cleanupDispatcher = Dispatchers.Unconfined, beforeRegister = { hook() })
    @After fun tearDown() { host.stop() }

    private fun begin(source: LocalMediaSource, type: String = "video/mp4") =
        runBlocking { host.begin(source, type, receiver, "127.0.0.1") }
    private fun port(url: String) = url.substringAfterLast(':').substringBefore('/').toInt()
    private fun pathOf(url: String) = "/" + url.substringAfter("//").substringAfter('/')

    @Test fun beginReturnsTokenisedLanUrlAndMarksActive() {
        val start = begin(FakeSource(ByteArray(10)))
        assertTrue(start.url, Regex("http://127\\.0\\.0\\.1:\\d+/m/[A-Za-z0-9_-]{22}/media\\.mp4").matches(start.url))
        assertTrue(host.active.value); assertEquals(start.id, host.currentId.value)
    }
    @Test fun servedUrlReturnsTheBytes() {
        val bytes = ByteArray(5000) { it.toByte() }
        val url = begin(FakeSource(bytes)).url
        assertArrayEquals(bytes, rawHttp(port(url), "GET", pathOf(url)).body)
    }
    @Test fun stopClosesSourceExactlyOnceAndIsIdempotent() {
        val s = FakeSource(ByteArray(10)); begin(s)
        host.stop(); host.stop()
        assertEquals(1, s.closes.get()); assertFalse(host.active.value); assertNull(host.currentId.value)
    }
    @Test fun stopWithoutSessionIsHarmless() { host.stop(); assertFalse(host.active.value) }
    @Test fun beginningAgainClosesPreviousSourceAndStopsItsServer() {
        val a = FakeSource(ByteArray(10)); val b = FakeSource(ByteArray(10))
        val first = begin(a); begin(b)
        assertEquals(1, a.closes.get()); assertEquals(0, b.closes.get())
        assertTrue(host.active.value)
        assertThrows(java.io.IOException::class.java) { rawHttp(port(first.url), "GET", pathOf(first.url)) }
    }
    @Test fun stopByIdOnlyStopsThatSession() {
        val a = FakeSource(ByteArray(10)); val b = FakeSource(ByteArray(10))
        val first = begin(a); val second = begin(b)
        assertFalse(host.stop(first.id)); assertEquals(0, b.closes.get()); assertTrue(host.active.value)
        assertTrue(host.stop(second.id)); assertEquals(1, b.closes.get()); assertFalse(host.active.value)
        assertFalse(host.stop(second.id))
        assertEquals(1, b.closes.get())
    }
    @Test fun idsAreUnique() { val a = begin(FakeSource(ByteArray(1))); val b = begin(FakeSource(ByteArray(1))); assertNotEquals(a.id, b.id) }
    @Test fun idleTimeoutClosesSource() {
        val s = FakeSource(ByteArray(10)); begin(s)
        clock.now = 999; assertFalse(host.reapIfExpired()); assertEquals(0, s.closes.get())
        clock.now = 1_000; assertTrue(host.reapIfExpired())
        assertEquals(1, s.closes.get()); assertFalse(host.active.value)
        assertFalse(host.reapIfExpired())
    }
    @Test fun requestsDeferIdleExpiry() {
        val url = begin(FakeSource(ByteArray(10))).url
        clock.now = 900
        rawHttp(port(url), "GET", pathOf(url))
        clock.now = 1_800; assertFalse(host.reapIfExpired())
        clock.now = 1_900; assertTrue(host.reapIfExpired())
    }
    @Test fun absoluteLifetimeEndsEvenWhenBusy() {
        val s = FakeSource(ByteArray(10)); val url = begin(s).url
        for (t in 500L..10_000L step 500L) {
            clock.now = t
            rawHttp(port(url), "GET", pathOf(url), mapOf("Range" to "bytes=0-0"))
            if (t < 10_000) assertFalse("t=$t", host.reapIfExpired())
        }
        assertTrue(host.reapIfExpired()); assertEquals(1, s.closes.get())
    }
    @Test fun failedStartClosesSourceAndStaysInactive() {
        val s = FakeSource(ByteArray(10))
        assertThrows(Exception::class.java) { runBlocking { host.begin(s, "video/mp4", receiver, "203.0.113.77") } }
        assertEquals(1, s.closes.get()); assertFalse(host.active.value)
    }
    @Test fun unsupportedTypeIsRefusedAndClosed() {
        val s = FakeSource(ByteArray(10))
        assertThrows(IllegalArgumentException::class.java) { begin(s, "text/html") }
        assertEquals(1, s.closes.get())
    }
    @Test fun stopRacingBeginAbortsTheBeginAndLeavesNothingServing() {
        val s = FakeSource(ByteArray(10))
        hook = { host.stop() }
        assertThrows(IllegalStateException::class.java) { begin(s) }
        assertEquals(1, s.closes.get()); assertFalse(host.active.value); assertNull(host.currentId.value)
    }
    @Test fun stopByIdOfAnotherSessionDuringBeginDoesNotAbortIt() {
        val s = FakeSource(ByteArray(10))
        hook = { host.stop(-1) }
        begin(s)
        assertTrue(host.active.value); assertEquals(0, s.closes.get())
    }
    @Test fun sourceIsClosedAfterTheServerHasStopped() {
        val gate = java.util.concurrent.CountDownLatch(1)
        val s = FakeSource(ByteArray(10), gate)
        val url = begin(s).url
        val t = kotlin.concurrent.thread { runCatching { rawHttp(port(url), "GET", pathOf(url)) } }
        Thread.sleep(300)
        host.stop()
        gate.countDown(); t.join(5000)
        assertEquals(1, s.closes.get())
        assertThrows(java.io.IOException::class.java) { rawHttp(port(url), "GET", pathOf(url)) }
    }
    @Test fun stopOnIoDispatcherReturnsBeforeCleanupFinishesButStillClosesOnce() {
        val async = LocalMediaHost(clock, cleanupDispatcher = Dispatchers.IO)
        val s = FakeSource(ByteArray(10))
        runBlocking { async.begin(s, "video/mp4", receiver, "127.0.0.1") }
        async.stop(); async.stop()
        val deadline = System.currentTimeMillis() + 5000
        while (s.closes.get() == 0 && System.currentTimeMillis() < deadline) Thread.sleep(10)
        Thread.sleep(100)
        assertEquals(1, s.closes.get())
    }
}
