package io.github.ddagunts.screencast.webrtc

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.webrtc.audio.JavaAudioDeviceModule

private class FakeMic(
    override val initialized: Boolean = true,
    private val startOk: Boolean = true,
    private val chunks: MutableList<ShortArray> = mutableListOf(),
) : MicSource {
    var started = 0; var stopped = 0; var released = 0
    override fun start(): Boolean { started++; return startOk }
    override fun read(dst: ShortArray, size: Int): Int {
        if (chunks.isEmpty()) return -1
        val c = chunks.removeAt(0); c.copyInto(dst); return c.size
    }
    override fun stop() { stopped++ }
    override fun release() { released++ }
}

class MicMixControllerTest {
    private val logs = mutableListOf<String>()
    private var created = 0
    private fun ctl(mic: MicSource?, granted: Boolean = true, throwOnCreate: Boolean = false) = MicMixController(
        micPermissionGranted = { granted },
        factory = { created++; if (throwOnCreate) throw SecurityException("denied") else mic },
        log = { logs += it },
        spawn = { Thread {} }, // never run a real loop; tests call pumpOnce()
    )
    private val cfgOn = WebRtcSessionConfig(VideoPreset.HD_30, 1, audioRequested = true, recordAudioGranted = true, sdkInt = 34, micMix = true)

    @Test fun `default config creates no mic controller and never touches the factory`() {
        val c = createMicMixController(cfgOn.copy(micMix = false), { true }, { created++; FakeMic() }, { })
        assertNull(c); assertEquals(0, created)
    }

    @Test fun `micMix without active audio creates no controller`() {
        assertNull(createMicMixController(cfgOn.copy(audioRequested = false), { true }, { created++; FakeMic() }, { }))
        assertNull(createMicMixController(cfgOn.copy(recordAudioGranted = false), { true }, { created++; FakeMic() }, { }))
        assertNull(createMicMixController(cfgOn.copy(sdkInt = 28), { true }, { created++; FakeMic() }, { }))
        assertEquals(0, created)
    }

    @Test fun `enabled config creates a controller that is idle until started`() {
        val c = createMicMixController(cfgOn, { true }, { created++; FakeMic() }, { })
        assertNotNull(c); assertEquals(MicMixStatus.IDLE, c!!.status); assertEquals(0, created)
    }

    @Test fun `denied permission degrades and never creates a record`() {
        val c = ctl(FakeMic(), granted = false)
        assertEquals(MicMixStatus.DENIED, c.start())
        assertEquals(0, created); assertFalse(c.isActive)
        assertTrue(logs.any { it.contains("denied", true) })
    }

    @Test fun `SecurityException from record creation degrades to FAILED`() {
        val c = ctl(null, throwOnCreate = true)
        assertEquals(MicMixStatus.FAILED, c.start()); assertFalse(c.isActive)
        assertTrue(logs.isNotEmpty())
    }

    @Test fun `uninitialized record is released and degrades`() {
        val m = FakeMic(initialized = false)
        val c = ctl(m)
        assertEquals(MicMixStatus.FAILED, c.start())
        assertEquals(1, m.released); assertEquals(0, m.started)
    }

    @Test fun `start failure releases and degrades`() {
        val m = FakeMic(startOk = false)
        val c = ctl(m)
        assertEquals(MicMixStatus.FAILED, c.start())
        assertEquals(1, m.released)
    }

    @Test fun `null record from factory degrades`() {
        assertEquals(MicMixStatus.FAILED, ctl(null).start())
    }

    @Test fun `active pump feeds ring and read error degrades and releases`() {
        val m = FakeMic(chunks = mutableListOf(shortArrayOf(1, 2, 3)))
        val c = ctl(m)
        assertEquals(MicMixStatus.ACTIVE, c.start()); assertTrue(c.isActive)
        assertTrue(c.pumpOnce())
        val d = ShortArray(3)
        assertEquals(3, c.fillMic(d, 3)); assertEquals(2, d[1].toInt())
        assertFalse(c.pumpOnce()) // read -> -1
        assertEquals(MicMixStatus.FAILED, c.status); assertFalse(c.isActive)
        assertEquals(1, m.released)
    }

    @Test fun `stop is idempotent releases once and clears ring`() {
        val m = FakeMic(chunks = mutableListOf(shortArrayOf(9, 9)))
        val c = ctl(m); c.start(); c.pumpOnce()
        c.stop(); c.stop()
        assertEquals(1, m.released); assertEquals(MicMixStatus.STOPPED, c.status)
        assertEquals(0, c.fillMic(ShortArray(2), 2))
        assertFalse(c.pumpOnce())
    }

    @Test fun `stop before start never creates a record and start after stop is refused`() {
        val c = ctl(FakeMic()); c.stop()
        assertEquals(MicMixStatus.STOPPED, c.start()); assertEquals(0, created)
    }

    // ---- buffer callback ----
    private class Spy : JavaAudioDeviceModule.AudioBufferCallback {
        var calls = 0
        override fun onBuffer(b: ByteBuffer, f: Int, ch: Int, sr: Int, n: Int, t: Long): Long { calls++; return 7L }
    }
    private fun pcm(vararg s: Int) = ByteBuffer.allocate(s.size * 2).order(ByteOrder.LITTLE_ENDIAN).also { b -> s.forEach { b.putShort(it.toShort()) }; b.position(0) }
    private fun at(b: ByteBuffer, i: Int) = b.getShort(i * 2).toInt()

    private fun activeCtl(vararg mic: Int): MicMixController {
        val m = FakeMic(chunks = mutableListOf(ShortArray(mic.size) { mic[it].toShort() }))
        return ctl(m).also { it.start(); it.pumpOnce() }
    }

    @Test fun `callback delegates, returns delegate result and mixes when active`() {
        val spy = Spy(); val c = activeCtl(1000)
        val cb = MicMixBufferCallback(spy, c, { true }, { })
        val b = pcm(2000, 2000)
        assertEquals(7L, cb.onBuffer(b, 2, 2, 48000, 4, 0))
        assertEquals(1, spy.calls)
        assertEquals(PcmMixer.clamp16(((2000 + 1000) * PcmMixer.GAIN_Q15) shr 15), at(b, 0))
    }

    @Test fun `callback does not touch the buffer when not active or disabled or wrong format`() {
        val idle = ctl(FakeMic())
        fun run(c: MicMixController, en: Boolean, fmt: Int = 2, ch: Int = 2, sr: Int = 48000): Int {
            val b = pcm(2000, 2000)
            MicMixBufferCallback(Spy(), c, { en }, { }).onBuffer(b, fmt, ch, sr, 4, 0)
            return at(b, 0)
        }
        assertEquals(2000, run(idle, true))
        assertEquals(2000, run(activeCtl(500), false))
        assertEquals(2000, run(activeCtl(500), true, fmt = 4))
        assertEquals(2000, run(activeCtl(500), true, ch = 1))
        assertEquals(2000, run(activeCtl(500), true, sr = 16000))
    }
}
