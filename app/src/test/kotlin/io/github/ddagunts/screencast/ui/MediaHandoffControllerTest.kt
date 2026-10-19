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
        var starts = 0; var closes = 0; var stops = 0; var loaded: SharedMedia? = null
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
}
