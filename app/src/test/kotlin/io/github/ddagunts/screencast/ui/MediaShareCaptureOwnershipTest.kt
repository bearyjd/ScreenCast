// Fork addition by bearyjd: regress retained capture errors blocking explicit media handoff.
package io.github.ddagunts.screencast.ui

import io.github.ddagunts.screencast.CastForegroundService
import io.github.ddagunts.screencast.WebRtcForegroundService
import io.github.ddagunts.screencast.cast.CastDevice
import io.github.ddagunts.screencast.cast.CastVolume
import io.github.ddagunts.screencast.cast.CastState
import io.github.ddagunts.screencast.media.SharedMedia
import io.github.ddagunts.screencast.media.SharedMediaResult
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

class MediaShareCaptureOwnershipTest {
    private val device = CastDevice("TV", "192.168.1.2", 8009)
    private fun hls(status: String) = mapOf(device.host to CastForegroundService.DeviceCast(
        device, status, "IDLE", CastVolume(), errorMessage = if (status == "ERROR") "failed" else null,
    ))
    private fun webRtc(status: String) = WebRtcForegroundService.SessionSnapshot(
        device, status, errorMessage = if (status == "ERROR") "failed" else null,
    )

    @Test fun retainedWebRtcErrorPermitsDirectMediaHandoff() {
        assertFalse(isCaptureHostBusy(device.host, emptyMap(), webRtc("ERROR")))
    }
    @Test fun retainedHlsErrorPermitsDirectMediaHandoff() {
        assertFalse(isCaptureHostBusy(device.host, hls("ERROR"), null))
    }
    @Test fun activeCaptureStatusesBlockOnlyTheirReceiver() {
        listOf("CONNECTING", "CASTING").forEach { status ->
            assertTrue(status, isCaptureHostBusy(device.host, hls(status), null))
            assertFalse(status, isCaptureHostBusy("192.168.1.3", hls(status), null))
        }
        listOf("CONNECTING", "LAUNCHING", "SIGNALING", "CASTING").forEach { status ->
            assertTrue(status, isCaptureHostBusy(device.host, emptyMap(), webRtc(status)))
            assertFalse(status, isCaptureHostBusy("192.168.1.3", emptyMap(), webRtc(status)))
        }
    }
    @Test fun terminalHlsIdleDoesNotBlockButOtherActiveSourceStillDoes() {
        assertFalse(isCaptureHostBusy(device.host, hls("IDLE"), null))
        assertTrue(isCaptureHostBusy(device.host, hls("ERROR"), webRtc("CONNECTING")))
        assertTrue(isCaptureHostBusy(device.host, hls("CASTING"), webRtc("ERROR")))
    }
    @Test fun retainedErrorsAllowExplicitLaunchAndActiveCapturePreventsIt() = runBlocking {
        val cases = listOf(
            Triple(hls("ERROR"), null, true),
            Triple(emptyMap(), webRtc("ERROR"), true),
            Triple(hls("ERROR"), webRtc("ERROR"), true),
            Triple(hls("CONNECTING"), webRtc("ERROR"), false),
            Triple(hls("ERROR"), webRtc("SIGNALING"), false),
        )
        cases.forEach { (hls, webRtc, allowed) ->
            var creates = 0
            val controller = MediaHandoffController(this, {
                creates++
                object : HandoffSession {
                    override val state = MutableStateFlow<CastState>(CastState.Idle)
                    override val playerState = MutableStateFlow("IDLE")
                    override suspend fun start(media: SharedMedia) {}
                    override suspend fun pause() {}
                    override suspend fun play() {}
                    override suspend fun stop() {}
                    override fun close() {}
                }
            }, { host -> isCaptureHostBusy(host, hls, webRtc) })
            try {
                controller.preview(SharedMediaResult(SharedMedia("https://example/media.mp4", "video/mp4")))
                controller.select(device)
                assertEquals(0, creates)
                controller.cast()
                yield()
                assertEquals(if (allowed) 1 else 0, creates)
                if (allowed) assertNull(controller.state.value.error) else assertNotNull(controller.state.value.error)
            } finally { controller.disconnect() }
        }
    }
}
