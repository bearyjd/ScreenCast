package io.github.ddagunts.screencast.webrtc

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class MicRingBufferTest {
    private fun s(vararg v: Int) = ShortArray(v.size) { v[it].toShort() }

    @Test fun `fifo order`() {
        val r = MicRingBuffer(8)
        r.write(s(1, 2, 3), 3)
        val d = ShortArray(3)
        assertEquals(3, r.read(d, 3)); assertArrayEquals(s(1, 2, 3), d)
        assertEquals(0, r.available)
    }

    @Test fun `underrun returns fewer samples and never blocks`() {
        val r = MicRingBuffer(8)
        r.write(s(1, 2), 2)
        val d = ShortArray(5)
        assertEquals(2, r.read(d, 5))
        assertEquals(0, r.read(d, 5))
    }

    @Test fun `overflow drops oldest and counts`() {
        val r = MicRingBuffer(4)
        r.write(s(1, 2, 3), 3)
        r.write(s(4, 5, 6), 3)
        assertEquals(2, r.droppedSamples)
        val d = ShortArray(4)
        assertEquals(4, r.read(d, 4)); assertArrayEquals(s(3, 4, 5, 6), d)
    }

    @Test fun `single write larger than capacity keeps the newest`() {
        val r = MicRingBuffer(3)
        r.write(s(1, 2, 3, 4, 5), 5)
        val d = ShortArray(3)
        assertEquals(3, r.read(d, 3)); assertArrayEquals(s(3, 4, 5), d)
        assertEquals(2, r.droppedSamples)
    }

    @Test fun `wraps around`() {
        val r = MicRingBuffer(4)
        val d = ShortArray(3)
        repeat(5) { i -> r.write(s(i, i + 10, i + 20), 3); assertEquals(3, r.read(d, 3)); assertArrayEquals(s(i, i + 10, i + 20), d) }
        assertEquals(0, r.droppedSamples)
    }

    @Test fun `clear empties`() {
        val r = MicRingBuffer(4)
        r.write(s(1, 2), 2); r.clear()
        assertEquals(0, r.available)
    }
}
