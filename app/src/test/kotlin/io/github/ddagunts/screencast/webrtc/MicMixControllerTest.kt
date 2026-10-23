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
    private val onRead: ((ShortArray) -> Int)? = null,
) : MicSource {
    @Volatile var started = 0; @Volatile var stopped = 0; @Volatile var released = 0
    override fun start(): Boolean { started++; return startOk }
    override fun read(dst: ShortArray, size: Int): Int {
        onRead?.let { return it(dst) }
        if (chunks.isEmpty()) return -1
        val c = chunks.removeAt(0); c.copyInto(dst); return c.size
    }
    @Volatile var stopHook: (() -> Unit)? = null
    override fun stop() { stopped++; stopHook?.invoke() }
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
        runAsync = { it.run() },
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
        val spy = Spy(); val c = activeCtl(5000)
        val cb = MicMixBufferCallback(spy, c, { true }, { }, gate = fastGate())
        val b = pcm(2000, 2000)
        assertEquals(7L, cb.onBuffer(b, 2, 2, 48000, 4, 0))
        assertEquals(1, spy.calls)
        assertEquals(((2000 * PcmMixer.GAIN_Q15) shr 15) + ((5000 * PcmMixer.MIC_GAIN_Q15) shr 15), at(b, 0))
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

    private fun fastGate() = MicGate(attackSamples = 1, holdSamples = 1000, releaseSamples = 1, openThreshold = 100)

    // ---- guard trip / callback stops the mic (A) ----
    @Test fun `guard trip stops and releases the mic`() {
        val m = FakeMic(chunks = mutableListOf(shortArrayOf(1)))
        val c = ctl(m); c.start()
        val pc = PlaybackAudioController(log = { })
        wireMicToGuard(pc, c)
        pc.guard.onError("boom")
        assertEquals(MicMixStatus.STOPPED, c.status); assertEquals(1, m.released)
    }

    @Test fun `every guard trip kind stops the mic`() {
        listOf<(PlaybackAudioController) -> Unit>(
            { it.guard.onInitError("x") }, { it.guard.onStartError("x") }, { it.guard.onRecordStop() },
            { it.guard.onRecordStart() }, // not our record: record is null
        ).forEach { trip ->
            val m = FakeMic(); val c = ctl(m); c.start()
            val pc = PlaybackAudioController(log = { }); wireMicToGuard(pc, c)
            trip(pc)
            assertEquals(MicMixStatus.STOPPED, c.status); assertEquals(1, m.released)
        }
    }

    @Test fun `orderly guard shutdown does not need the mic hook`() {
        val m = FakeMic(); val c = ctl(m); c.start()
        val pc = PlaybackAudioController(log = { }); wireMicToGuard(pc, c)
        pc.guard.shutdown()
        assertEquals(MicMixStatus.ACTIVE, c.status) // the peer stops the mic itself in close()
    }

    @Test fun `stopAsync is idempotent and falls back to a synchronous stop when it cannot spawn`() {
        val m = FakeMic(); val c = MicMixController({ true }, { m }, { }, spawn = { Thread {} }, runAsync = { throw OutOfMemoryError() })
        c.start(); c.stopAsync(); c.stopAsync()
        assertEquals(MicMixStatus.STOPPED, c.status); assertEquals(1, m.released)
    }

    @Test fun `callback stops the mic once the guard says disabled`() {
        val m = FakeMic(chunks = mutableListOf(shortArrayOf(5000)))
        val c = ctl(m); c.start(); c.pumpOnce()
        val b = pcm(2000, 2000)
        MicMixBufferCallback(Spy(), c, { false }, { }, gate = fastGate()).onBuffer(b, 2, 2, 48000, 4, 0)
        assertEquals(2000, at(b, 0)); assertEquals(MicMixStatus.STOPPED, c.status); assertEquals(1, m.released)
    }

    // ---- callback robustness (G, H) ----
    @Test fun `mixing uses base offset zero even when the buffer position is not zero`() {
        val c = activeCtl(5000)
        val b = pcm(2000, 2000, 777, 777); b.position(4)
        MicMixBufferCallback(Spy(), c, { true }, { }, gate = fastGate()).onBuffer(b, 2, 2, 48000, 4, 0)
        assertEquals(((2000 * PcmMixer.GAIN_Q15) shr 15) + ((5000 * PcmMixer.MIC_GAIN_Q15) shr 15), at(b, 0))
        assertEquals(777, at(b, 2)); assertEquals(4, b.position())
    }

    @Test fun `gate closed on quiet mic leaves playback untouched`() {
        val c = activeCtl(50)
        val b = pcm(2000, 2000)
        MicMixBufferCallback(Spy(), c, { true }, { }).onBuffer(b, 2, 2, 48000, 4, 0)
        assertEquals(2000, at(b, 0))
    }

    @Test fun `mixer error disables mixing logs once and stops the mic`() {
        val m = FakeMic(chunks = mutableListOf(shortArrayOf(5000), shortArrayOf(5000)))
        val c = ctl(m); c.start(); c.pumpOnce(); c.pumpOnce()
        val log = mutableListOf<String>()
        val cb = MicMixBufferCallback(Spy(), c, { true }, { log += it }, gate = fastGate())
        val ro = pcm(2000, 2000).asReadOnlyBuffer()
        cb.onBuffer(ro, 2, 2, 48000, 4, 0); cb.onBuffer(ro, 2, 2, 48000, 4, 0)
        assertEquals(1, log.count { it.contains("mixing disabled") })
        assertEquals(MicMixStatus.STOPPED, c.status); assertEquals(1, m.released)
        val b = pcm(2000, 2000)
        cb.onBuffer(b, 2, 2, 48000, 4, 0); assertEquals(2000, at(b, 0))
    }

    @Test fun `unexpected format logs once and stops the mic`() {
        val m = FakeMic(); val c = ctl(m); c.start()
        val log = mutableListOf<String>()
        val cb = MicMixBufferCallback(Spy(), c, { true }, { log += it })
        repeat(3) { cb.onBuffer(pcm(2000, 2000), 2, 1, 44100, 4, 0) }
        assertEquals(1, log.count { it.contains("format") })
        assertEquals(MicMixStatus.STOPPED, c.status); assertEquals(1, m.released)
    }

    @Test fun `sustained loud mic trips the feedback guard, logs, and stops the mic for good`() {
        val m = FakeMic(onRead = { d -> d[0] = 20000; 1 })
        val c = ctl(m); c.start()
        val log = mutableListOf<String>()
        val cb = MicMixBufferCallback(Spy(), c, { true }, { log += it }, gate = fastGate(), feedback = FeedbackGuard(limitSamples = 4, peakThreshold = 3000))
        repeat(6) { c.pumpOnce(); cb.onBuffer(pcm(0, 0, 0, 0), 2, 2, 48000, 8, 0) }
        assertEquals(1, log.count { it.contains("feedback") })
        assertEquals(MicMixStatus.STOPPED, c.status); assertEquals(1, m.released)
    }

    // ---- lifecycle (H) ----
    @Test fun `real reader thread blocks in read, stop unblocks it, joins and releases once`() {
        val gate = java.util.concurrent.CountDownLatch(1)
        val inRead = java.util.concurrent.CountDownLatch(1)
        val m = FakeMic(onRead = { inRead.countDown(); gate.await(); -1 })
        val c = MicMixController({ true }, { m }, { }) // real spawn
        assertEquals(MicMixStatus.ACTIVE, c.start())
        assertTrue(inRead.await(2, java.util.concurrent.TimeUnit.SECONDS))
        m.stopHook = { gate.countDown() }
        val stopper = Thread { c.stop() }
        stopper.start(); stopper.join(3000)
        assertFalse("stop must not hang", stopper.isAlive)
        assertEquals(1, m.released); assertEquals(MicMixStatus.STOPPED, c.status)
        assertTrue(Thread.getAllStackTraces().keys.none { it.name == "mic-mix" && it.isAlive })
    }

    @Test fun `100 consecutive empty reads degrade`() {
        val m = FakeMic(onRead = { 0 })
        val log = mutableListOf<String>()
        val c = MicMixController({ true }, { m }, { log += it }, spawn = { Thread {} })
        c.start()
        repeat(99) { assertTrue(c.pumpOnce()) }
        assertFalse(c.pumpOnce())
        assertEquals(MicMixStatus.FAILED, c.status); assertEquals(1, m.released)
        assertTrue(log.any { it.contains("no data") })
    }

    @Test fun `a thrown read is logged with the throwable and degrades`() {
        val m = FakeMic(onRead = { throw IllegalStateException("boom") })
        val log = mutableListOf<String>()
        val c = MicMixController({ true }, { m }, { log += it }, spawn = { Thread {} })
        c.start(); assertFalse(c.pumpOnce())
        assertTrue(log.any { it.contains("IllegalStateException") && it.contains("boom") })
        assertEquals(1, m.released)
    }

    @Test fun `spawn failure degrades, releases the record and never throws`() {
        val m = FakeMic()
        val c = MicMixController({ true }, { m }, { }, spawn = { throw OutOfMemoryError("no thread") })
        assertEquals(MicMixStatus.FAILED, c.start())
        assertEquals(1, m.released)
    }

    @Test fun `stop during a blocking start is not blocked and the late record is released`() {
        val entered = java.util.concurrent.CountDownLatch(1)
        val go = java.util.concurrent.CountDownLatch(1)
        val m = FakeMic()
        val c = MicMixController({ true }, { entered.countDown(); go.await(); m }, { }, spawn = { Thread {} })
        val starter = Thread { c.start() }
        starter.start()
        assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS))
        val stopper = Thread { c.stop() }
        stopper.start(); stopper.join(2000)
        assertFalse("stop() must not wait for the blocking factory", stopper.isAlive)
        go.countDown(); starter.join(2000)
        assertEquals(MicMixStatus.STOPPED, c.status); assertEquals(1, m.released); assertEquals(0, m.started)
    }

    // ---- silence detection (C) ----
    @Test fun `all zero reads flag the mic as silent once and recovery clears it`() {
        val zeros = ShortArray(480)
        var loud = false
        val m = FakeMic(onRead = { d -> (if (loud) ShortArray(480) { 9 } else zeros).copyInto(d); 480 })
        val log = mutableListOf<String>()
        val c = MicMixController({ true }, { m }, { log += it }, spawn = { Thread {} })
        c.start()
        repeat(MIC_SILENT_READS - 1) { c.pumpOnce() }
        assertFalse(c.isSilent)
        repeat(50) { c.pumpOnce() }
        assertTrue(c.isSilent)
        assertEquals(1, log.count { it.contains("silent") })
        assertTrue(c.statusText.contains("mic silent (blocked by system?)"))
        assertEquals(MicMixStatus.ACTIVE, c.status)
        loud = true; c.pumpOnce()
        assertFalse(c.isSilent); assertFalse(c.statusText.contains("silent"))
    }

    // ---- latency (F) ----
    @Test fun `first consumer read trims the ring to the target`() {
        val m = FakeMic(onRead = { d -> ShortArray(480) { 3 }.copyInto(d); 480 })
        val c = MicMixController({ true }, { m }, { }, spawn = { Thread {} })
        c.start(); repeat(10) { c.pumpOnce() } // 100 ms queued
        val d = ShortArray(480)
        assertEquals(480, c.fillMic(d, 480))
        assertEquals(MIC_RING_TARGET_SAMPLES - 480, c.buffered)
    }

    @Test fun `above the high water mark the ring is trimmed to the target`() {
        val m = FakeMic(onRead = { d -> ShortArray(480) { 3 }.copyInto(d); 480 })
        val c = MicMixController({ true }, { m }, { }, spawn = { Thread {} })
        c.start(); c.pumpOnce()
        val d = ShortArray(480); c.fillMic(d, 480) // primed
        repeat(8) { c.pumpOnce() }                  // 80 ms > high water
        assertTrue(c.buffered > MIC_RING_HIGH_WATER_SAMPLES)
        c.fillMic(d, 480)
        assertEquals(MIC_RING_TARGET_SAMPLES - 480, c.buffered)
    }

    @Test fun `below the high water mark nothing is dropped`() {
        val m = FakeMic(onRead = { d -> ShortArray(480) { 3 }.copyInto(d); 480 })
        val c = MicMixController({ true }, { m }, { }, spawn = { Thread {} })
        c.start(); c.pumpOnce(); val d = ShortArray(480); c.fillMic(d, 480)
        repeat(4) { c.pumpOnce() } // 40 ms
        c.fillMic(d, 480)
        assertEquals(4 * 480 - 480, c.buffered)
    }

    @Test fun `ring constants keep latency well below the 100 ms cap`() {
        assertTrue(MIC_RING_TARGET_SAMPLES < MIC_RING_HIGH_WATER_SAMPLES)
        assertEquals(60, MIC_RING_HIGH_WATER_SAMPLES * 1000 / MIC_MIX_SAMPLE_RATE)
        assertEquals(30, MIC_RING_TARGET_SAMPLES * 1000 / MIC_MIX_SAMPLE_RATE)
    }

    // ---- notification truthfulness (L1) ----
    @Test fun `controller reports display changes for start, silence, recovery and stop`() {
        var loud = false
        val m = FakeMic(onRead = { d -> ShortArray(480) { if (loud) 9 else 0 }.copyInto(d); 480 })
        val seen = mutableListOf<MicMixDisplay>()
        val c = MicMixController({ true }, { m }, { }, spawn = { Thread {} }, onDisplay = { seen += it })
        c.start()
        assertEquals(MicMixDisplay.ACTIVE, seen.last())
        repeat(MIC_SILENT_READS) { c.pumpOnce() }
        assertEquals(MicMixDisplay.SILENT, seen.last())
        loud = true; c.pumpOnce()
        assertEquals(MicMixDisplay.ACTIVE, seen.last())
        c.stop()
        assertEquals(MicMixDisplay.STOPPED, seen.last())
    }

    @Test fun `denied and failed starts report STOPPED and a throwing callback is contained`() {
        val seen = mutableListOf<MicMixDisplay>()
        MicMixController({ false }, { FakeMic() }, { }, onDisplay = { seen += it }).start()
        assertEquals(MicMixDisplay.STOPPED, seen.last())
        val c = MicMixController({ true }, { FakeMic(startOk = false) }, { }, onDisplay = { throw IllegalStateException("ui") })
        assertEquals(MicMixStatus.FAILED, c.start())
    }

    // ---- release ownership (L3): the reader thread releases, never while a read is in flight ----
    private class EventMic(val unblockOnStop: Boolean) : MicSource {
        val events: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
        val inRead = java.util.concurrent.CountDownLatch(1)
        val unblock = java.util.concurrent.CountDownLatch(1)
        override val initialized = true
        override fun start() = true
        override fun read(dst: ShortArray, size: Int): Int {
            inRead.countDown(); unblock.await(); events += "readEnd"; return -1
        }
        override fun stop() { events += "stop"; if (unblockOnStop) unblock.countDown() }
        override fun release() { events += "release" }
    }

    @Test fun `release happens once, after the last read returned, when stop unblocks the read`() {
        val m = EventMic(unblockOnStop = true)
        val c = MicMixController({ true }, { m }, { })
        c.start(); assertTrue(m.inRead.await(2, java.util.concurrent.TimeUnit.SECONDS))
        c.stop()
        awaitEvent(m, "release")
        assertEquals(1, m.events.count { it == "release" })
        assertTrue(m.events.indexOf("release") > m.events.indexOf("readEnd"))
    }

    @Test fun `a stuck read outliving the join timeout is never released under the reader`() {
        val m = EventMic(unblockOnStop = false)
        val c = MicMixController({ true }, { m }, { })
        c.start(); assertTrue(m.inRead.await(2, java.util.concurrent.TimeUnit.SECONDS))
        c.stop() // join times out: the reader is still inside read()
        assertEquals("must not release while the reader is alive", 0, m.events.count { it == "release" })
        m.unblock.countDown()
        awaitEvent(m, "release")
        assertEquals(1, m.events.count { it == "release" })
        assertTrue(m.events.indexOf("release") > m.events.indexOf("readEnd"))
        c.stop() // idempotent: no second release
        Thread.sleep(50)
        assertEquals(1, m.events.count { it == "release" })
    }

    private fun awaitEvent(m: EventMic, e: String) {
        val end = System.nanoTime() + 3_000_000_000L
        while (e !in m.events && System.nanoTime() < end) Thread.sleep(5)
        assertTrue("timed out waiting for $e: ${m.events}", e in m.events)
    }
}
