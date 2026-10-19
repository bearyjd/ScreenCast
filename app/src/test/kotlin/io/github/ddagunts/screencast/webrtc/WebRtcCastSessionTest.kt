// Fork modification by bearyjd: production-session lifecycle regression tests.
package io.github.ddagunts.screencast.webrtc

import io.github.ddagunts.screencast.WebRtcForegroundService
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowPowerManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import io.github.ddagunts.screencast.cast.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection.PeerConnectionState

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WebRtcCastSessionTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val config = WebRtcSessionConfig(VideoPreset.HD_30, 2_000_000, false)
    private class Transport : SessionTransport {
        override val incoming = MutableSharedFlow<CastMessage>(extraBufferCapacity = 8)
        var closeCount = 0
        var blockSends = false
        var connectFails = false
        var sendFails = false
        var disconnectOnConnect = false
        var disconnectOnSend = 0
        var sends = 0
        val reader = Job()
        var onClose: ((Throwable?) -> Unit)? = null
        override fun connect(onClose: (Throwable?) -> Unit): Job { this.onClose = onClose; if (connectFails) error("connect failed"); if (disconnectOnConnect) onClose(IllegalStateException("disconnected")); return reader }
        override suspend fun send(message: CastMessage) { if (++sends == disconnectOnSend) onClose?.invoke(IllegalStateException("disconnected")); if (sendFails) error("send failed"); if (blockSends) awaitCancellation() }
        override fun close() { closeCount++ }
        suspend fun ready() { incoming.emit(CastMessage("receiver-0", SENDER_ID, CastNs.RECEIVER,
            """{"type":"RECEIVER_STATUS","status":{"applications":[{"transportId":"transport","sessionId":"session"}]}}""")) }
    }
    private class Peer : SessionPeer {
        override val connectionState = MutableStateFlow(PeerConnectionState.NEW)
        var closeCount = 0
        var buildFails = false
        var offerFails = false
        var answerFails = false
        var onBuild: () -> Unit = {}
        var screenSources = 0
        override fun build() { onBuild(); if (buildFails) error("build failed") }
        override fun addScreenSource(data: Intent, onProjectionStopped: () -> Unit) { screenSources++ }
        override suspend fun createOffer(): String { if (offerFails) error("offer failed"); return "offer" }
        override suspend fun setRemoteAnswer(sdp: String) { if (answerFails) error("answer failed") }
        override fun addRemoteIceCandidate(candidate: IceCandidate) {}
        override fun close() { closeCount++ }
    }

    @Test fun `stop releases capture immediately even when network sends never complete`() = runTest {
        val transport = Transport()
        val peer = Peer()
        val session = WebRtcCastSession(context, CastDevice("TV", "192.0.2.1", 8009), config,
            transport, { peer }, StandardTestDispatcher(testScheduler))
        val start = launch { session.startCast("receiver", Intent()) {} }
        runCurrent()
        transport.ready()
        advanceTimeBy(50)
        runCurrent()
        start.join()
        transport.blockSends = true
        session.stop()
        assertEquals("capture must stop before any network acknowledgement", 1, peer.closeCount)
        assertEquals(WebRtcState.Idle, session.state.value)
        session.close()
    }
    @Test fun `ICE failure releases all resources exactly once and retains its error`() = runTest {
        val transport = Transport()
        val peer = Peer()
        val session = WebRtcCastSession(context, CastDevice("TV", "192.0.2.1", 8009), config,
            transport, { peer }, StandardTestDispatcher(testScheduler))
        val start = launch { session.startCast("receiver", Intent()) {} }
        runCurrent(); transport.ready(); advanceTimeBy(50); runCurrent(); start.join()
        peer.connectionState.value = PeerConnectionState.FAILED
        runCurrent()
        assertEquals(1, peer.closeCount)
        assertEquals(1, transport.closeCount)
        val failure = session.state.value
        assertTrue(failure is WebRtcState.Error)
        session.close(); session.stop()
        assertEquals(failure, session.state.value)
        assertEquals(1, peer.closeCount)
        assertEquals(1, transport.closeCount)
    }

    @Test fun `every startup failure closes resources and preserves an actionable error`() = runTest {
        for (scenario in listOf("connect", "send", "timeout", "build", "offer")) {
            val transport = Transport().apply { connectFails = scenario == "connect"; sendFails = scenario == "send" }
            val peer = Peer().apply { buildFails = scenario == "build"; offerFails = scenario == "offer" }
            val session = WebRtcCastSession(context, CastDevice("TV", "192.0.2.1", 8009), config,
                transport, { peer }, StandardTestDispatcher(testScheduler))
            val start = launch { runCatching { session.startCast("receiver", Intent()) {} } }
            runCurrent()
            if (scenario != "timeout") transport.ready()
            advanceTimeBy(if (scenario == "timeout") 10_001 else 50)
            runCurrent(); start.join()
            assertEquals("$scenario must close transport", 1, transport.closeCount)
            assertTrue("$scenario must retain Error", session.state.value is WebRtcState.Error)
            assertEquals("$scenario closes allocated peer", if (scenario in listOf("build", "offer")) 1 else 0, peer.closeCount)
            session.close()
            assertTrue(session.state.value is WebRtcState.Error)
        }
    }

    @Test fun `answer and heartbeat failures release capture and keep Error`() = runTest {
        for (scenario in listOf("answer", "heartbeat")) {
            val transport = Transport()
            val peer = Peer().apply { answerFails = scenario == "answer" }
            val session = WebRtcCastSession(context, CastDevice("TV", "192.0.2.1", 8009), config,
                transport, { peer }, StandardTestDispatcher(testScheduler))
            val start = launch { session.startCast("receiver", Intent()) {} }
            runCurrent(); transport.ready(); advanceTimeBy(50); runCurrent(); start.join()
            if (scenario == "answer") {
                transport.incoming.emit(CastMessage("transport", SENDER_ID, WEBRTC_NAMESPACE,
                    """{"type":"ANSWER","sdp":"rejected"}"""))
            } else {
                transport.sendFails = true
                advanceTimeBy(5_000)
            }
            runCurrent()
            assertEquals("$scenario must stop capture", 1, peer.closeCount)
            assertEquals(1, transport.closeCount)
            assertTrue(session.state.value is WebRtcState.Error)
        }
    }

    @Test fun `unsupported audio is rejected before connecting or creating native resources`() = runTest {
        val transport = Transport()
        var peerCreated = false
        val session = WebRtcCastSession(context, CastDevice("TV", "192.0.2.1", 8009), config.copy(audioEnabled = true),
            transport, { peerCreated = true; Peer() }, StandardTestDispatcher(testScheduler))
        val start = launch { session.startCast("receiver", Intent()) {} }
        runCurrent()
        assertTrue("audio must fail before any network or projection work", session.state.value is WebRtcState.Error)
        assertFalse(peerCreated)
        assertNull(transport.onClose)
        start.join()
    }

    class TestService : WebRtcForegroundService() {
        override val workerDispatcher get() = dispatcher
        override fun createSession(device: CastDevice, config: WebRtcSessionConfig): WebRtcCastSession {
            creations++
            return WebRtcCastSession(this, device, config, Transport().apply { disconnectOnSend = 1 },
                { Peer() }, dispatcher)
        }
        companion object {
            lateinit var dispatcher: CoroutineDispatcher
            var creations = 0
        }
    }

    @Test fun `terminal service failure releases foreground ownership and permits retry`() = runTest {
        TestService.dispatcher = StandardTestDispatcher(testScheduler)
        TestService.creations = 0
        val controller = Robolectric.buildService(TestService::class.java).create()
        val service = controller.get()
        val intent = Intent().setAction(WebRtcForegroundService.ACTION_START)
            .putExtra(WebRtcForegroundService.EXTRA_DEVICE_NAME, "TV")
            .putExtra(WebRtcForegroundService.EXTRA_DEVICE_HOST, "192.0.2.1")
            .putExtra(WebRtcForegroundService.EXTRA_APP_ID, "receiver")
            .putExtra(WebRtcForegroundService.EXTRA_RESULT_DATA, Intent())
        service.onStartCommand(intent, 0, 1)
        runCurrent()
        assertEquals("ERROR", WebRtcForegroundService.sessionFlow.value?.status)
        assertFalse("failure must release its wake lock", ShadowPowerManager.getLatestWakeLock().isHeld)
        assertTrue("failure must leave foreground", shadowOf(service).isForegroundStopped)
        service.onStartCommand(intent, 0, 2)
        runCurrent()
        assertEquals(2, TestService.creations)
        controller.destroy()
        assertEquals("ERROR", WebRtcForegroundService.sessionFlow.value?.status)
    }

    @Test fun `disconnect during startup cannot overwrite Error or install capture afterward`() = runTest {
        for (stage in listOf("initialSend", "appConnect", "peerFactory", "peerBuild")) {
            val transport = Transport().apply {
                disconnectOnSend = when (stage) { "initialSend" -> 1; "appConnect" -> 3; else -> 0 }
            }
            val peer = Peer().apply {
                if (stage == "peerBuild") onBuild = { transport.onClose?.invoke(IllegalStateException("disconnected")) }
            }
            var peerCreated = false
            val session = WebRtcCastSession(context, CastDevice("TV", "192.0.2.1", 8009), config,
                transport, {
                    peerCreated = true
                    if (stage == "peerFactory") transport.onClose?.invoke(IllegalStateException("disconnected"))
                    peer
                }, StandardTestDispatcher(testScheduler))
            val start = launch { session.startCast("receiver", Intent()) {} }
            runCurrent()
            if (stage != "initialSend") { transport.ready(); advanceTimeBy(50); runCurrent() }
            start.join()
            assertTrue("$stage must retain terminal Error, actual=${session.state.value}", session.state.value is WebRtcState.Error)
            assertTrue("$stage must cancel reader", transport.reader.isCancelled)
            assertEquals("$stage cannot start capture after failure", 0, peer.screenSources)
            assertEquals(if (peerCreated) 1 else 0, peer.closeCount)
            transport.onClose?.invoke(IllegalStateException("late disconnect"))
            assertEquals(WebRtcState.Error("disconnected"), session.state.value)
        }
    }

    @Test fun `reader returned after synchronous connect failure is cancelled`() = runTest {
        val transport = Transport().apply { disconnectOnConnect = true }
        val session = WebRtcCastSession(context, CastDevice("TV", "192.0.2.1", 8009), config,
            transport, { error("must not create peer") }, StandardTestDispatcher(testScheduler))
        session.startCast("receiver", Intent()) {}
        assertTrue(session.state.value is WebRtcState.Error)
        assertTrue("late returned reader must be cancelled", transport.reader.isCancelled)
    }

    @Test fun `startup native operations dispose allocations made after a disconnect callback`() = runTest {
        for (stage in listOf("build", "source", "buildThrows", "sourceThrows", "sourceThread", "sourceCloseThrows")) {
            val transport = Transport()
            val peer = object : SessionPeer {
                override val connectionState = MutableStateFlow(PeerConnectionState.NEW)
                var allocated = false
                var disposed = false
                var closes = 0
                var offers = 0
                fun allocateAfterDisconnect() {
                    val disconnect = { transport.onClose?.invoke(IllegalStateException("native startup disconnected")); Unit }
                    if (stage == "sourceThread") {
                        val callback = Thread(disconnect).apply { start() }
                        callback.join(5_000)
                        assertFalse("native callback must not wait for the lifecycle monitor", callback.isAlive)
                    } else disconnect()
                    assertEquals("transport must close before the native call returns", 1, transport.closeCount)
                    allocated = true
                    if (stage.endsWith("Throws")) error("native initialization failed after allocation")
                }
                override fun build() { if (stage.startsWith("build")) allocateAfterDisconnect() }
                override fun addScreenSource(data: Intent, onProjectionStopped: () -> Unit) {
                    if (stage.startsWith("source")) allocateAfterDisconnect()
                }
                override suspend fun createOffer(): String { offers++; return "offer" }
                override suspend fun setRemoteAnswer(sdp: String) {}
                override fun addRemoteIceCandidate(candidate: IceCandidate) {}
                override fun close() {
                    if (disposed) return
                    disposed = true
                    closes++
                    allocated = false
                    if (stage == "sourceCloseThrows") error("native disposal failed")
                }
            }
            val session = WebRtcCastSession(context, CastDevice("TV", "192.0.2.1", 8009), config,
                transport, { peer }, StandardTestDispatcher(testScheduler))
            val start = launch { session.startCast("receiver", Intent()) {} }
            runCurrent(); transport.ready(); advanceTimeBy(50); runCurrent(); start.join()
            assertFalse("$stage leaked resources allocated after an early close", peer.allocated)
            assertEquals("$stage must dispose exactly once", 1, peer.closes)
            assertEquals("$stage must not create an offer after shutdown", 0, peer.offers)
            assertEquals(WebRtcState.Error("native startup disconnected"), session.state.value)
            session.stop(); session.close()
            assertEquals(1, peer.closes)
        }
    }

}
