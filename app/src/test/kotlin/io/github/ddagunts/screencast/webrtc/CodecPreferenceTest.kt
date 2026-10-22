// Fork modification by bearyjd: codec preference ordering + persistence tests.
package io.github.ddagunts.screencast.webrtc

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private fun c(mime: String, vararg p: Pair<String, String>) = CodecDescriptor(mime, p.toMap())

private val CAPS = listOf(
    c("video/VP8"),
    c("video/rtx"),
    c("video/VP9", "profile-id" to "0"),
    c("video/H264", "profile-level-id" to "42e01f", "packetization-mode" to "1"),
    c("video/H264", "profile-level-id" to "640c1f", "packetization-mode" to "1"),
    c("video/H264", "profile-level-id" to "4d001f"),
    c("video/AV1"),
    c("video/red"),
    c("video/ulpfec"),
    c("video/flexfec-03"),
)

class CodecOrderingTest {
    @Test fun `AUTO keeps order unchanged and applied`() {
        val r = orderCodecs(CAPS, VideoCodecPreference.AUTO)
        assertEquals(CAPS, r.codecs)
        assertTrue(r.applied)
    }

    @Test fun `H264 moves all H264 first, high profile leading, stable`() {
        val r = orderCodecs(CAPS, VideoCodecPreference.H264)
        assertTrue(r.applied)
        assertEquals(
            listOf("640c1f", "42e01f", "4d001f"),
            r.codecs.take(3).map { it.parameters["profile-level-id"] },
        )
        assertEquals(CAPS.filterNot { it.mimeType == "video/H264" }, r.codecs.drop(3))
    }

    @Test fun `non media codecs are kept`() {
        val r = orderCodecs(CAPS, VideoCodecPreference.VP9)
        assertEquals(CAPS.size, r.codecs.size)
        assertEquals(CAPS.toSet(), r.codecs.toSet())
        for (m in listOf("video/rtx", "video/red", "video/ulpfec", "video/flexfec-03")) {
            assertTrue(r.codecs.any { it.mimeType == m })
        }
        assertEquals("video/VP9", r.codecs.first().mimeType)
    }

    @Test fun `mime match is case insensitive`() {
        val r = orderCodecs(listOf(c("video/vp8"), c("video/av1")), VideoCodecPreference.AV1)
        assertEquals("video/av1", r.codecs.first().mimeType)
    }

    @Test fun `unsupported preference falls back unchanged and not applied`() {
        val caps = listOf(c("video/VP8"), c("video/rtx"))
        val r = orderCodecs(caps, VideoCodecPreference.AV1)
        assertEquals(caps, r.codecs)
        assertFalse(r.applied)
    }

    @Test fun `empty input is safe`() {
        val r = orderCodecs(emptyList(), VideoCodecPreference.H264)
        assertTrue(r.codecs.isEmpty())
        assertFalse(r.applied)
    }

    @Test fun `input list is not mutated`() {
        val copy = CAPS.toList()
        orderCodecs(CAPS, VideoCodecPreference.H264)
        assertEquals(copy, CAPS)
    }

    @Test fun `fromName maps unknown and null to AUTO`() {
        assertEquals(VideoCodecPreference.AUTO, VideoCodecPreference.fromName(null))
        assertEquals(VideoCodecPreference.AUTO, VideoCodecPreference.fromName("H265"))
        assertEquals(VideoCodecPreference.VP9, VideoCodecPreference.fromName("VP9"))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VideoCodecPreferenceStoreTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()

    @Test fun `default is AUTO and snapshot carries it`() {
        val s = WebRtcConfigStore(ctx)
        assertEquals(VideoCodecPreference.AUTO, s.videoCodecPreference)
        assertEquals(VideoCodecPreference.AUTO, s.snapshot().videoCodecPreference)
    }

    @Test fun `round trip`() {
        for (p in VideoCodecPreference.entries) {
            WebRtcConfigStore(ctx).videoCodecPreference = p
            assertEquals(p, WebRtcConfigStore(ctx).videoCodecPreference)
            assertEquals(p, WebRtcConfigStore(ctx).snapshot().videoCodecPreference)
        }
    }

    @Test fun `unknown stored value yields AUTO`() {
        ctx.getSharedPreferences("webrtc_config", Context.MODE_PRIVATE).edit()
            .putString("video_codec_preference", "H266").apply()
        assertEquals(VideoCodecPreference.AUTO, WebRtcConfigStore(ctx).videoCodecPreference)
    }

    @Test fun `session config defaults to AUTO`() {
        assertEquals(
            VideoCodecPreference.AUTO,
            WebRtcSessionConfig(VideoPreset.HD_30, 2_000_000, false).videoCodecPreference,
        )
    }
}
