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
class MediaShareIntentTest {
    @Test fun acceptsTextAndTypedRemoteUriWithoutAnyProjectionOrFetch() {
        val text = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "https://example/a.mp4?signature=keep%2Bme")
        assertEquals("https://example/a.mp4?signature=keep%2Bme", MediaShareIntent.parse(text).media?.url)
        val remote = Intent(Intent.ACTION_SEND).setType("audio/mpeg").putExtra(Intent.EXTRA_STREAM, Uri.parse("https://example/audio?id=2"))
        assertEquals("audio/mpeg", MediaShareIntent.parse(remote).media?.contentType)
    }
    @Test fun refusesMultipleLocalAndMalformedExtras() {
        val local = Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM, Uri.parse("content://private/clip"))
        assertNotNull(MediaShareIntent.parse(local).error)
        val multiple = Intent(Intent.ACTION_SEND_MULTIPLE).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "https://example/a.mp4")
        assertNotNull(MediaShareIntent.parse(multiple).error)
        val ambiguous = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "https://example/a.mp4")
        ambiguous.clipData = ClipData.newPlainText("media", "https://example/b.mp4")
        assertNotNull(MediaShareIntent.parse(ambiguous).error)
        assertNotNull(MediaShareIntent.parse(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, 123)).error)
    }
}
