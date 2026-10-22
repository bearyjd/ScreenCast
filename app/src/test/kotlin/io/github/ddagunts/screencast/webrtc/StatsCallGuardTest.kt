// Fork modification by bearyjd: close()/stats-call mutual exclusion tests.
package io.github.ddagunts.screencast.webrtc

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StatsCallGuardTest {
    @Test fun `run executes before close and returns the value`() {
        val g = StatsCallGuard()
        assertEquals(5, g.run { 5 })
        assertTrue(g.closeAndAwait(100))
    }

    @Test fun `run never starts after close began`() {
        val g = StatsCallGuard()
        assertTrue(g.closeAndAwait(100))
        var started = false
        assertNull(g.run { started = true; 1 })
        assertFalse(started)
    }

    @Test fun `close waits for in-flight call before returning`() {
        val g = StatsCallGuard()
        val inCall = CountDownLatch(1)
        val release = CountDownLatch(1)
        val callDone = AtomicBoolean(false)
        val t = Thread { g.run { inCall.countDown(); release.await(); callDone.set(true) } }
        t.start()
        assertTrue(inCall.await(2, TimeUnit.SECONDS))

        val closeReturned = AtomicBoolean(false)
        val closeResult = AtomicBoolean(false)
        val closer = Thread { closeResult.set(g.closeAndAwait(5_000)); closeReturned.set(true) }
        closer.start()
        Thread.sleep(200)
        assertFalse("close must wait for the in-flight call", closeReturned.get())
        // New calls are refused while close is waiting.
        var started = false
        assertNull(g.run { started = true; 1 })
        assertFalse(started)

        release.countDown()
        closer.join(2_000)
        assertTrue(closeReturned.get())
        assertTrue(callDone.get())
        assertTrue(closeResult.get())
    }

    @Test fun `close gives up after the bound and reports false`() {
        val g = StatsCallGuard()
        val inCall = CountDownLatch(1)
        val release = CountDownLatch(1)
        val t = Thread { g.run { inCall.countDown(); release.await() } }
        t.start()
        assertTrue(inCall.await(2, TimeUnit.SECONDS))
        val start = System.nanoTime()
        assertFalse(g.closeAndAwait(150))
        assertTrue((System.nanoTime() - start) / 1_000_000 < 2_000)
        release.countDown()
        t.join(2_000)
    }

    @Test fun `exception in block releases the guard`() {
        val g = StatsCallGuard()
        try { g.run { error("x") } } catch (_: IllegalStateException) {}
        assertTrue(g.closeAndAwait(100))
    }
}
