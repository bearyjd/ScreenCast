package io.github.ddagunts.screencast.webrtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class StreamIdsTest {
    @Test fun sharedModeGivesBothTracksTheSameStreamId() {
        val video = webRtcStreamIds(WebRtcTrackKind.VIDEO, shared = true)
        val audio = webRtcStreamIds(WebRtcTrackKind.AUDIO, shared = true)
        assertEquals(1, video.size)
        assertEquals(video, audio)
        assertEquals(listOf(WEBRTC_SHARED_STREAM_ID), video)
    }

    @Test fun legacyModeKeepsSeparateStreamIds() {
        assertEquals(listOf("screen-video"), webRtcStreamIds(WebRtcTrackKind.VIDEO, shared = false))
        assertEquals(listOf("screen-audio"), webRtcStreamIds(WebRtcTrackKind.AUDIO, shared = false))
        assertNotEquals(
            webRtcStreamIds(WebRtcTrackKind.VIDEO, shared = false),
            webRtcStreamIds(WebRtcTrackKind.AUDIO, shared = false),
        )
    }

    // Source-level guard: a revert to hard-coded stream ids in the peer would bypass the flag.
    @Test fun peerDoesNotHardCodeStreamIds() {
        val src = listOf(
            "src/main/java/io/github/ddagunts/screencast/webrtc/WebRtcPeer.kt",
            "app/src/main/java/io/github/ddagunts/screencast/webrtc/WebRtcPeer.kt",
        ).map(::File).first { it.exists() }.readText()
        assertFalse(src.contains("listOf(\"screen-"))
        assertTrue(src.contains("webRtcStreamIds("))
    }

    @Test fun trackIdsStayDistinct() {
        assertNotEquals(WEBRTC_VIDEO_TRACK_ID, WEBRTC_AUDIO_TRACK_ID)
    }

    // Pinned default: flip deliberately, only after the attended cast-sim A/B
    // (tools/cast-sim/README.md) shows the audio_vs_video delta shrinks and audio is not choppy.
    @Test fun sharedStreamDefaultIsPinned() {
        assertFalse(WEBRTC_SHARED_STREAM_ID_DEFAULT)
    }
}
