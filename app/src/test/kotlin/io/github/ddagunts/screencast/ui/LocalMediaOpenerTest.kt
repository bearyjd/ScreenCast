// Fork modification by bearyjd: opening a shared content URI once, at accept time, with type and seekability checks.
package io.github.ddagunts.screencast.ui

import android.net.Uri
import android.os.ParcelFileDescriptor
import io.github.ddagunts.screencast.media.*
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocalMediaOpenerTest {
    private val uri = Uri.parse("content://media/external/video/1")
    private val file: File = File.createTempFile("opener", ".mp4").apply { writeBytes(ByteArray(4096) { it.toByte() }.also { b -> "\u0000\u0000\u0000\u0018ftyp".forEachIndexed { i, c -> b[i] = c.code.toByte() } }); deleteOnExit() }
    private class Backend(val type: String?, val name: String?, val fd: () -> ParcelFileDescriptor?) : LocalUriBackend {
        var opens = 0
        override fun type(uri: Uri) = type
        override fun displayName(uri: Uri) = name
        override fun openReadOnly(uri: Uri): ParcelFileDescriptor? { opens++; return fd() }
    }
    private fun open(b: LocalUriBackend, intentType: String? = "video/mp4") = LocalMediaOpener.open(b, LocalShareRequest(uri, intentType))
    private fun pfd() = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)

    @Test fun regularFileIsAcceptedWithSanitisedNameAndOpenedOnce() {
        val b = Backend("video/mp4", "Clip‮.mp4") { pfd() }
        val r = open(b)
        assertNull(r.error)
        assertEquals("video/mp4", r.contentType)
        assertEquals("Clip.mp4", r.displayName)
        assertEquals(1, b.opens)
        assertNotNull(r.handle)
        r.handle!!.close()
    }
    // Robolectric's ParcelFileDescriptor.dup() shares one fd (closing a dup closes the original), so dup independence
    // is NOT unit-testable here; it relies on real dup(2) on device and is listed as unverified in the evidence file.
    @Test fun handleExposesVerifiedSourceOfFullLengthBeforeProbeIsClosed() {
        val pfd = pfd()
        val source = PfdMediaHandle(pfd).openSource()!!
        assertEquals(4096, source.length)
        val buf = java.nio.ByteBuffer.allocate(8)
        assertEquals(8, source.readAt(buf, 100)); assertEquals(100.toByte(), buf.get(0))
        source.close()
    }
    @Test fun disallowedTypeIsRejectedBeforeOpening() {
        val b = Backend("video/webm", null) { pfd() }
        val r = open(b)
        assertNotNull(r.error); assertNull(r.handle); assertEquals(0, b.opens)
    }
    @Test fun nullResolverTypeFallsBackToAllowedIntentTypeOnly() {
        assertNull(open(Backend(null, null) { pfd() }, "audio/mp4").error)
        assertNotNull(open(Backend(null, null) { pfd() }, "video/*").error)
    }
    @Test fun securityOrIoFailuresAreCleanErrors() {
        assertNotNull(open(Backend("video/mp4", null) { throw SecurityException("revoked") }).error)
        assertNotNull(open(Backend("video/mp4", null) { throw java.io.FileNotFoundException() }).error)
        assertNotNull(open(Backend("video/mp4", null) { null }).error)
    }
    @Test fun nonSeekableStreamGetsDownloadFirstMessage() {
        val pipe = ParcelFileDescriptor.createPipe()
        // Real Android reports statSize -1 for non-regular fds (Robolectric's pipe does not), so model that explicitly.
        val nonRegular = object : ParcelFileDescriptor(pipe[0]) { override fun getStatSize(): Long = -1L }
        val r = open(Backend("video/mp4", null) { nonRegular })
        assertEquals("This item isn't stored on the phone yet. Download it first.", r.error)
        assertNull(r.handle)
        runCatching { pipe[1].close() }
    }
    @Test fun emptyFileIsRejected() {
        val empty = File.createTempFile("empty", ".mp4").apply { deleteOnExit() }
        val r = open(Backend("video/mp4", null) { ParcelFileDescriptor.open(empty, ParcelFileDescriptor.MODE_READ_ONLY) })
        assertEquals(LocalMediaOpener.EMPTY, r.error)
    }
    @Test fun contentThatDoesNotMatchDeclaredTypeIsRejected() {
        val html = File.createTempFile("fake", ".mp4").apply { writeText("<html>not a video at all</html>"); deleteOnExit() }
        val r = open(Backend("video/mp4", null) { ParcelFileDescriptor.open(html, ParcelFileDescriptor.MODE_READ_ONLY) })
        assertEquals(LocalMediaOpener.NOT_MEDIA, r.error); assertNull(r.handle)
    }
    @Test fun mp3ByIdTagOrFrameSyncPasses() {
        val f = File.createTempFile("aud", ".mp3").apply { writeBytes(byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte()) + ByteArray(100)); deleteOnExit() }
        assertNull(open(Backend("audio/mpeg", null) { ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY) }, "audio/mpeg").error)
    }
    @Test fun probeFailureFromRuntimeErrorsIsAnErrorNotACrash() {
        val pfd = pfd(); pfd.close()
        assertNull(PfdMediaHandle(pfd).openSource())
    }
}
