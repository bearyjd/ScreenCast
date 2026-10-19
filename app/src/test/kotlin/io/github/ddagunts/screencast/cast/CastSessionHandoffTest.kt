package io.github.ddagunts.screencast.cast

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CastSessionHandoffTest {
    private class Transport : CastTransport {
        override val incoming = MutableSharedFlow<CastMessage>(extraBufferCapacity = 20)
        val sent = java.util.concurrent.CopyOnWriteArrayList<CastMessage>()
        var closes = 0
        override fun connect(onClose: (Throwable?) -> Unit) = Job()
        override suspend fun send(msg: CastMessage) { synchronized(sent) { sent.add(msg) } }
        override fun close() { closes++ }
        suspend fun receiver(apps: String) { incoming.emit(CastMessage("receiver-0", SENDER_ID, CastNs.RECEIVER,
            """{"type":"RECEIVER_STATUS","status":{"applications":$apps}}""")) }
    }
    @Test fun wrongAppDoesNotTriggerLoadAndMatchingAppIsSelected() = runBlocking {
        val transport = Transport()
        val session = CastSession(CastDevice("TV", "192.168.1.2", 8009), transport)
        val start = launch { session.startCast(DEFAULT_MEDIA_RECEIVER, "https://example/a.mp4", "video/mp4") }
        withTimeout(3000) { while (transport.sent.none { JSONObject(it.payloadUtf8).optString("type") == "LAUNCH" }) delay(10) }
        transport.receiver("""[{"appId":"OTHER","transportId":"wrong","sessionId":"wrong"}]""")
        delay(120)
        assertFalse(transport.sent.any { JSONObject(it.payloadUtf8).optString("type") == "LOAD" })
        transport.receiver("""[{"appId":"OTHER","transportId":"wrong","sessionId":"wrong"},{"appId":"CC1AD845","transportId":"right","sessionId":"right-session"}]""")
        withTimeout(3000) { start.join() }
        assertEquals("right", transport.sent.last { JSONObject(it.payloadUtf8).optString("type") == "LOAD" }.destinationId)
        assertTrue(session.state.value is CastState.Connecting)
        session.close()
    }
    @Test fun closeRetainsFailure() {
        val broken = object : CastTransport {
            override val incoming = MutableSharedFlow<CastMessage>()
            var closes = 0
            override fun connect(onClose: (Throwable?) -> Unit): Job = error("secret URL must not escape")
            override suspend fun send(msg: CastMessage) {}
            override fun close() { closes++ }
        }
        val session = CastSession(CastDevice("TV", "192.168.1.2", 8009), broken)
        runBlocking { session.startCast(DEFAULT_MEDIA_RECEIVER, "https://example/a.mp4", "video/mp4") }
        session.close()
        assertTrue(session.state.value is CastState.Error)
        assertFalse((session.state.value as CastState.Error).message.contains("secret"))
        assertTrue(broken.closes > 0)
    }
    @Test fun matchingLaunchErrorFailsImmediatelyAndLogsNeverContainUrl() = runBlocking {
        val transport = Transport()
        val session = CastSession(CastDevice("TV", "192.168.1.2", 8009), transport)
        val start = launch { session.startCast(DEFAULT_MEDIA_RECEIVER, "https://example/a.mp4?token=secret", "video/mp4") }
        withTimeout(3000) { while (transport.sent.none { JSONObject(it.payloadUtf8).optString("type") == "LAUNCH" }) delay(10) }
        val launch = transport.sent.first { JSONObject(it.payloadUtf8).optString("type") == "LAUNCH" }
        val id = JSONObject(launch.payloadUtf8).getInt("requestId")
        transport.incoming.emit(CastMessage("receiver-0", SENDER_ID, CastNs.RECEIVER,
            """{"type":"LAUNCH_ERROR","requestId":$id,"reason":"secret https://example/a.mp4"}"""))
        delay(100)
        try {
            assertTrue(session.state.value is CastState.Error)
            assertTrue(org.robolectric.shadows.ShadowLog.getLogs().none { it.msg.contains("secret") || it.msg.contains("https://") })
        } finally { start.cancelAndJoin(); session.close() }
    }
    @Test fun receiverCloseIsTerminalAndRetainsError() = runBlocking {
        val transport = Transport()
        val session = CastSession(CastDevice("TV", "192.168.1.2", 8009), transport)
        val start = launch { session.startCast(DEFAULT_MEDIA_RECEIVER, "https://example/a.mp4", "video/mp4") }
        withTimeout(3000) { while (transport.sent.none { JSONObject(it.payloadUtf8).optString("type") == "LAUNCH" }) delay(10) }
        transport.receiver("""[{"appId":"CC1AD845","transportId":"right","sessionId":"session"}]""")
        withTimeout(3000) { start.join() }
        transport.incoming.emit(CastMessage("right", SENDER_ID, CastNs.CONNECTION, """{"type":"CLOSE"}"""))
        delay(100)
        try { assertTrue(session.state.value is CastState.Error) } finally { session.close() }
    }
    @Test fun onlyCorrelatedMediaAcknowledgesBufferedLoadAndStopTargetsMediaSession() = runBlocking {
        val transport = Transport()
        val session = CastSession(CastDevice("TV", "192.168.1.2", 8009), transport)
        val start = launch { session.startCast(DEFAULT_MEDIA_RECEIVER, "https://example/a.mp4?secret=token", "video/mp4", streamType = "BUFFERED") }
        withTimeout(3000) { while (transport.sent.none { JSONObject(it.payloadUtf8).optString("type") == "LAUNCH" }) delay(10) }
        transport.receiver("""[{"appId":"CC1AD845","transportId":"right","sessionId":"session"}]""")
        withTimeout(3000) { start.join() }
        val load = JSONObject(transport.sent.last { JSONObject(it.payloadUtf8).optString("type") == "LOAD" }.payloadUtf8)
        assertEquals("BUFFERED", load.getJSONObject("media").getString("streamType"))
        val id = load.getInt("requestId")
        suspend fun status(source: String, request: Int) { transport.incoming.emit(CastMessage(source, SENDER_ID, CastNs.MEDIA,
            """{"type":"MEDIA_STATUS","requestId":$request,"status":[{"mediaSessionId":7,"playerState":"PLAYING"}]}""")) }
        status("wrong", id); delay(50)
        assertTrue(session.state.value is CastState.Connecting)
        status("right", id + 1000); delay(50)
        assertTrue(session.state.value is CastState.Connecting)
        status("right", id)
        withTimeout(3000) { while (session.state.value !is CastState.Casting) delay(10) }
        session.stopPlayback()
        val stop = transport.sent.last()
        assertEquals("right", stop.destinationId)
        assertEquals("STOP", JSONObject(stop.payloadUtf8).getString("type"))
        assertEquals(7, JSONObject(stop.payloadUtf8).getInt("mediaSessionId"))
        assertEquals(1, transport.closes)
    }
}
