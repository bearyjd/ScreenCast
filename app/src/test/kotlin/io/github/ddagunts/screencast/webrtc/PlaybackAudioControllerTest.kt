package io.github.ddagunts.screencast.webrtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackAudioControllerTest {
    private val logs = mutableListOf<String>()
    private var now = 0L
    private fun controller(port: FakeAdmPort?, fallbackDisables: MutableList<String> = mutableListOf()) =
        PlaybackAudioController(
            log = { logs += it }, nowMs = { now }, sleepMs = { now += it },
        ).also { it.bind(port, fallbackDisable = { fallbackDisables += "fallback" }) }

    @Test fun `successful attach adds the audio track and keeps the record`() {
        val p = FakeAdmPort()
        val c = controller(p)
        assertTrue(c.attach(projectionAvailable = true))
        assertNotNull(c.record)
    }

    @Test fun `failed attach means no audio track and no record`() {
        val p = FakeAdmPort().apply { buildFails = IllegalStateException("x") }
        val c = controller(p)
        assertFalse(c.attach(true))
        assertNull(c.record)
    }

    @Test fun `no port (reflection setup failed) is video only and uses the fallback disable`() {
        val fb = mutableListOf<String>()
        val c = controller(null, fb)
        assertFalse(c.attach(true))
        assertEquals(listOf("fallback"), fb)
    }

    @Test fun `ADM callbacks route into the guard and disable recording`() {
        val calls = listOf<(PlaybackAudioController) -> Unit>(
            { it.stateCallback.onWebRtcAudioRecordStop() },
            { it.errorCallback.onWebRtcAudioRecordInitError("e") },
            { it.errorCallback.onWebRtcAudioRecordStartError(null, "e") },
            { it.errorCallback.onWebRtcAudioRecordError("e") },
        )
        for (call in calls) {
            val p = FakeAdmPort()
            val c = controller(p)
            c.attach(true)
            p.events.clear()
            call(c)
            assertTrue(c.guard.isTripped)
            assertTrue("disable requested", p.events.contains("disable"))
        }
    }

    @Test fun `start callback with our record stays armed, otherwise trips`() {
        val p = FakeAdmPort()
        val c = controller(p)
        c.attach(true)
        c.stateCallback.onWebRtcAudioRecordStart()
        assertFalse(c.guard.isTripped)
        p.identityHolds = false
        c.stateCallback.onWebRtcAudioRecordStart()
        assertTrue(c.guard.isTripped)
    }

    @Test fun `meter is enabled until the guard trips`() {
        val p = FakeAdmPort()
        val c = controller(p)
        c.attach(true)
        assertTrue(c.meterEnabled())
        c.errorCallback.onWebRtcAudioRecordError("x")
        assertFalse(c.meterEnabled())
    }

    @Test fun `close shuts the guard down, waits for the record to go quiet, then returns`() {
        val p = FakeAdmPort()
        val c = controller(p)
        c.attach(true)
        p.quietAfterDisable = true
        c.close(maxWaitMs = 300)
        assertTrue(c.guard.isTripped)
        assertTrue(p.events.contains("disable"))
        assertTrue("did not burn the whole bound", now < 300)
    }

    @Test fun `close is bounded when the record never quiets`() {
        val p = FakeAdmPort()
        val c = controller(p)
        c.attach(true)
        p.quietAfterDisable = false
        c.close(maxWaitMs = 300)
        assertTrue(now in 300..330)
    }

    @Test fun `releaseRecord releases exactly once however often it is called`() {
        val p = FakeAdmPort()
        val c = controller(p)
        c.attach(true)
        c.releaseRecord(); c.releaseRecord()
        assertEquals(1, (p.playback as FakeRec).releases)
        assertNull(c.record)
    }

    @Test fun `releaseRecord without a record is a no-op`() {
        controller(FakeAdmPort()).releaseRecord()
    }

    @Test fun `record is assigned before recording is re-enabled so an early start cannot trip the guard`() {
        val p = FakeAdmPort()
        val c = controller(p)
        var recordSeenAtEnable: PlaybackRecord? = null
        p.onEnable = { enabled ->
            if (enabled) {
                recordSeenAtEnable = c.record
                c.stateCallback.onWebRtcAudioRecordStart() // library thread reacts immediately
            }
        }
        assertTrue(c.attach(true))
        assertNotNull(recordSeenAtEnable)
        assertFalse("early start with our record must not trip", c.guard.isTripped)
    }

    @Test fun `failed swap leaves no record reference`() {
        val p = FakeAdmPort().apply { identityHolds = false }
        val c = controller(p)
        assertFalse(c.attach(true))
        assertNull(c.record)
    }

    @Test fun `audio off disables ADM recording up front and never swaps`() {
        val p = FakeAdmPort()
        val c = controller(p)
        c.prepare(audioActive = false)
        assertEquals(listOf("disable"), p.events)
        assertFalse("no audio track when audio is off", c.attach(true))
        assertEquals("no swap attempted", listOf("disable"), p.events)
        assertNull(c.record)
    }

    @Test fun `audio off without a port still disables through the fallback`() {
        val fb = mutableListOf<String>()
        val c = controller(null, fb)
        c.prepare(audioActive = false)
        assertEquals(listOf("fallback"), fb)
    }

    @Test fun `audio on does not disable up front`() {
        val p = FakeAdmPort()
        controller(p).prepare(audioActive = true)
        assertTrue(p.events.isEmpty())
    }
}
