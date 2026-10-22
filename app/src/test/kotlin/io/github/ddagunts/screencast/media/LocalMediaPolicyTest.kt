// Fork modification by bearyjd: allow-list, name sanitising, bind guard and fd verification for local sharing.
package io.github.ddagunts.screencast.media

import java.io.IOException
import java.net.InetAddress
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class LocalMediaPolicyTest {
    @Test fun onlySmallAllowListOfTypesIsServed() {
        assertEquals("video/mp4", LocalMediaPolicy.resolveType("video/mp4", null))
        assertEquals("audio/mpeg", LocalMediaPolicy.resolveType("AUDIO/MPEG; charset=x", null))
        assertEquals("audio/mp4", LocalMediaPolicy.resolveType(null, "audio/mp4"))
        for (bad in listOf("video/webm", "video/*", "application/octet-stream", "text/html", "image/jpeg", "video/x-matroska")) {
            assertNull(bad, LocalMediaPolicy.resolveType(bad, "video/mp4"))
            assertNull(bad, LocalMediaPolicy.resolveType(null, bad))
        }
        assertNull(LocalMediaPolicy.resolveType(null, null))
    }
    @Test fun resolverTypeWinsOverIntentType() {
        assertNull(LocalMediaPolicy.resolveType("video/webm", "video/mp4"))
        assertEquals("video/mp4", LocalMediaPolicy.resolveType("video/mp4", "video/*"))
    }
    @Test fun extensionMatchesType() {
        assertEquals("mp4", LocalMediaPolicy.extensionFor("video/mp4"))
        assertEquals("mp3", LocalMediaPolicy.extensionFor("audio/mpeg"))
        assertEquals("m4a", LocalMediaPolicy.extensionFor("audio/mp4"))
    }
    @Test fun displayNameIsSanitisedAndBounded() {
        assertEquals("Holiday 2024.mp4", LocalMediaPolicy.sanitizeDisplayName("Holiday 2024.mp4", "video/mp4"))
        assertEquals("ab", LocalMediaPolicy.sanitizeDisplayName("a\u0000\n‮b", "video/mp4"))
        assertEquals("Shared video", LocalMediaPolicy.sanitizeDisplayName(null, "video/mp4"))
        assertEquals("Shared audio", LocalMediaPolicy.sanitizeDisplayName("  \u0007 ", "audio/mpeg"))
        assertEquals(80, LocalMediaPolicy.sanitizeDisplayName("x".repeat(500), "video/mp4").length)
    }
    @Test fun bindHostMustBeRealLanAddress() {
        assertTrue(LocalMediaPolicy.isLanBindAddress("192.168.1.20"))
        assertTrue(LocalMediaPolicy.isLanBindAddress("10.0.0.5"))
        assertTrue(LocalMediaPolicy.isLanBindAddress("172.16.5.5"))
        for (pub in listOf("8.8.8.8", "100.64.0.1", "172.32.0.1", "192.169.0.1")) assertFalse(pub, LocalMediaPolicy.isLanBindAddress(pub))
        for (bad in listOf("127.0.0.1", "0.0.0.0", "169.254.1.1", "224.0.0.1", "::1", "localhost", "", "example.com")) {
            assertFalse(bad, LocalMediaPolicy.isLanBindAddress(bad))
        }
    }
    @Test fun receiverMustBeIpv4LiteralNeverResolved() {
        assertEquals("192.168.1.2", LocalMediaPolicy.parseReceiverAddress("192.168.1.2")?.hostAddress)
        for (bad in listOf("tv.local", "", "256.1.1.1", "1.2.3", "::1", "1.2.3.4.5", "0x7f.1", "01.2.3.4")) {
            assertNull(bad, LocalMediaPolicy.parseReceiverAddress(bad))
        }
    }
    private class Probe(override val length: Long, val read: (Long) -> Int) : LocalMediaSource {
        override fun readAt(dst: ByteBuffer, position: Long): Int = read(position)
        override fun close() {}
    }
    @Test fun seekableRegularSourcePassesVerification() =
        assertTrue(LocalMediaPolicy.isSeekable(Probe(10) { 1 }))
    @Test fun emptyUnknownOrUnreadableSourcesAreRejected() {
        assertFalse(LocalMediaPolicy.isSeekable(Probe(0) { 1 }))
        assertFalse(LocalMediaPolicy.isSeekable(Probe(-1) { 1 }))
        assertFalse(LocalMediaPolicy.isSeekable(Probe(10) { throw IOException("Illegal seek") }))
        assertFalse(LocalMediaPolicy.isSeekable(Probe(10) { -1 }))
        assertFalse(LocalMediaPolicy.isSeekable(Probe(10) { p -> if (p == 0L) 1 else -1 }))
    }
    @Test fun tokensAre128BitUrlSafeAndUnique() {
        val a = LocalMediaServer.newToken(); val b = LocalMediaServer.newToken()
        assertNotEquals(a, b)
        assertEquals(22, a.length)
        assertTrue(a.matches(Regex("[A-Za-z0-9_-]{22}")))
    }
    @Test fun tokenCompareIsExact() {
        assertTrue(LocalMediaServer.tokenMatches("abc", "abc"))
        assertFalse(LocalMediaServer.tokenMatches("abd", "abc"))
        assertFalse(LocalMediaServer.tokenMatches("ab", "abc"))
        assertFalse(LocalMediaServer.tokenMatches(null, "abc"))
        assertFalse(LocalMediaServer.tokenMatches("", "abc"))
    }
    private fun ip(s: String) = InetAddress.getByName(s)
    @Test fun receiverMustShareTheWifiSubnet() {
        assertTrue(LocalMediaPolicy.inSubnet(ip("192.168.1.77"), "192.168.1.20", 24))
        assertFalse(LocalMediaPolicy.inSubnet(ip("192.168.2.77"), "192.168.1.20", 24))
        assertTrue(LocalMediaPolicy.inSubnet(ip("192.168.2.77"), "192.168.1.20", 16))
        assertFalse(LocalMediaPolicy.inSubnet(ip("10.0.0.1"), "192.168.1.20", 24))
        assertTrue(LocalMediaPolicy.inSubnet(ip("192.168.1.20"), "192.168.1.20", 32))
        assertFalse(LocalMediaPolicy.inSubnet(ip("192.168.1.21"), "192.168.1.20", 32))
        assertFalse(LocalMediaPolicy.inSubnet(ip("192.168.1.21"), "192.168.1.20", 0))
        assertFalse(LocalMediaPolicy.inSubnet(ip("192.168.1.21"), "192.168.1.20", 7))
        assertTrue(LocalMediaPolicy.inSubnet(ip("10.9.9.9"), "10.0.0.5", 8))
        assertFalse(LocalMediaPolicy.inSubnet(ip("192.168.1.21"), "192.168.1.20", 33))
        assertFalse(LocalMediaPolicy.inSubnet(ip("192.168.1.21"), "not-an-ip", 24))
    }
    private fun bytes(vararg v: Int) = ByteArray(12).also { v.forEachIndexed { i, b -> it[i] = b.toByte() } }
    @Test fun magicBytesMatchDeclaredType() {
        val mp4 = bytes(0, 0, 0, 0x18, 'f'.code, 't'.code, 'y'.code, 'p'.code)
        assertTrue(LocalMediaPolicy.matchesMagic("video/mp4", mp4)); assertTrue(LocalMediaPolicy.matchesMagic("audio/mp4", mp4))
        assertFalse(LocalMediaPolicy.matchesMagic("audio/mpeg", mp4))
        assertTrue(LocalMediaPolicy.matchesMagic("audio/mpeg", bytes('I'.code, 'D'.code, '3'.code)))
        assertTrue(LocalMediaPolicy.matchesMagic("audio/mpeg", bytes(0xFF, 0xFB)))
        assertFalse(LocalMediaPolicy.matchesMagic("audio/mpeg", bytes(0xFF, 0x1B)))
        assertFalse(LocalMediaPolicy.matchesMagic("video/mp4", bytes(1, 2, 3, 4, 5, 6, 7, 8)))
        assertFalse(LocalMediaPolicy.matchesMagic("video/mp4", ByteArray(3)))
    }
    @Test fun labelsAreSanitisedWithFallback() {
        assertEquals("Living Room TV", LocalMediaPolicy.sanitizeLabel("Living Room\u202E TV", "Receiver"))
        assertEquals("Receiver", LocalMediaPolicy.sanitizeLabel("\n", "Receiver"))
        assertEquals(40, LocalMediaPolicy.sanitizeLabel("y".repeat(200), "Receiver", 40).length)
    }
    @Test fun receiverMustBePrivateAndNeverThePhoneItself() {
        assertTrue(LocalMediaPolicy.isAcceptableReceiver(ip("192.168.1.50"), "192.168.1.20"))
        for (bad in listOf("192.168.1.20", "127.0.0.1", "8.8.8.8", "100.64.0.9", "169.254.1.1", "0.0.0.0", "224.0.0.1")) {
            assertFalse(bad, LocalMediaPolicy.isAcceptableReceiver(ip(bad), "192.168.1.20"))
        }
    }
    @Test fun surrogatePairsAreNotSplitByTruncation() {
        val out = LocalMediaPolicy.sanitizeLabel("\uD83D\uDE00".repeat(10), "x", 5)
        assertTrue(out.length % 2 == 0 && !out.last().isHighSurrogate())
    }
    @Test fun onlyGstaticIsAnAllowedOrigin() = assertEquals(setOf("https://www.gstatic.com"), LocalMediaPolicy.ALLOWED_ORIGINS)
}
