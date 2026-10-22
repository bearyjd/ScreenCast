package io.github.ddagunts.screencast.webrtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackAudioPolicyTest {
    @Test fun `audio mode truth table`() {
        assertEquals(AudioMode.OFF, resolveAudioMode(requested = false, recordAudioGranted = false, sdkInt = 34))
        assertEquals(AudioMode.OFF, resolveAudioMode(false, true, 34))
        assertEquals(AudioMode.NEEDS_PERMISSION, resolveAudioMode(true, false, 34))
        assertEquals(AudioMode.ACTIVE, resolveAudioMode(true, true, 34))
        assertEquals(AudioMode.ACTIVE, resolveAudioMode(true, true, 29))
    }

    @Test fun `playback capture needs Android 10`() {
        assertEquals(AudioMode.UNSUPPORTED_OS, resolveAudioMode(true, true, 28))
        assertEquals(AudioMode.UNSUPPORTED_OS, resolveAudioMode(true, false, 26))
        assertEquals("off stays off", AudioMode.OFF, resolveAudioMode(false, true, 26))
    }

    @Test fun `session config distinguishes requested from active`() {
        val base = WebRtcSessionConfig(VideoPreset.HD_30, 2_000_000, audioRequested = true, sdkInt = 34)
        assertFalse("requested without permission is not active", base.audioActive)
        assertTrue(base.copy(recordAudioGranted = true).audioActive)
        assertFalse(base.copy(audioRequested = false, recordAudioGranted = true).audioActive)
        assertFalse("below API 29", base.copy(recordAudioGranted = true, sdkInt = 28).audioActive)
    }

    @Test fun `the ADM source constant is literally REMOTE_SUBMIX 8`() {
        assertEquals(8, AUDIO_SOURCE_REMOTE_SUBMIX)
    }

    @Test fun `notes are consistent with an enabled switch`() {
        assertNull(audioNote(AudioMode.OFF))
        assertNull(audioNote(AudioMode.ACTIVE))
        val perm = audioNote(AudioMode.NEEDS_PERMISSION)
        assertNotNull(perm)
        assertTrue(perm!!.contains("Microphone") && perm.contains("not used"))
        assertFalse("must not say audio is off while the switch is on", perm.contains("is off", ignoreCase = true))
        assertFalse(perm.contains("\n"))
        assertTrue(audioNote(AudioMode.UNSUPPORTED_OS)!!.contains("Android 10"))
    }

    @Test fun `permission action`() {
        assertEquals(PermissionAction.NONE, permissionAction(granted = true, askedBefore = true, shouldShowRationale = false))
        assertEquals("first ask", PermissionAction.REQUEST, permissionAction(false, askedBefore = false, shouldShowRationale = false))
        assertEquals("denied once, rationale still possible", PermissionAction.REQUEST, permissionAction(false, true, true))
        assertEquals("permanent denial", PermissionAction.OPEN_SETTINGS, permissionAction(false, true, false))
    }

    @Test fun `playback buffer is at least two min buffers and one 10ms frame`() {
        assertEquals(2 * 3840, playbackBufferBytes(minBufferBytes = 3840, sampleRate = 48_000, channels = 2))
        assertEquals("floor of 10 ms", 480 * 2 * 2, playbackBufferBytes(minBufferBytes = 100, sampleRate = 48_000, channels = 2).let { maxOf(it, 480 * 2 * 2) })
        assertTrue(playbackBufferBytes(minBufferBytes = -1, sampleRate = 48_000, channels = 2) >= 480 * 2 * 2)
    }

    @Test fun `quiesce wait returns when quiet and honours the bound`() {
        var now = 0L
        assertTrue(awaitQuiet(300, 10, isQuiet = { now >= 40 }, nowMs = { now }, sleepMs = { now += it }))
        assertTrue("slept at most to the first quiet poll", now <= 50)
        now = 0
        assertFalse(awaitQuiet(300, 10, isQuiet = { false }, nowMs = { now }, sleepMs = { now += it }))
        assertTrue(now in 300..320)
        now = 0
        assertTrue("already quiet: no sleep", awaitQuiet(300, 10, { true }, { now }, { now += it }) && now == 0L)
    }

    @Test fun `release once runs the action exactly once`() {
        var n = 0
        val r = ReleaseOnce { n++ }
        r.run(); r.run(); r.run()
        assertEquals(1, n)
    }
}
