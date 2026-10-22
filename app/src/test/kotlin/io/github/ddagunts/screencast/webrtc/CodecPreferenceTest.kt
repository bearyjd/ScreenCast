// Fork modification by bearyjd: codec preference ordering + persistence tests.
package io.github.ddagunts.screencast.webrtc

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test fun `H264 preference keeps VP8 and other codecs after H264`() {
        val r = orderCodecs(CAPS, VideoCodecPreference.H264)
        val mimes = r.codecs.map { it.mimeType }
        assertEquals(CAPS.size, mimes.size)
        val lastH264 = mimes.lastIndexOf("video/H264")
        for (m in listOf("video/VP8", "video/VP9", "video/AV1", "video/rtx")) {
            assertTrue(m, mimes.indexOf(m) > lastH264)
        }
    }

    @Test fun `fromName maps unknown and null to H264`() {
        assertEquals(VideoCodecPreference.H264, VideoCodecPreference.fromName(null))
        assertEquals(VideoCodecPreference.H264, VideoCodecPreference.fromName("H265"))
        assertEquals(VideoCodecPreference.AUTO, VideoCodecPreference.fromName("AUTO"))
        assertEquals(VideoCodecPreference.VP9, VideoCodecPreference.fromName("VP9"))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VideoCodecPreferenceStoreTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()

    @Test fun `default is H264 and snapshot carries it`() {
        val s = WebRtcConfigStore(ctx)
        assertEquals(VideoCodecPreference.H264, s.videoCodecPreference)
        assertEquals(VideoCodecPreference.H264, s.snapshot().videoCodecPreference)
    }

    @Test fun `round trip`() {
        for (p in VideoCodecPreference.entries) {
            WebRtcConfigStore(ctx).videoCodecPreference = p
            assertEquals(p, WebRtcConfigStore(ctx).videoCodecPreference)
            assertEquals(p, WebRtcConfigStore(ctx).snapshot().videoCodecPreference)
        }
    }

    @Test fun `explicit AUTO is persisted and honoured`() {
        WebRtcConfigStore(ctx).videoCodecPreference = VideoCodecPreference.AUTO
        assertEquals(VideoCodecPreference.AUTO, WebRtcConfigStore(ctx).videoCodecPreference)
        assertEquals(VideoCodecPreference.AUTO, WebRtcConfigStore(ctx).snapshot().videoCodecPreference)
    }

    @Test fun `unknown stored value yields H264`() {
        ctx.getSharedPreferences("webrtc_config", Context.MODE_PRIVATE).edit()
            .putString("video_codec_preference", "H266").apply()
        assertEquals(VideoCodecPreference.H264, WebRtcConfigStore(ctx).videoCodecPreference)
    }

    @Test fun `session config defaults to H264`() {
        assertEquals(
            VideoCodecPreference.H264,
            WebRtcSessionConfig(VideoPreset.HD_30, 2_000_000, false).videoCodecPreference,
        )
    }
}

class CodecMismatchTest {
    private val h264 = VideoCodecPreference.H264

    @Test fun `AUTO never mismatches`() {
        for (m in listOf("video/VP8", "video/H264", null, "")) {
            assertNull(codecMismatch(VideoCodecPreference.AUTO, m))
        }
    }

    @Test fun `null blank and unknown mime yield null`() {
        for (m in listOf(null, "", "  ", "video/rtx", "video/H265", "garbage")) {
            assertNull(m, codecMismatch(h264, m))
        }
    }

    @Test fun `matching mime is case insensitive`() {
        assertNull(codecMismatch(h264, "video/H264"))
        assertNull(codecMismatch(h264, "video/h264"))
        assertNull(codecMismatch(VideoCodecPreference.VP8, "VIDEO/vp8"))
        assertNull(codecMismatch(VideoCodecPreference.VP9, "video/VP9"))
        assertNull(codecMismatch(VideoCodecPreference.AV1, "video/av1"))
    }

    @Test fun `not applied preference uses neutral phone wording`() {
        assertEquals(
            "H.264 unavailable on this phone; using VP8",
            codecMismatch(h264, "video/VP8", applied = false),
        )
        assertNull(codecMismatch(h264, "video/H264", applied = false))
        assertNull(codecMismatch(VideoCodecPreference.AUTO, "video/VP8", applied = false))
        assertNull(codecMismatch(h264, "video/rtx", applied = false))
    }

    @Test fun `applied preference blames the TV`() {
        assertEquals("Requested H.264 but the TV chose VP8", codecMismatch(h264, "video/VP8", applied = true))
    }

    @Test fun `mismatch message names both codecs`() {
        assertEquals("Requested H.264 but the TV chose VP8", codecMismatch(h264, "video/VP8"))
        assertEquals("Requested AV1 but the TV chose VP8", codecMismatch(VideoCodecPreference.AV1, "video/vp8"))
        assertEquals("Requested VP9 but the TV chose H.264", codecMismatch(VideoCodecPreference.VP9, "video/H264"))
        assertEquals("Requested VP8 but the TV chose AV1", codecMismatch(VideoCodecPreference.VP8, "video/AV1"))
    }
}

class CodecMismatchReporterTest {
    private fun sample(mime: String?) = VideoStatsSample(
        mime, null, null, null, null, null, null, null, null, null, null, null, null,
    )

    @Test fun `reports once on first sample with a mime`() {
        val warns = mutableListOf<String>()
        val published = mutableListOf<String>()
        val r = CodecMismatchReporter(VideoCodecPreference.H264, { warns += it }, { published += it })
        r.onSample(sample(null))
        assertTrue(warns.isEmpty() && published.isEmpty())
        r.onSample(sample("video/VP8"))
        r.onSample(sample("video/VP8"))
        r.onSample(sample("video/H264"))
        assertEquals(listOf("Requested H.264 but the TV chose VP8"), warns)
        assertEquals(warns, published)
    }

    @Test fun `first mime decides even when it matches`() {
        val warns = mutableListOf<String>()
        val r = CodecMismatchReporter(VideoCodecPreference.H264, { warns += it }, { warns += it })
        r.onSample(sample("video/H264"))
        r.onSample(sample("video/VP8"))
        assertTrue(warns.isEmpty())
    }

    @Test fun `AUTO and unknown mime stay silent`() {
        val warns = mutableListOf<String>()
        CodecMismatchReporter(VideoCodecPreference.AUTO, { warns += it }, { warns += it })
            .onSample(sample("video/VP8"))
        CodecMismatchReporter(VideoCodecPreference.H264, { warns += it }, { warns += it })
            .onSample(sample("video/rtx"))
        assertTrue(warns.isEmpty())
    }

    @Test fun `reporter reads applied lazily at decision time`() {
        val published = mutableListOf<String>()
        var applied = true
        val r = CodecMismatchReporter(VideoCodecPreference.H264, {}, { published += it }, { applied })
        applied = false
        r.onSample(sample("video/VP8"))
        assertEquals(listOf("H.264 unavailable on this phone; using VP8"), published)
    }

    @Test fun `a new reporter per cast reports again`() {
        val published = mutableListOf<String>()
        repeat(2) {
            CodecMismatchReporter(VideoCodecPreference.H264, {}, { published += it })
                .onSample(sample("video/VP8"))
        }
        assertEquals(2, published.size)
    }
}
