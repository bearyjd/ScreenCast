package io.github.ddagunts.screencast.media

import org.junit.Assert.*
import org.junit.Test

class SharedMediaTest {
    @Test fun acceptsDirectMediaAndPreservesSignedQuery() {
        val result = SharedMediaParser.parse("https://media.example/Film.MP4?token=secret%2Bvalue", "text/plain")
        assertEquals(SharedMedia("https://media.example/Film.MP4?token=secret%2Bvalue", "video/mp4"), result.media)
        assertNull(result.error)
    }
    @Test fun refusesAmbiguousUnsafeAndUnsupportedTargets() {
        listOf("https://example/a.mp4 https://example/b.mp4", "https://user:pass@example/a.mp4",
            "content://provider/a.mp4", "file:///a.mp4", "https://youtube.com/watch?v=a",
            "http://localhost/a.mp4", "http://localhost./a.mp4", "http://sub.localhost./a.mp4",
            "http://2130706433/a.mp4", "http://0x7f000001/a.mp4", "http://[::ffff:127.0.0.1]/a.mp4", "http://127.0.0.1/a.mp4", "http://169.254.1.2/a.mp4",
            "http://[::1]/a.mp4", "http://0.0.0.0/a.mp4", "https://example/a.mp4#fragment",
            "https://example/a.mp4\\other", "https://example:99999/a.mp4").forEach {
            assertNotNull(it, SharedMediaParser.parse(it, "text/plain").error)
        }
        assertNotNull(SharedMediaParser.parse("https://example/a.mp4", "text/html").error)
        assertNotNull(SharedMediaParser.parse("https://example/page", "text/plain").error)
        assertNotNull(SharedMediaParser.parse(null, "video/mp4").error)
    }
    @Test fun allowsLanAndDeclaredMimeWithoutFetching() {
        assertEquals("video/mp4", SharedMediaParser.parse("http://192.168.1.10/media?id=2", "video/mp4").media?.contentType)
        assertEquals("audio/mpeg", SharedMediaParser.parse("https://example/sound.mp3", "audio/*").media?.contentType)
    }
    @Test fun rejectsOctalIpv4ThatReceiverCouldInterpretAsLoopback() {
        assertNotNull(SharedMediaParser.parse("http://0177.0.0.1/a.mp4", "video/mp4").error)
    }
}
