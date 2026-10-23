package io.github.ddagunts.screencast.webrtc

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MicRingBufferTrimTest {
    private fun s(vararg v: Int) = ShortArray(v.size) { v[it].toShort() }

    @Test fun `trimTo drops the oldest samples and counts them`() {
        val r = MicRingBuffer(10)
        r.write(s(1, 2, 3, 4, 5, 6), 6)
        r.trimTo(2)
        assertEquals(2, r.available); assertEquals(4, r.droppedSamples)
        val d = ShortArray(2); r.read(d, 2); assertArrayEquals(s(5, 6), d)
    }

    @Test fun `trimTo is a no-op when already at or below target`() {
        val r = MicRingBuffer(10)
        r.write(s(1, 2), 2); r.trimTo(5)
        assertEquals(2, r.available); assertEquals(0, r.droppedSamples)
    }

    @Test fun `concurrent producer and consumer keep order and conserve samples`() {
        repeat(10) {
            val r = MicRingBuffer(480)
            // Strictly increasing values with no modulo wrap: drops only ever jump FORWARD, so any
            // x <= last is a real ordering bug. (A wrapping sequence made a long consumer stall look
            // like a backward step.) Short must hold every value, so total stays below Short.MAX_VALUE.
            val total = 30_000
            val err = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
            val producer = Thread {
                try { var v = 0; while (v < total) { val n = minOf(48, total - v); r.write(ShortArray(n) { (v + it).toShort() }, n); v += n } }
                catch (t: Throwable) { err.set(t) }
            }
            var read = 0L; var last = -1
            var bad = false
            val consumer = Thread {
                try {
                    val d = ShortArray(100)
                    var idle = 0
                    while (idle < 2000) {
                        val n = r.read(d, 100)
                        if (n == 0) { idle++; Thread.yield(); continue }
                        idle = 0
                        for (i in 0 until n) { val x = d[i].toInt(); if (x <= last) bad = true; last = x }
                        read += n
                    }
                } catch (t: Throwable) { err.set(t) }
            }
            producer.start(); consumer.start(); producer.join(10_000); consumer.join(10_000)
            assertEquals(null, err.get())
            assertTrue("order violated", !bad)
            assertEquals(total.toLong(), read + r.droppedSamples + r.available)
        }
    }
}
