package io.github.ddagunts.screencast

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import io.github.ddagunts.screencast.cast.CastDevice
import io.github.ddagunts.screencast.cast.CastMessage
import io.github.ddagunts.screencast.cast.CastNs
import io.github.ddagunts.screencast.cast.SENDER_ID
import io.github.ddagunts.screencast.webrtc.FakeStore
import io.github.ddagunts.screencast.webrtc.FakeVolume
import io.github.ddagunts.screencast.webrtc.MediaMuteGuard
import io.github.ddagunts.screencast.webrtc.MuteMarker
import io.github.ddagunts.screencast.webrtc.SessionPeer
import io.github.ddagunts.screencast.webrtc.SessionTransport
import io.github.ddagunts.screencast.webrtc.WebRtcCastSession
import io.github.ddagunts.screencast.webrtc.WebRtcConfigStore
import io.github.ddagunts.screencast.webrtc.WebRtcSessionConfig
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection.PeerConnectionState

internal class WireTransport : SessionTransport {
    override val incoming = MutableSharedFlow<CastMessage>(extraBufferCapacity = 8)
    override fun connect(onClose: (Throwable?) -> Unit): Job = Job()
    override suspend fun send(message: CastMessage) {}
    override fun close() {}
    suspend fun ready() = incoming.emit(CastMessage("receiver-0", SENDER_ID, CastNs.RECEIVER,
        """{"type":"RECEIVER_STATUS","status":{"applications":[{"transportId":"transport","sessionId":"session"}]}}"""))
}

internal class WirePeer : SessionPeer {
    override val connectionState = MutableStateFlow(PeerConnectionState.NEW)
    override fun build() {}
    override fun addScreenSource(data: Intent, onProjectionStopped: () -> Unit) {}
    override suspend fun createOffer() = "offer"
    override suspend fun setRemoteAnswer(sdp: String) {}
    override fun addRemoteIceCandidate(candidate: IceCandidate) {}
    override fun close() {}
}

private class ThrowingCloseSession(
    ctx: Context, device: CastDevice, cfg: WebRtcSessionConfig,
    t: SessionTransport, p: SessionPeer, d: CoroutineDispatcher,
) : WebRtcCastSession(ctx, device, cfg, t, { p }, d) {
    override fun close() { super.close(); throw IllegalStateException("close boom") }
}

class MuteWiringService : WebRtcForegroundService() {
    override val workerDispatcher get() = dispatcher
    override fun createMuteGuard() = guard
    override fun createSession(device: CastDevice, config: WebRtcSessionConfig): WebRtcCastSession {
        val t = WireTransport(); val p = WirePeer()
        transport = t; peer = p
        return if (throwOnClose) ThrowingCloseSession(this, device, config, t, p, dispatcher)
        else WebRtcCastSession(this, device, config, t, { p }, dispatcher)
    }
    internal companion object Hooks {
        lateinit var dispatcher: CoroutineDispatcher
        lateinit var guard: MediaMuteGuard
        var throwOnClose = false
        internal var transport: WireTransport? = null
        internal var peer: WirePeer? = null
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WebRtcServiceMuteWiringTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private val vol = FakeVolume()
    private val store = FakeStore()

    @Before fun setUp() {
        MuteWiringService.guard = MediaMuteGuard(vol, store, clock = { 1L }, log = {})
        MuteWiringService.throwOnClose = false
    }
    @After fun tearDown() { MuteWiringService.throwOnClose = false }

    private fun startIntent() = Intent().setAction(WebRtcForegroundService.ACTION_START)
        .putExtra(WebRtcForegroundService.EXTRA_DEVICE_NAME, "TV")
        .putExtra(WebRtcForegroundService.EXTRA_DEVICE_HOST, "192.0.2.1")
        .putExtra(WebRtcForegroundService.EXTRA_APP_ID, "receiver")
        .putExtra(WebRtcForegroundService.EXTRA_RESULT_DATA, Intent())

    private fun TestScope.setup(muteSetting: Boolean): WebRtcForegroundService {
        MuteWiringService.dispatcher = StandardTestDispatcher(testScheduler)
        WebRtcConfigStore(ctx).muteWhileCasting = muteSetting
        val svc = Robolectric.buildService(MuteWiringService::class.java).create().get()
        runCurrent()
        return svc
    }

    private suspend fun TestScope.reachCasting(svc: WebRtcForegroundService) {
        svc.onStartCommand(startIntent(), 0, 1)
        runCurrent(); MuteWiringService.transport!!.ready(); advanceTimeBy(50); runCurrent()
        MuteWiringService.peer!!.connectionState.value = PeerConnectionState.CONNECTED
        runCurrent()
    }

    @Test fun `setting on mutes once the cast is actually established`() = runTest {
        val svc = setup(true)
        svc.onStartCommand(startIntent(), 0, 1)
        runCurrent()
        assertTrue("connecting must not silence the phone yet", vol.events.isEmpty())
        MuteWiringService.transport!!.ready(); advanceTimeBy(50); runCurrent()
        MuteWiringService.peer!!.connectionState.value = PeerConnectionState.CONNECTED
        runCurrent()
        assertEquals(listOf("mute"), vol.events)
        assertTrue(vol.muted)
    }

    @Test fun `setting off never touches the volume`() = runTest {
        val svc = setup(false)
        reachCasting(svc)
        svc.onStartCommand(Intent().setAction(WebRtcForegroundService.ACTION_STOP), 0, 2)
        runCurrent(); advanceTimeBy(600); runCurrent()
        assertTrue(vol.events.isEmpty())
        assertNull(store.marker)
    }

    @Test fun `stopCast restores`() = runTest {
        val svc = setup(true)
        reachCasting(svc)
        svc.onStartCommand(Intent().setAction(WebRtcForegroundService.ACTION_STOP), 0, 2)
        runCurrent(); advanceTimeBy(600); runCurrent()
        assertFalse(vol.muted)
        assertEquals(listOf("mute", "unmute"), vol.events)
        assertNull(store.marker)
    }

    @Test fun `terminal session restores`() = runTest {
        val svc = setup(true)
        reachCasting(svc)
        MuteWiringService.peer!!.connectionState.value = PeerConnectionState.FAILED
        runCurrent()
        assertFalse(vol.muted)
        assertNull(store.marker)
    }

    @Test fun `close throwing still restores`() = runTest {
        MuteWiringService.throwOnClose = true
        val svc = setup(true)
        reachCasting(svc)
        assertTrue(vol.muted)
        MuteWiringService.peer!!.connectionState.value = PeerConnectionState.FAILED
        runCurrent()
        assertFalse(vol.muted)
        assertNull(store.marker)
    }

    @Test fun `late connected event after stop cannot leave the phone muted`() = runTest {
        val svc = setup(true)
        svc.onStartCommand(startIntent(), 0, 1)
        runCurrent(); MuteWiringService.transport!!.ready(); advanceTimeBy(50); runCurrent()
        svc.onStartCommand(Intent().setAction(WebRtcForegroundService.ACTION_STOP), 0, 2)
        MuteWiringService.peer!!.connectionState.value = PeerConnectionState.CONNECTED
        runCurrent(); advanceTimeBy(600); runCurrent()
        assertFalse(vol.muted)
        assertNull(store.marker)
    }

    @Test fun `onCreate recovers a stale mute`() = runTest {
        store.marker = MuteMarker("old", 1L)
        vol.muted = true
        setup(false)
        assertFalse(vol.muted)
        assertNull(store.marker)
    }
}
