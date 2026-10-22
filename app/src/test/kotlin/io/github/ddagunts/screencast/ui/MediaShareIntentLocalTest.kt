// Fork modification by bearyjd: local EXTRA_STREAM classification (content:// only, single item).
package io.github.ddagunts.screencast.ui

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MediaShareIntentLocalTest {
    private fun send(type: String, stream: Any?) = Intent(Intent.ACTION_SEND).setType(type).apply {
        if (stream != null) putExtra(Intent.EXTRA_STREAM, stream as android.os.Parcelable)
    }
    @Test fun contentUriIsALocalRequestAndKeepsDeclaredType() {
        val r = MediaShareIntent.local(send("video/mp4", Uri.parse("content://media/external/video/1")))
        assertNull(r.error)
        assertEquals("content://media/external/video/1", r.request?.uri.toString())
        assertEquals("video/mp4", r.request?.intentType)
    }
    @Test fun noStreamOrRemoteStreamFallsThroughToUrlPath() {
        for (i in listOf(send("text/plain", null), send("video/mp4", Uri.parse("https://example/a.mp4")))) {
            val r = MediaShareIntent.local(i); assertNull(r.request); assertNull(r.error)
        }
    }
    @Test fun otherSchemesAreRejected() {
        for (u in listOf("file:///sdcard/a.mp4", "android.resource://x/1", "ftp://h/a.mp4", "content:", "/sdcard/a.mp4")) {
            val r = MediaShareIntent.local(send("video/mp4", Uri.parse(u)))
            assertNull(u, r.request); assertNotNull(u, r.error)
        }
    }
    @Test fun multipleOrAmbiguousItemsAreRejected() {
        val multi = Intent(Intent.ACTION_SEND_MULTIPLE).setType("video/mp4")
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(Uri.parse("content://m/1"), Uri.parse("content://m/2")))
        assertNull(MediaShareIntent.local(multi).request)
        val clip = send("video/mp4", Uri.parse("content://m/1")).apply {
            clipData = ClipData.newRawUri("a", Uri.parse("content://m/1")).apply { addItem(ClipData.Item(Uri.parse("content://m/2"))) }
        }
        assertNotNull(MediaShareIntent.local(clip).error)
        val differing = send("video/mp4", Uri.parse("content://m/1")).apply { clipData = ClipData.newRawUri("a", Uri.parse("content://m/2")) }
        assertNotNull(MediaShareIntent.local(differing).error)
        val withText = send("video/mp4", Uri.parse("content://m/1")).putExtra(Intent.EXTRA_TEXT, "https://example/a.mp4")
        assertNotNull(MediaShareIntent.local(withText).error)
        val withData = send("video/mp4", Uri.parse("content://m/1")).setData(Uri.parse("content://m/1"))
        assertNull(MediaShareIntent.local(withData).request)
        assertNull(MediaShareIntent.local(Intent(Intent.ACTION_VIEW).putExtra(Intent.EXTRA_STREAM, Uri.parse("content://m/1"))).request)
    }
    @Test fun matchingClipDataIsAccepted() {
        val i = send("video/mp4", Uri.parse("content://m/1")).apply { clipData = ClipData.newRawUri("a", Uri.parse("content://m/1")) }
        assertNotNull(MediaShareIntent.local(i).request)
    }
    @Test fun malformedStreamExtraIsRejected() {
        assertNotNull(MediaShareIntent.local(Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM, "content://m/1")).error)
    }
    @Test fun oversizedUriIsRejected() {
        assertNotNull(MediaShareIntent.local(send("video/mp4", Uri.parse("content://m/" + "a".repeat(5000)))).error)
    }
    @Test fun sendMultipleWithoutStreamFallsThrough() {
        val i = Intent(Intent.ACTION_SEND_MULTIPLE).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "https://example/a.mp4")
        val r = MediaShareIntent.local(i); assertNull(r.request); assertNull(r.error)
    }
    @Test fun clipItemsWithTextHtmlOrIntentAreRejected() {
        val uri = Uri.parse("content://m/1")
        val text = send("video/mp4", uri).apply { clipData = ClipData("a", arrayOf("video/mp4"), ClipData.Item("t", null, uri)) }
        assertNotNull(MediaShareIntent.local(text).error)
        val html = send("video/mp4", uri).apply { clipData = ClipData("a", arrayOf("video/mp4"), ClipData.Item("t", "<b>x</b>")) }
        assertNotNull(MediaShareIntent.local(html).error)
        val viaIntent = send("video/mp4", uri).apply { clipData = ClipData("a", arrayOf("video/mp4"), ClipData.Item(Intent("x"))) }
        assertNotNull(MediaShareIntent.local(viaIntent).error)
    }
    @Test fun uppercaseSchemeIsTreatedAsContent() {
        assertNotNull(MediaShareIntent.local(send("video/mp4", Uri.parse("CONTENT://m/1"))).request)
    }
    @Test fun httpStreamPlusTextStillFallsThroughToUrlPath() {
        val i = send("video/mp4", Uri.parse("https://example/a.mp4")).putExtra(Intent.EXTRA_TEXT, "https://example/a.mp4")
        val r = MediaShareIntent.local(i); assertNull(r.request); assertNull(r.error)
    }
}
