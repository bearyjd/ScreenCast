package io.github.ddagunts.screencast.webrtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackAudioGuardTest {
    private val logs = mutableListOf<String>()
    private fun guard(ours: () -> Boolean = { true }, onDisable: () -> Unit = {}, blocking: (() -> Unit)? = null) =
        PlaybackAudioGuard(disable = onDisable, isOurRecordActive = ours, log = { logs += it },
            disableBlocking = blocking ?: onDisable)

    @Test fun `trips once on stop and logs once`() {
        var d = 0
        val g = guard(onDisable = { d++ })
        g.onRecordStop(); g.onRecordStop(); g.onError("x")
        assertTrue(g.isTripped); assertEquals(1, d); assertEquals(1, logs.size)
    }

    @Test fun `trips on every ADM error callback`() {
        val calls = listOf<(PlaybackAudioGuard) -> Unit>({ it.onInitError("e") }, { it.onStartError("e") }, { it.onError("e") })
        for (c in calls) { var d = 0; val g = guard(onDisable = { d++ }); c(g); assertTrue(g.isTripped); assertEquals(1, d) }
    }

    @Test fun `start with our record stays armed`() {
        var d = 0; val g = guard(onDisable = { d++ })
        g.onRecordStart()
        assertFalse(g.isTripped); assertEquals(0, d)
    }

    @Test fun `start with any other record trips`() {
        var d = 0; val g = guard(ours = { false }, onDisable = { d++ })
        g.onRecordStart()
        assertTrue(g.isTripped); assertEquals(1, d)
    }

    @Test fun `shutdown uses the blocking disable once and is silent`() {
        var async = 0; var sync = 0
        val g = guard(onDisable = { async++ }, blocking = { sync++ })
        g.shutdown(); g.onError("teardown noise"); g.shutdown()
        assertEquals(1, sync); assertEquals(0, async); assertTrue(logs.isEmpty())
    }

    @Test fun `survives a throwing disable`() {
        val g = guard(ours = { false }, onDisable = { error("boom") })
        g.onRecordStart()
        assertTrue(g.isTripped)
    }
}
