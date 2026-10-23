package io.github.ddagunts.screencast.webrtc

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
    private fun pb(v: Int) = (v * PcmMixer.GAIN_Q15) shr 15           // playback at -3 dB (gate fully open)
    private fun mic(v: Int) = (v * PcmMixer.MIC_GAIN_Q15) shr 15      // mic at -9 dB (gate fully open)
    // Instant gate: opens on the first loud sample, long hold, instant release.
    private fun fastGate(hold: Int = 1000) = MicGate(attackSamples = 1, holdSamples = hold, releaseSamples = 1, openThreshold = 100)

    @Test fun `gains are minus 3 dB playback and minus 9 dB mic`() {
        assertEquals(-3.0103, 20 * Math.log10(PcmMixer.GAIN_Q15 / 32768.0), 0.01)
        assertEquals(-9.0, 20 * Math.log10(PcmMixer.MIC_GAIN_Q15 / 32768.0), 0.01)
    }

    @Test fun `thresholds are sane constants`() {
        assertTrue(MIC_GATE_OPEN_THRESHOLD in 200..6000)
        assertTrue(MIC_GATE_ATTACK_SAMPLES in 1..960)
        assertTrue(MIC_GATE_HOLD_SAMPLES >= 4800)
        assertTrue(MIC_GATE_RELEASE_SAMPLES >= 2400)
    }

    @Test fun `clamp saturates both signs`() {
        assertEquals(32767, PcmMixer.clamp16(40000))
        assertEquals(-32768, PcmMixer.clamp16(-40000))
        assertEquals(123, PcmMixer.clamp16(123))
    }

    @Test fun `gate closed on low level input leaves playback byte for byte unchanged`() {
        val b = stereo(ByteOrder.LITTLE_ENDIAN, 1000, -2000, 3000, 4000)
        val before = b.array().copyOf()
        val n = PcmMixer.mixMonoIntoStereo(b, 0, 8, shortArrayOf(50, -60), 2, fastGate())
        assertEquals(2, n)
        assertTrue(before.contentEquals(b.array()))
    }

    @Test fun `gate opens on loud input then mic is upmixed at minus 9 dB and playback at minus 3 dB`() {
        val b = stereo(ByteOrder.LITTLE_ENDIAN, 1000, -2000, 3000, 4000)
        PcmMixer.mixMonoIntoStereo(b, 0, 8, shortArrayOf(5000, -6000), 2, fastGate())
        assertEquals(pb(1000) + mic(5000), sample(b, 0))
        assertEquals(pb(-2000) + mic(5000), sample(b, 1))
        assertEquals(pb(3000) + mic(-6000), sample(b, 2))
        assertEquals(pb(4000) + mic(-6000), sample(b, 3))
    }

    @Test fun `gate gain ramps on attack with no step larger than one slice`() {
        val gate = MicGate(attackSamples = 4, holdSamples = 100, releaseSamples = 4, openThreshold = 100)
        val gains = IntArray(6) { gate.step(5000) }
        assertEquals(listOf(8192, 16384, 24576, 32768, 32768, 32768), gains.toList())
    }

    @Test fun `gate holds then releases with a ramp not a cliff`() {
        val gate = MicGate(attackSamples = 1, holdSamples = 3, releaseSamples = 4, openThreshold = 100)
        assertEquals(32768, gate.step(5000))
        repeat(3) { assertEquals(32768, gate.step(0)) } // hold
        val rel = IntArray(5) { gate.step(0) }
        assertEquals(listOf(24576, 16384, 8192, 0, 0), rel.toList())
        assertFalse(gate.isOpen)
    }

    @Test fun `mixed output rises smoothly on attack with no click`() {
        val gate = MicGate(attackSamples = 8, holdSamples = 100, releaseSamples = 8, openThreshold = 100)
        val n = 16
        val b = stereo(ByteOrder.LITTLE_ENDIAN, *IntArray(n * 2) { 0 })
        PcmMixer.mixMonoIntoStereo(b, 0, n * 4, ShortArray(n) { 20000 }, n, gate)
        val slice = mic(20000) / 8 + 2
        assertTrue(sample(b, 0) in 1..slice)
        for (f in 1 until n) {
            val d = sample(b, f * 2) - sample(b, (f - 1) * 2)
            assertTrue("frame $f step $d", d in 0..slice)
        }
        assertEquals(mic(20000), sample(b, (n - 1) * 2))
    }

    @Test fun `playback is only attenuated while the gate is open`() {
        val closed = stereo(ByteOrder.LITTLE_ENDIAN, 8000, 8000)
        PcmMixer.mixMonoIntoStereo(closed, 0, 4, shortArrayOf(0), 1, fastGate())
        assertEquals(8000, sample(closed, 0))
        val open = stereo(ByteOrder.LITTLE_ENDIAN, 8000, 8000)
        PcmMixer.mixMonoIntoStereo(open, 0, 4, shortArrayOf(5000), 1, fastGate())
        assertEquals(pb(8000) + mic(5000), sample(open, 0))
    }

    @Test fun `positive overflow clips to max`() {
        val b = stereo(ByteOrder.LITTLE_ENDIAN, 32767, 32767)
        PcmMixer.mixMonoIntoStereo(b, 0, 4, shortArrayOf(32767), 1, fastGate())
        assertEquals(PcmMixer.clamp16(pb(32767) + mic(32767)), sample(b, 0))
        assertEquals(sample(b, 0), sample(b, 1))
    }

    @Test fun `negative overflow clips to min and does not wrap`() {
        val b = stereo(ByteOrder.LITTLE_ENDIAN, -32768, -32768)
        PcmMixer.mixMonoIntoStereo(b, 0, 4, shortArrayOf(-32768), 1, fastGate())
        assertTrue(sample(b, 0) < -20000)
        assertEquals(PcmMixer.clamp16(pb(-32768) + mic(-32768)), sample(b, 0))
    }

    @Test fun `underrun is a silent mic and the gate then releases back to unity playback`() {
        val b = stereo(ByteOrder.LITTLE_ENDIAN, 1000, 1000, 2000, 2000, 3000, 3000)
        val gate = MicGate(attackSamples = 1, holdSamples = 1, releaseSamples = 1, openThreshold = 100)
        val n = PcmMixer.mixMonoIntoStereo(b, 0, 12, shortArrayOf(5000), 1, gate)
        assertEquals(3, n)
        assertEquals(pb(1000) + mic(5000), sample(b, 0))
        assertEquals(pb(2000), sample(b, 2)) // hold sample: still open, mic silent
        assertEquals(3000, sample(b, 4))     // released: byte-identical playback
    }

    @Test fun `block peak reports the loudest mic contribution`() {
        val gate = fastGate()
        val b = stereo(ByteOrder.LITTLE_ENDIAN, 0, 0, 0, 0)
        PcmMixer.mixMonoIntoStereo(b, 0, 8, shortArrayOf(5000, -9000), 2, gate)
        assertEquals(Math.abs(mic(-9000)), gate.takeBlockPeak())
        assertEquals(0, gate.takeBlockPeak())
    }

    @Test fun `trailing partial frame is untouched and not counted`() {
        val b = stereo(ByteOrder.LITTLE_ENDIAN, 1000, 1000, 777)
        val n = PcmMixer.mixMonoIntoStereo(b, 0, 6, shortArrayOf(5000, 5000), 2, fastGate())
        assertEquals(1, n)
        assertEquals(777, sample(b, 2))
    }

    @Test fun `odd byte length ignores the stray byte`() {
        val b = stereo(ByteOrder.LITTLE_ENDIAN, 1000, 1000, 5)
        assertEquals(1, PcmMixer.mixMonoIntoStereo(b, 0, 7, shortArrayOf(0), 1, fastGate()))
        assertEquals(5, sample(b, 2))
    }

    @Test fun `respects base offset and does not touch bytes before it`() {
        val b = stereo(ByteOrder.LITTLE_ENDIAN, 111, 222, 1000, 1000)
        PcmMixer.mixMonoIntoStereo(b, 4, 4, shortArrayOf(5000), 1, fastGate())
        assertEquals(111, sample(b, 0)); assertEquals(222, sample(b, 1))
        assertEquals(pb(1000) + mic(5000), sample(b, 2))
    }

    @Test fun `length beyond buffer limit is clamped`() {
        val b = stereo(ByteOrder.LITTLE_ENDIAN, 1000, 1000)
        assertEquals(1, PcmMixer.mixMonoIntoStereo(b, 0, 400, shortArrayOf(0, 0, 0), 3, fastGate()))
    }

    @Test fun `result is independent of buffer byte order and leaves position alone`() {
        val le = stereo(ByteOrder.LITTLE_ENDIAN, 1000, -1000)
        val be = stereo(ByteOrder.BIG_ENDIAN, 1000, -1000)
        PcmMixer.mixMonoIntoStereo(le, 0, 4, shortArrayOf(3000), 1, fastGate())
        PcmMixer.mixMonoIntoStereo(be, 0, 4, shortArrayOf(3000), 1, fastGate())
        assertEquals(sample(le, 0), sample(be, 0)); assertEquals(sample(le, 1), sample(be, 1))
        assertEquals(0, be.position())
    }

    @Test fun `micCount larger than array is clamped`() {
        val b = stereo(ByteOrder.LITTLE_ENDIAN, 1000, 1000, 1000, 1000)
        assertEquals(2, PcmMixer.mixMonoIntoStereo(b, 0, 8, shortArrayOf(50), 99, fastGate()))
    }

    // ---- feedback safeguard ----
    @Test fun `feedback guard trips only after the level stays high continuously`() {
        val g = FeedbackGuard(limitSamples = 1000, peakThreshold = 8000)
        assertFalse(g.onBlock(9000, 400)); assertFalse(g.onBlock(9000, 400))
        assertTrue(g.onBlock(9000, 400))
        assertTrue(g.tripped)
    }

    @Test fun `a quiet block resets the feedback timer`() {
        val g = FeedbackGuard(limitSamples = 1000, peakThreshold = 8000)
        g.onBlock(9000, 600); g.onBlock(100, 480); g.onBlock(9000, 600)
        assertFalse(g.tripped)
    }

    @Test fun `feedback guard is sticky for the session`() {
        val g = FeedbackGuard(limitSamples = 100, peakThreshold = 8000)
        assertTrue(g.onBlock(9000, 200)); assertTrue(g.onBlock(0, 10))
    }

    @Test fun `feedback constants mean seconds of sustained loud mic`() {
        assertTrue(FEEDBACK_LIMIT_SAMPLES >= 48_000 * 2)
        assertTrue(FEEDBACK_PEAK_THRESHOLD in 4000..30000)
    }

    @Test fun `gate watch reports once when the gate never opens`() {
        val w = GateOpenWatch(limitFrames = 100)
        assertFalse(w.onBlock(false, 99))
        assertTrue(w.onBlock(false, 1))
        assertFalse(w.onBlock(false, 500))
    }

    @Test fun `gate watch stays silent once the gate has opened`() {
        val w = GateOpenWatch(limitFrames = 100)
        assertFalse(w.onBlock(false, 50)); assertFalse(w.onBlock(true, 10))
        assertFalse(w.onBlock(false, 10_000))
    }
}
