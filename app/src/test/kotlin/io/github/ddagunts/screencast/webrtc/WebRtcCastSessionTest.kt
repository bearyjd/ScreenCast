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
import org.json.JSONObject
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
        var blockNonCancellable = false
        var connectFails = false
        var sendFails = false
        var disconnectOnConnect = false
        var disconnectOnSend = 0
        var sends = 0
        val sent = mutableListOf<CastMessage>()
        val events = mutableListOf<String>()
        val reader = Job()
        var onClose: ((Throwable?) -> Unit)? = null
        override fun connect(onClose: (Throwable?) -> Unit): Job { this.onClose = onClose; if (connectFails) error("connect failed"); if (disconnectOnConnect) onClose(IllegalStateException("disconnected")); return reader }
        override suspend fun send(message: CastMessage) { sent += message; events += "send"; if (++sends == disconnectOnSend) onClose?.invoke(IllegalStateException("disconnected")); if (sendFails) error("send failed"); if (blockSends) awaitCancellation(); if (blockNonCancellable) withContext(NonCancellable) { awaitCancellation() } }
        override fun close() { closeCount++; events += "close" }
        suspend fun ready() { incoming.emit(CastMessage("receiver-0", SENDER_ID, CastNs.RECEIVER,
            """{"type":"RECEIVER_STATUS","status":{"applications":[{"transportId":"transport","sessionId":"session"}]}}""")) }
    }
    private class Peer : SessionPeer {
        override val connectionState = MutableStateFlow(PeerConnectionState.NEW)
        override val codecMismatch = MutableStateFlow<String?>(null)
        var closeCount = 0
        var buildFails = false
        var offerFails = false
        var answerFails = false
        var onBuild: () -> Unit = {}
        var screenSources = 0
        var projectionStopped: (() -> Unit)? = null
        override fun build() { onBuild(); if (buildFails) error("build failed") }
        override fun addScreenSource(data: Intent, onProjectionStopped: () -> Unit) { screenSources++; projectionStopped = onProjectionStopped }
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

    @Test fun `peer codec mismatch reaches the session and stops after close`() = runTest {
        val transport = Transport()
        val peer = Peer()
        val session = WebRtcCastSession(context, CastDevice("TV", "192.0.2.1", 8009), config,
            transport, { peer }, StandardTestDispatcher(testScheduler))
        assertNull(session.codecMismatch.value)
        val start = launch { session.startCast("receiver", Intent()) {} }
        runCurrent(); transport.ready(); advanceTimeBy(50); runCurrent(); start.join()
        peer.codecMismatch.value = "Requested H.264 but the TV chose VP8"
        runCurrent()
        assertEquals("Requested H.264 but the TV chose VP8", session.codecMismatch.value)
        session.close()
        runCurrent()
        peer.codecMismatch.value = "late"
        runCurrent()
        assertEquals("Requested H.264 but the TV chose VP8", session.codecMismatch.value)
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

    @Test fun `audio requested is never a reason to refuse the cast`() = runTest {
        // Audio-active vs video-only is decided inside the peer; the session must still connect.
        for (granted in listOf(false, true)) {
            val transport = Transport()
            var peerCreated = false
            val cfg = config.copy(audioRequested = true, recordAudioGranted = granted)
            val session = WebRtcCastSession(context, CastDevice("TV", "192.0.2.1", 8009), cfg,
                transport, { peerCreated = true; Peer() }, StandardTestDispatcher(testScheduler))
            val start = launch { session.startCast("receiver", Intent()) {} }
            runCurrent()
            assertFalse("granted=$granted must not fail before connecting", session.state.value is WebRtcState.Error)
            assertNotNull(transport.onClose)
            transport.ready(); advanceTimeBy(50); runCurrent(); start.join()
            assertTrue("granted=$granted", peerCreated)
            assertFalse("granted=$granted", session.state.value is WebRtcState.Error)
            session.close()
        }
    }

    class TestService : WebRtcForegroundService() {
        override val workerDispatcher get() = dispatcher
        override fun createSession(device: CastDevice, config: WebRtcSessionConfig): WebRtcCastSession {
            creations++
            return WebRtcCastSession(this, device, config, transport ?: Transport().apply { disconnectOnSend = 1 },
                { Peer() }, dispatcher)
        }
        companion object {
            lateinit var dispatcher: CoroutineDispatcher
            var creations = 0
            internal var transport: SessionTransport? = null
        }
    }

    @Test fun `terminal service failure releases foreground ownership and permits retry`() = runTest {
        TestService.dispatcher = StandardTestDispatcher(testScheduler)
        TestService.creations = 0
        TestService.transport = null
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

    private fun Transport.farewells() = sent.filter {
        (it.namespace == WEBRTC_NAMESPACE && it.payloadUtf8.contains("\"BYE\"")) ||
            (it.namespace == CastNs.RECEIVER && it.payloadUtf8.contains("\"STOP\"")) ||
            (it.namespace == CastNs.CONNECTION && it.payloadUtf8.contains("\"CLOSE\""))
    }

    private suspend fun TestScope.casting(transport: Transport, peer: Peer = Peer(), onStopped: () -> Unit = {}): WebRtcCastSession {
        val session = WebRtcCastSession(context, CastDevice("TV", "192.0.2.1", 8009), config,
            transport, { peer }, StandardTestDispatcher(testScheduler))
        val start = launch { session.startCast("receiver", Intent(), onStopped) }
        runCurrent(); transport.ready(); advanceTimeBy(50); runCurrent()
        start.join()
        return session
    }

    @Test fun `stop sends BYE then CLOSE then closes the transport`() = runTest {
        val transport = Transport()
        val session = casting(transport)
        transport.sent.clear()
        session.stop()
        runCurrent()
        val f = transport.farewells()
        assertEquals(3, f.size)
        assertEquals(WEBRTC_NAMESPACE, f[0].namespace); assertEquals("transport", f[0].destinationId)
        assertEquals(CastNs.RECEIVER, f[1].namespace)
        assertEquals("session", JSONObject(f[1].payloadUtf8).getString("sessionId"))
        assertEquals(CastNs.CONNECTION, f[2].namespace)
        assertEquals(1, transport.closeCount)
        assertEquals(WebRtcState.Idle, session.state.value)
    }

    @Test fun `hung farewell send does not delay teardown and transport closes within timeout`() = runTest {
        val transport = Transport()
        val peer = Peer()
        val session = casting(transport, peer)
        transport.blockSends = true
        session.stop()
        assertEquals("peer disposed synchronously", 1, peer.closeCount)
        assertEquals(WebRtcState.Idle, session.state.value)
        assertTrue("reader cancelled synchronously", transport.reader.isCancelled)
        runCurrent()
        assertEquals("transport stays open while farewell pending", 0, transport.closeCount)
        advanceTimeBy(499); runCurrent()
        assertEquals(0, transport.closeCount)
        advanceTimeBy(2); runCurrent()
        assertEquals("transport must close once the timeout elapses", 1, transport.closeCount)
    }

    @Test fun `stop twice or stop then close sends farewell once`() = runTest {
        val transport = Transport()
        val session = casting(transport)
        transport.sent.clear()
        session.stop(); session.stop(); session.close()
        runCurrent(); advanceTimeBy(600); runCurrent()
        assertEquals(3, transport.farewells().size)
        assertEquals(1, transport.closeCount)
    }

    @Test fun `no farewell without transportId or after failure or plain close`() = runTest {
        val early = Transport()
        val s1 = WebRtcCastSession(context, CastDevice("TV", "192.0.2.1", 8009), config,
            early, { Peer() }, StandardTestDispatcher(testScheduler))
        val start = launch { s1.startCast("receiver", Intent()) {} }
        runCurrent()
        s1.stop()
        runCurrent(); advanceTimeBy(600); runCurrent()
        assertTrue("no transportId yet", early.farewells().isEmpty())
        assertEquals(1, early.closeCount)
        start.join()

        val failed = Transport(); val peer = Peer()
        val s2 = casting(failed, peer)
        peer.connectionState.value = PeerConnectionState.FAILED
        runCurrent()
        s2.stop()
        runCurrent(); advanceTimeBy(600); runCurrent()
        assertTrue("no farewell after failure", failed.farewells().isEmpty())

        val plain = Transport()
        val s3 = casting(plain)
        s3.close()
        runCurrent(); advanceTimeBy(600); runCurrent()
        assertTrue("plain close sends no farewell", plain.farewells().isEmpty())
        assertEquals(1, plain.closeCount)
    }

    @Test fun `farewell completes before transport close`() = runTest {
        val transport = Transport()
        val session = casting(transport)
        transport.events.clear()
        session.stop()
        runCurrent()
        assertEquals(listOf("send", "send", "send", "close"), transport.events)
    }

    @Test fun `projection stop callback sends farewell and notifies caller`() = runTest {
        val transport = Transport(); val peer = Peer()
        var notified = 0
        val session = casting(transport, peer) { notified++ }
        transport.sent.clear()
        peer.projectionStopped!!.invoke()
        runCurrent()
        assertEquals(1, notified)
        assertEquals(3, transport.farewells().size)
        assertEquals(1, transport.closeCount)
    }

    @Test fun `service stop sends farewell despite job cancellation`() = runTest {
        TestService.dispatcher = StandardTestDispatcher(testScheduler)
        val transport = Transport()
        TestService.transport = transport
        val controller = Robolectric.buildService(TestService::class.java).create()
        val service = controller.get()
        val start = Intent().setAction(WebRtcForegroundService.ACTION_START)
            .putExtra(WebRtcForegroundService.EXTRA_DEVICE_NAME, "TV")
            .putExtra(WebRtcForegroundService.EXTRA_DEVICE_HOST, "192.0.2.1")
            .putExtra(WebRtcForegroundService.EXTRA_APP_ID, "receiver")
            .putExtra(WebRtcForegroundService.EXTRA_RESULT_DATA, Intent())
        service.onStartCommand(start, 0, 1)
        // Stop while startup is still waiting on the launch loop, so job cancellation races stop().
        runCurrent(); transport.ready(); runCurrent()
        transport.sent.clear()
        service.onStartCommand(Intent().setAction(WebRtcForegroundService.ACTION_STOP), 0, 2)
        runCurrent(); advanceTimeBy(600); runCurrent()
        assertEquals(3, transport.farewells().size)
        assertEquals(1, transport.closeCount)
        TestService.transport = null
        controller.destroy()
    }

    @Test fun `receiver initiated BYE and CLOSE send no farewell back`() = runTest {
        for (msg in listOf(
            CastMessage("transport", SENDER_ID, WEBRTC_NAMESPACE, """{"type":"BYE"}"""),
            CastMessage("transport", SENDER_ID, CastNs.CONNECTION, """{"type":"CLOSE"}"""),
        )) {
            val transport = Transport()
            val session = casting(transport)
            transport.sent.clear()
            transport.incoming.emit(msg)
            runCurrent(); advanceTimeBy(600); runCurrent()
            assertTrue(transport.farewells().isEmpty())
            assertEquals(1, transport.closeCount)
            session.stop(); runCurrent(); advanceTimeBy(600); runCurrent()
            assertTrue(transport.farewells().isEmpty())
        }
    }

    @Test fun `farewell send failure still closes the transport`() = runTest {
        val transport = Transport()
        val session = casting(transport)
        transport.sendFails = true
        session.stop()
        runCurrent()
        assertEquals(1, transport.closeCount)
    }

    @Test fun `non cancellable blocked send still closes transport at the deadline`() = runTest {
        val transport = Transport()
        val session = casting(transport)
        transport.blockNonCancellable = true
        session.stop()
        runCurrent()
        assertEquals(0, transport.closeCount)
        advanceTimeBy(501); runCurrent()
        assertEquals(1, transport.closeCount)
    }

    @Test fun `farewell skips STOP without sessionId and still sends STOP with only sessionId`() = runTest {
        for (onlyTransport in listOf(true, false)) {
            val transport = Transport()
            val session = WebRtcCastSession(context, CastDevice("TV", "192.0.2.1", 8009), config,
                transport, { Peer() }, StandardTestDispatcher(testScheduler))
            val start = launch { session.startCast("receiver", Intent()) {} }
            runCurrent()
            val app = if (onlyTransport) """"transportId":"transport"""" else """"sessionId":"session""""
            transport.incoming.emit(CastMessage("receiver-0", SENDER_ID, CastNs.RECEIVER,
                """{"type":"RECEIVER_STATUS","status":{"applications":[{$app}]}}"""))
            runCurrent()
            transport.sent.clear()
            session.stop()
            runCurrent(); advanceTimeBy(600); runCurrent()
            val f = transport.farewells()
            if (onlyTransport) {
                assertEquals(listOf(WEBRTC_NAMESPACE, CastNs.CONNECTION), f.map { it.namespace })
            } else {
                assertEquals(listOf(CastNs.RECEIVER, CastNs.CONNECTION), f.map { it.namespace })
            }
            assertEquals(1, transport.closeCount)
            start.join()
        }
    }
}
