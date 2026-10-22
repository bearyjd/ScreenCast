// Fork modification by bearyjd: WebRTC codec/stats visibility tests.
package io.github.ddagunts.screencast.webrtc

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VideoStatsTest {
    private fun report(
        codecMime: String? = "video/H264",
        outbound: Map<String, Any?> = emptyMap(),
        tsUs: Double = 1_000_000.0,
    ): Map<String, StatEntry> {
        val base = mapOf<String, Any?>(
            "kind" to "video", "codecId" to "C1", "encoderImplementation" to "MediaCodec",
            "powerEfficientEncoder" to true, "framesPerSecond" to 58.0, "frameWidth" to 1920L,
            "frameHeight" to 1080L, "framesEncoded" to 100L, "totalEncodeTime" to 0.5,
            "bytesSent" to 1_000_000L, "qualityLimitationReason" to "none",
            "nackCount" to 3L, "pliCount" to 1L,
        )
        val map = mutableMapOf(
            "OV" to StatEntry("outbound-rtp", tsUs, base + outbound),
            "OA" to StatEntry("outbound-rtp", tsUs, mapOf("kind" to "audio")),
        )
        if (codecMime != null) map["C1"] = StatEntry("codec", tsUs, mapOf("mimeType" to codecMime, "clockRate" to 90000L))
        return map
    }

    @Test fun `parse extracts video outbound and codec`() {
        val s = checkNotNull(parseVideoStats(report()))
        assertEquals("video/H264", s.codecMime)
        assertEquals("MediaCodec", s.encoderImpl)
        assertEquals(true, s.powerEfficient)
        assertEquals(58.0, s.fps!!, 0.0)
        assertEquals(1920, s.width)
        assertEquals(1080, s.height)
        assertEquals(100L, s.framesEncoded)
        assertEquals(1_000_000L, s.bytesSent)
        assertEquals("none", s.qualityLimit)
        assertEquals(3L, s.nack)
        assertEquals(1L, s.pli)
        assertEquals(1000.0, s.timestampMs!!, 0.0)
    }

    @Test fun `parse accepts BigInteger and Integer numbers`() {
        val s = checkNotNull(parseVideoStats(report(outbound = mapOf(
            "bytesSent" to java.math.BigInteger.valueOf(42), "nackCount" to 7, "frameWidth" to 1280))))
        assertEquals(42L, s.bytesSent)
        assertEquals(7L, s.nack)
        assertEquals(1280, s.width)
    }

    @Test fun `parse returns null without a video outbound report`() {
        assertNull(parseVideoStats(emptyMap()))
        assertNull(parseVideoStats(mapOf("OA" to StatEntry("outbound-rtp", 0.0, mapOf("kind" to "audio")))))
    }

    @Test fun `parse picks the video outbound with most bytes and skips rtx`() {
        val r = report() + mapOf(
            "OV2" to StatEntry("outbound-rtp", 0.0, mapOf("kind" to "video", "codecId" to "C2", "bytesSent" to 5_000_000L, "frameWidth" to 640L)),
            "C2" to StatEntry("codec", 0.0, mapOf("mimeType" to "video/rtx")),
            "OV3" to StatEntry("outbound-rtp", 0.0, mapOf("kind" to "video", "codecId" to "C1", "bytesSent" to 2_000_000L, "frameWidth" to 1280L)),
            "OA2" to StatEntry("outbound-rtp", 0.0, mapOf("kind" to "audio", "bytesSent" to 9_000_000L)),
        )
        val s = checkNotNull(parseVideoStats(r))
        assertEquals(1280, s.width)
        assertEquals(2_000_000L, s.bytesSent)
    }

    @Test fun `parse falls back to mediaType when kind missing`() {
        val r = mapOf("OV" to StatEntry("outbound-rtp", 0.0, mapOf("mediaType" to "video")))
        checkNotNull(parseVideoStats(r))
    }

    @Test fun `parse tolerates missing fields and missing codec report`() {
        val r = mapOf("OV" to StatEntry("outbound-rtp", 0.0, mapOf("kind" to "video", "codecId" to "GONE")))
        val s = checkNotNull(parseVideoStats(r))
        assertNull(s.codecMime)
        assertNull(s.encoderImpl)
        assertNull(s.framesEncoded)
        assertNull(s.width)
    }

    @Test fun `parse ignores wrongly typed values`() {
        val s = checkNotNull(parseVideoStats(report(outbound = mapOf("framesEncoded" to "abc", "powerEfficientEncoder" to 1))))
        assertNull(s.framesEncoded)
        assertNull(s.powerEfficient)
    }

    @Test fun `first sample reports n-a for rates`() {
        val line = formatVideoStats(checkNotNull(parseVideoStats(report())), null)
        assertEquals(
            "video stats: codec=video/H264 impl=MediaCodec hw=true fps=58 res=1920x1080 " +
                "encMsPerFrame=n/a kbps=n/a qualityLimit=none nack=3 pli=1",
            line,
        )
    }

    @Test fun `second sample computes deltas`() {
        val a = checkNotNull(parseVideoStats(report()))
        val b = checkNotNull(parseVideoStats(report(
            outbound = mapOf("framesEncoded" to 160L, "totalEncodeTime" to 0.62, "bytesSent" to 1_375_000L),
            tsUs = 4_000_000.0,
        )))
        val line = formatVideoStats(b, a)
        // 0.12 s / 60 frames = 2.0 ms ; 375000 B * 8 / 3 s = 1000 kbps
        assertTrue(line, line.contains("encMsPerFrame=2.0"))
        assertTrue(line, line.contains("kbps=1000 "))
    }

    @Test fun `zero frames delta does not divide by zero`() {
        val a = checkNotNull(parseVideoStats(report()))
        val b = checkNotNull(parseVideoStats(report(tsUs = 4_000_000.0)))
        assertTrue(formatVideoStats(b, a).contains("encMsPerFrame=n/a"))
    }

    @Test fun `zero time delta and counter reset give n-a`() {
        val a = checkNotNull(parseVideoStats(report()))
        val same = formatVideoStats(a, a)
        assertTrue(same, same.contains("kbps=n/a"))
        val reset = checkNotNull(parseVideoStats(report(
            outbound = mapOf("bytesSent" to 10L, "framesEncoded" to 1L), tsUs = 4_000_000.0)))
        val line = formatVideoStats(reset, a)
        assertTrue(line, line.contains("kbps=n/a") && line.contains("encMsPerFrame=n/a"))
    }

    @Test fun `missing fields format as n-a`() {
        val r = mapOf("OV" to StatEntry("outbound-rtp", 0.0, mapOf("kind" to "video")))
        val line = formatVideoStats(checkNotNull(parseVideoStats(r)), null)
        assertEquals(
            "video stats: codec=n/a impl=n/a hw=n/a fps=n/a res=n/a encMsPerFrame=n/a kbps=n/a qualityLimit=n/a nack=n/a pli=n/a",
            line,
        )
    }

    @Test fun `sampler logs every interval and first line has n-a rates`() = runTest {
        val lines = mutableListOf<String>()
        var n = 0
        val sampler = VideoStatsSampler(
            fetch = { n++; report(tsUs = n * 3_000_000.0, outbound = mapOf("framesEncoded" to n * 60L, "totalEncodeTime" to n * 0.12, "bytesSent" to n * 375_000L)) },
            log = { lines += it },
            intervalMs = 3_000,
        )
        val job = launch { sampler.run() }
        runCurrent()
        assertEquals(1, lines.size)
        assertTrue(lines[0].contains("kbps=n/a"))
        advanceTimeBy(3_000); runCurrent()
        assertEquals(2, lines.size)
        assertTrue(lines[1], lines[1].contains("kbps=1000"))
        job.cancel()
    }

    @Test fun `sampler survives fetch errors and null reports but rethrows cancellation`() = runTest {
        val lines = mutableListOf<String>()
        var n = 0
        val sampler = VideoStatsSampler(
            fetch = { when (n++) { 0 -> error("boom"); 1 -> null; else -> report() } },
            log = { lines += it },
            intervalMs = 1_000,
        )
        val job = launch { sampler.run() }
        advanceTimeBy(2_500); runCurrent()
        assertEquals(1, lines.size)
        job.cancel()
        runCurrent()
        assertTrue(job.isCancelled)
    }

    @Test fun `sampler hands each parsed sample to onSample`() = runTest {
        val seen = mutableListOf<String?>()
        val sampler = VideoStatsSampler(
            fetch = { report() }, log = {}, intervalMs = 1_000,
            onSample = { seen += it.codecMime },
        )
        val job = launch { sampler.run() }
        advanceTimeBy(1_500); runCurrent()
        assertEquals(2, seen.size)
        job.cancel()
    }

    @Test fun `sampler stops logging after cancel`() = runTest {
        val lines = mutableListOf<String>()
        val sampler = VideoStatsSampler(fetch = { report() }, log = { lines += it }, intervalMs = 1_000)
        val job = launch { sampler.run() }
        runCurrent()
        job.cancel()
        advanceTimeBy(10_000); runCurrent()
        assertEquals(1, lines.size)
        assertFalse(job.isActive)
    }

    @Test fun `sampler propagates CancellationException from fetch`() = runTest {
        val sampler = VideoStatsSampler(fetch = { throw CancellationException("x") }, log = {}, intervalMs = 1_000)
        val job = launch { sampler.run() }
        runCurrent()
        assertTrue(job.isCancelled)
    }
}
