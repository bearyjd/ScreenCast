package io.github.ddagunts.screencast.ui

import io.github.ddagunts.screencast.cast.*
import io.github.ddagunts.screencast.media.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

class MediaHandoffControllerTest {
    private val device = CastDevice("TV", "192.168.1.2", 8009)
    private val media = SharedMedia("https://example/movie.mp4?token=secret", "video/mp4")
    private open class Fake : HandoffSession {
        override val state = MutableStateFlow<CastState>(CastState.Idle)
        override val playerState = MutableStateFlow("IDLE")
        override val mediaRejected = MutableStateFlow(false)
        override val receiverApps = MutableStateFlow<Set<String>?>(null)
        var starts = 0; var closes = 0; var stops = 0; var releases = 0; var loaded: SharedMedia? = null
        override fun release() { releases++ }
        override suspend fun start(media: SharedMedia) { starts++; loaded = media }
        open override suspend fun pause() {}
        override suspend fun play() {}
        override suspend fun stop() { stops++ }
        override fun close() { closes++ }
    }
    @Test fun previewAndSelectionRequireExplicitCastAndCannotReplay() = runBlocking {
        val fake = Fake(); var created = 0
        val controller = MediaHandoffController(this, { created++; fake })
        controller.preview(SharedMediaResult(media))
        controller.cast(); yield()
        assertEquals(0, created)
        controller.select(device); yield()
        assertEquals(0, created)
        controller.cast(); controller.cast(); yield()
        assertEquals(1, created)
        assertEquals(1, fake.starts)
        assertEquals(media, fake.loaded)
        controller.disconnect()
        assertEquals(1, fake.closes)
        assertEquals(0, fake.stops)
    }
    @Test fun busyReceiverNeverCreatesSession() = runBlocking {
        var created = 0
        val controller = MediaHandoffController(this, { created++; Fake() }, { true })
        controller.preview(SharedMediaResult(media)); controller.select(device); controller.cast(); yield()
        assertEquals(0, created)
        assertNotNull(controller.state.value.error)
        controller.disconnect()
    }
    @Test fun rejectedSharePreservesControllerAndStopIsExplicit() = runBlocking {
        val fake = Fake()
        val controller = MediaHandoffController(this, { fake })
        controller.preview(SharedMediaResult(media)); controller.select(device); controller.cast(); yield()
        controller.preview(SharedMediaResult(error = "Unsupported"))
        assertEquals(0, fake.closes)
        controller.stop(); yield()
        assertEquals(1, fake.stops)
        assertEquals(1, fake.closes)
        controller.disconnect()
        assertEquals(1, fake.closes)
    }
    @Test fun receiverFailureClosesOnceAndRetainsSafeError() = runBlocking {
        val fake = Fake()
        val controller = MediaHandoffController(this, { fake })
        controller.preview(SharedMediaResult(media)); controller.select(device); controller.cast(); yield()
        fake.state.value = CastState.Error("receiver rejected media"); yield(); yield()
        assertEquals(1, fake.closes)
        assertEquals("receiver rejected media", controller.state.value.error)
        controller.disconnect()
    }
    @Test fun staleControlFailureCannotCloseReplacementSession() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val old = object : Fake() { override suspend fun pause() { release.await(); error("old connection failed") } }
        val replacement = Fake()
        var count = 0
        val controller = MediaHandoffController(this, { if (count++ == 0) old else replacement })
        controller.preview(SharedMediaResult(media)); controller.select(device); controller.cast(); yield()
        controller.pause(); yield()
        controller.select(device); controller.cast(); yield()
        release.complete(Unit); yield(); yield()
        assertEquals(0, replacement.closes)
        assertNull(controller.state.value.error)
        controller.disconnect()
    }
    @Test fun disconnectKeepsLocalServingButEveryEndingPathReleasesIt() = runBlocking {
        val kept = Fake()
        var controller = MediaHandoffController(this, { kept })
        controller.preview(SharedMediaResult(media)); controller.select(device); controller.cast(); yield()
        controller.disconnect()
        assertEquals(0, kept.releases)
        val explicit = Fake()
        controller = MediaHandoffController(this, { explicit })
        controller.preview(SharedMediaResult(media)); controller.select(device); controller.cast(); yield()
        controller.end(); controller.end()
        assertEquals(1, explicit.releases)
        val stopped = Fake()
        controller = MediaHandoffController(this, { stopped })
        controller.preview(SharedMediaResult(media)); controller.select(device); controller.cast(); yield()
        controller.stop(); yield()
        assertEquals(1, stopped.releases)
        val failed = Fake()
        controller = MediaHandoffController(this, { failed })
        controller.preview(SharedMediaResult(media)); controller.select(device); controller.cast(); yield()
        failed.mediaRejected.value = true; failed.state.value = CastState.Error("x"); yield(); yield()
        assertEquals(1, failed.releases)
    }
    @Test fun startFailureReleasesAndShowsSpecificHandoffMessage() = runBlocking {
        val failing = object : Fake() { override suspend fun start(media: SharedMedia) { throw HandoffException("Connect to Wi-Fi to cast a file from this phone.") } }
        val controller = MediaHandoffController(this, { failing })
        controller.preview(SharedMediaResult(media)); controller.select(device); controller.cast(); yield(); yield()
        assertEquals("Connect to Wi-Fi to cast a file from this phone.", controller.state.value.error)
        assertEquals(1, failing.releases)
        assertNull(controller.state.value.active)
    }
    private val localMedia = SharedMedia("", "video/mp4", "Clip.mp4")
    private fun localCast(fake: Fake, body: suspend (MediaHandoffController) -> Unit) = runBlocking {
        val c = MediaHandoffController(this, { fake })
        try {
            c.preview(SharedMediaResult(localMedia)); c.select(device); c.cast(); yield()
            body(c)
        } finally { c.disconnect() }
    }
    @Test fun controlChannelLossKeepsServingButMediaRejectionEndsIt() {
        val drop = Fake()
        localCast(drop) { c ->
            drop.state.value = CastState.Error("receiver heartbeat timeout"); yield(); yield()
            assertEquals(0, drop.releases); assertEquals(1, drop.closes)
            assertEquals("receiver heartbeat timeout", c.state.value.error)
            assertNull(c.state.value.active)
        }
    }
    @Test fun loadFailureEndsServing() {
        val rejected = Fake()
        localCast(rejected) {
            rejected.mediaRejected.value = true; rejected.state.value = CastState.Error("Receiver rejected media"); yield(); yield()
            assertEquals(1, rejected.releases)
        }
    }
    @Test fun receiverLeavingDefaultMediaReceiverReleasesOnce() {
        val f = Fake()
        localCast(f) {
            f.receiverApps.value = emptySet(); yield()
            f.receiverApps.value = setOf("E8C28D3C"); yield()   // backdrop before our launch: ignored
            assertEquals(0, f.releases)
            f.receiverApps.value = setOf(DEFAULT_MEDIA_RECEIVER); yield()
            assertEquals(0, f.releases)
            f.receiverApps.value = setOf("ABCD1234"); yield()
            assertEquals(1, f.releases)
            f.receiverApps.value = emptySet(); yield()
            assertEquals(1, f.releases)
        }
    }
    @Test fun receiverAppDisappearingReleases() {
        val f = Fake()
        localCast(f) {
            f.receiverApps.value = setOf(DEFAULT_MEDIA_RECEIVER); yield()
            f.receiverApps.value = emptySet(); yield()
            assertEquals(1, f.releases)
        }
    }
    @Test fun stableDefaultMediaReceiverNeverReleases() {
        val f = Fake()
        localCast(f) {
            f.receiverApps.value = setOf(DEFAULT_MEDIA_RECEIVER); yield()
            f.receiverApps.value = setOf(DEFAULT_MEDIA_RECEIVER, "X"); yield()
            assertEquals(0, f.releases)
        }
    }
    @Test fun cancelDuringStartReleasesAndNeverLeavesActiveState() {
        val hang = CompletableDeferred<Unit>()
        val f = object : Fake() { override suspend fun start(media: SharedMedia) { hang.await() } }
        localCast(f) { c ->
            assertTrue(c.state.value.activeLocal)
            c.end(); yield()
            assertEquals(1, f.releases); assertNull(c.state.value.active); assertFalse(c.state.value.activeLocal)
            hang.complete(Unit)
        }
    }
    @Test fun urlSessionIsNotMarkedLocal() = runBlocking {
        val c = MediaHandoffController(this, { Fake() })
        c.preview(SharedMediaResult(media)); c.select(device); c.cast(); yield()
        assertFalse(c.state.value.activeLocal); c.disconnect()
    }
}
