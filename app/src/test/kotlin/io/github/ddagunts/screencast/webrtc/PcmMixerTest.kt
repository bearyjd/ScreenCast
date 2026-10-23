package io.github.ddagunts.screencast.webrtc

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Test

class PcmMixerTest {
    private fun stereo(order: ByteOrder, vararg s: Int): ByteBuffer {
        // Samples are always laid out little-endian regardless of the buffer's own order (as pcm16Peak reads them).
        val b = ByteBuffer.allocate(s.size * 2).order(order)
        s.forEach { b.put(it.toByte()); b.put((it shr 8).toByte()) }
        b.position(0)
        return b
    }
    private fun sample(b: ByteBuffer, i: Int): Int =
        ((b.get(i * 2).toInt() and 0xFF) or (b.get(i * 2 + 1).toInt() shl 8)).toShort().toInt()
    private fun g(v: Int) = (v * PcmMixer.GAIN_Q15) shr 15

    @Test fun `gain is minus 3 dB`() {
        assertEquals(-3.0103, 20 * Math.log10(PcmMixer.GAIN_Q15 / 32768.0), 0.01)
    }

    @Test fun `clamp saturates both signs`() {
        assertEquals(32767, PcmMixer.clamp16(40000))
        assertEquals(-32768, PcmMixer.clamp16(-40000))
        assertEquals(123, PcmMixer.clamp16(123))
    }

    @Test fun `mono mic is upmixed to both channels and summed with headroom gain`() {
        val b = stereo(ByteOrder.LITTLE_ENDIAN, 1000, -2000, 3000, 4000)
        val n = PcmMixer.mixMonoIntoStereo(b, 0, 8, shortArrayOf(500, -600), 2)
        assertEquals(2, n)
        assertEquals(g(1000) + g(500), sample(b, 0))
        assertEquals(g(-2000) + g(500), sample(b, 1))
        assertEquals(g(3000) + g(-600), sample(b, 2))
        assertEquals(g(4000) + g(-600), sample(b, 3))
    }

    @Test fun `positive overflow clips to max`() {
        val b = stereo(ByteOrder.LITTLE_ENDIAN, 32767, 32767)
        PcmMixer.mixMonoIntoStereo(b, 0, 4, shortArrayOf(32767), 1)
        assertEquals(32767, sample(b, 0)); assertEquals(32767, sample(b, 1))
    }

    @Test fun `negative overflow clips to min and does not wrap`() {
        val b = stereo(ByteOrder.LITTLE_ENDIAN, -32768, -32768)
        PcmMixer.mixMonoIntoStereo(b, 0, 4, shortArrayOf(-32768), 1)
        assertEquals(-32768, sample(b, 0)); assertEquals(-32768, sample(b, 1))
    }

    @Test fun `underrun leaves mic silent but keeps the same playback gain`() {
        val b = stereo(ByteOrder.LITTLE_ENDIAN, 1000, 1000, 2000, 2000, 3000, 3000)
        val n = PcmMixer.mixMonoIntoStereo(b, 0, 12, shortArrayOf(100), 1)
        assertEquals(3, n)
        assertEquals(g(1000) + g(100), sample(b, 0))
        assertEquals(g(2000), sample(b, 2)); assertEquals(g(2000), sample(b, 3))
        assertEquals(g(3000), sample(b, 4))
    }

    @Test fun `zero mic samples is playback at the same gain`() {
        val b = stereo(ByteOrder.LITTLE_ENDIAN, 8000, -8000)
        PcmMixer.mixMonoIntoStereo(b, 0, 4, ShortArray(0), 0)
        assertEquals(g(8000), sample(b, 0)); assertEquals(g(-8000), sample(b, 1))
    }

    @Test fun `trailing partial frame is untouched and not counted`() {
        val b = stereo(ByteOrder.LITTLE_ENDIAN, 1000, 1000, 777)
        val n = PcmMixer.mixMonoIntoStereo(b, 0, 6, shortArrayOf(1, 1), 2)
        assertEquals(1, n)
        assertEquals(777, sample(b, 2))
    }

    @Test fun `odd byte length ignores the stray byte`() {
        val b = stereo(ByteOrder.LITTLE_ENDIAN, 1000, 1000, 5)
        assertEquals(1, PcmMixer.mixMonoIntoStereo(b, 0, 7, shortArrayOf(0), 1))
        assertEquals(5, sample(b, 2))
    }

    @Test fun `respects position offset and does not touch bytes before it`() {
        val b = stereo(ByteOrder.LITTLE_ENDIAN, 111, 222, 1000, 1000)
        PcmMixer.mixMonoIntoStereo(b, 4, 4, shortArrayOf(0), 1)
        assertEquals(111, sample(b, 0)); assertEquals(222, sample(b, 1))
        assertEquals(g(1000), sample(b, 2))
    }

    @Test fun `length beyond buffer limit is clamped`() {
        val b = stereo(ByteOrder.LITTLE_ENDIAN, 1000, 1000)
        assertEquals(1, PcmMixer.mixMonoIntoStereo(b, 0, 400, shortArrayOf(0, 0, 0), 3))
    }

    @Test fun `result is independent of buffer byte order and leaves position alone`() {
        val le = stereo(ByteOrder.LITTLE_ENDIAN, 1000, -1000)
        val be = stereo(ByteOrder.BIG_ENDIAN, 1000, -1000)
        PcmMixer.mixMonoIntoStereo(le, 0, 4, shortArrayOf(300), 1)
        PcmMixer.mixMonoIntoStereo(be, 0, 4, shortArrayOf(300), 1)
        assertEquals(sample(le, 0), sample(be, 0)); assertEquals(sample(le, 1), sample(be, 1))
        assertEquals(0, be.position())
    }

    @Test fun `micCount larger than array is clamped`() {
        val b = stereo(ByteOrder.LITTLE_ENDIAN, 1000, 1000, 1000, 1000)
        assertEquals(2, PcmMixer.mixMonoIntoStereo(b, 0, 8, shortArrayOf(50), 99))
    }
}
