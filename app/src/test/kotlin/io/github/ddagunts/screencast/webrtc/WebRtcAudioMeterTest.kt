package io.github.ddagunts.screencast.webrtc

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebRtcAudioMeterTest {
    private fun pcm(vararg s: Int): ByteBuffer =
        ByteBuffer.allocate(s.size * 2).order(ByteOrder.LITTLE_ENDIAN).also { b -> s.forEach { b.putShort(it.toShort()) } }

    @Test fun silenceIsZero() = assertEquals(0, pcm16Peak(pcm(0, 0, 0), 0, 6))

    @Test fun peakIsAbsoluteMax() = assertEquals(1200, pcm16Peak(pcm(10, -1200, 300), 0, 6))

    @Test fun respectsOffsetAndLength() = assertEquals(5, pcm16Peak(pcm(9000, 5, -3), 2, 4))

    @Test fun minShortDoesNotOverflow() = assertEquals(32768, pcm16Peak(pcm(-32768), 0, 2))

    @Test fun ignoresTrailingOddByte() = assertEquals(7, pcm16Peak(pcm(7, 9000), 0, 3))

    private fun buf(): ByteBuffer = pcm(100, -2000).also { it.position(0) }
    private fun meter(logs: MutableList<String>, enabled: () -> Boolean, clock: () -> Long) =
        WebRtcAudioMeter(recordingState = { 3 }, nowNs = clock, log = { logs += it }, enabled = enabled)

    @Test fun logsAtMostEveryFiveSecondsPeakOnly() {
        var t = 1L
        val logs = mutableListOf<String>()
        val m = meter(logs, { true }) { t }
        repeat(120) { t += 100_000_000L; m.onBuffer(buf(), 2, 2, 48000, 4, 0) } // 12 s of 10 Hz buffers
        assertEquals(2, logs.size)
        assertTrue(logs.all { it.contains("peak=2000") })
    }

    @Test fun silentWhenDisabled() {
        var t = 1L
        val logs = mutableListOf<String>()
        val m = meter(logs, { false }) { t }
        repeat(100) { t += 100_000_000L; m.onBuffer(buf(), 2, 2, 48000, 4, 0) }
        assertTrue(logs.isEmpty())
    }
}
