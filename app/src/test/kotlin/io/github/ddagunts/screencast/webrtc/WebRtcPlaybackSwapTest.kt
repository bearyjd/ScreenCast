package io.github.ddagunts.screencast.webrtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class WebRtcPlaybackSwapTest {
    private val logs = mutableListOf<String>()
    private fun run(p: FakeAdmPort, projection: Boolean = true) = swapPlayback(p, projection, { logs += it })

    @Test fun `success order is disable, init, build, install, enable inside one lock`() {
        val p = FakeAdmPort()
        val r = run(p)
        assertSame(p.playback, r)
        assertEquals(listOf("lock+", "enable(false)", "init", "build", "install", "enable(true)", "lock-"), p.events)
        assertEquals("owned by the ADM now", 0, (p.playback as FakeRec).releases)
    }

    @Test fun `no mic record API exists on the port and a failed swap never re-enables`() {
        val p = FakeAdmPort().apply { buildFails = IllegalStateException("x") }
        assertNull(run(p))
        assertFalse(p.events.contains("enable(true)"))
        assertTrue(p.events.contains("disable"))
    }

    @Test fun `no projection means video only`() {
        val p = FakeAdmPort()
        assertNull(run(p, projection = false))
        assertEquals(listOf("disable"), p.events)
    }

    @Test fun `native start already requested aborts before any mutation`() {
        val p = FakeAdmPort().apply { threadIdle = false }
        assertNull(run(p))
        assertEquals(listOf("lock+", "lock-", "disable"), p.events)
    }

    @Test fun `an existing record aborts before any mutation`() {
        val p = FakeAdmPort().apply { hasRecordAtStart = true }
        assertNull(run(p))
        assertFalse(p.events.contains("init")); assertFalse(p.events.contains("enable(false)"))
        assertTrue(p.events.contains("disable"))
    }

    @Test fun `reflection and build failures at every stage are video only`() {
        val failures = listOf(
            NoSuchFieldException("audioRecord"), NoSuchMethodException("initRecordingIfNeeded"),
            SecurityException("denied"), IllegalStateException("InaccessibleObject"),
            IllegalArgumentException("AudioRecord build failed"), UnsupportedOperationException(),
        )
        for (f in failures) for (stage in 0..2) {
            val p = FakeAdmPort()
            when (stage) { 0 -> p.initFails = f; 1 -> p.buildFails = f; else -> p.installFails = f }
            assertNull("${f.javaClass.simpleName}@$stage", run(p))
            assertTrue(p.events.contains("disable"))
            assertFalse(p.events.contains("enable(true)"))
            val rec = (p.playback as FakeRec)
            assertEquals("built records are released exactly once (stage $stage)", if (stage == 2) 1 else 0, rec.releases)
        }
    }

    @Test fun `init returning false is video only`() {
        val p = FakeAdmPort().apply { initResult = false }
        assertNull(run(p)); assertTrue(p.events.contains("disable")); assertFalse(p.events.contains("build"))
    }

    @Test fun `uninitialized playback record is released once and never installed`() {
        val p = FakeAdmPort().apply { playback = FakeRec(state = 0) }
        assertNull(run(p))
        assertEquals(1, (p.playback as FakeRec).releases)
        assertFalse(p.events.contains("install"))
        assertTrue(p.events.contains("disable"))
    }

    @Test fun `identity failure uninstalls releases once and disables`() {
        val p = FakeAdmPort().apply { identityHolds = false }
        assertNull(run(p))
        assertTrue(p.events.contains("uninstall"))
        assertEquals(1, (p.playback as FakeRec).releases)
        assertTrue(p.events.contains("disable"))
    }

    @Test fun `source number alone never rejects a record that is ours`() {
        val p = FakeAdmPort()
        assertSame(p.playback, run(p)) // diagnosticSource() is only logged
    }

    @Test fun `onInstalled fires after install and before recording is re-enabled`() {
        val p = FakeAdmPort()
        val seen = mutableListOf<String>()
        swapPlayback(p, true, { }, onInstalled = { seen += "cb:" + p.events.last() })
        assertEquals(listOf("cb:install"), seen)
    }
}
