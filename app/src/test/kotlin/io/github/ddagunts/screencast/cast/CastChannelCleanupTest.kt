package io.github.ddagunts.screencast.cast

import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import org.junit.Assert.*
import org.junit.Test

class CastChannelCleanupTest {
    @Test fun failedHandshakeClosesCreatedSocketBeforeThrowing() {
        val socket = SSLContext.getDefault().socketFactory.createSocket() as SSLSocket
        val channel = CastChannel("unused.example", 8009) { socket }
        assertThrows(Exception::class.java) { channel.connect {} }
        assertTrue("Unconnected socket handshake fails; connect must release it", socket.isClosed)
    }
    @Test fun closingDuringSocketCreationClosesLateSocket() {
        val created = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val socket = SSLContext.getDefault().socketFactory.createSocket() as SSLSocket
        val channel = CastChannel("unused.example", 8009) { created.countDown(); release.await(); socket }
        val thread = Thread { runCatching { channel.connect {} } }
        thread.start()
        assertTrue(created.await(3, java.util.concurrent.TimeUnit.SECONDS))
        channel.close()
        release.countDown()
        thread.join(3000)
        assertFalse(thread.isAlive)
        assertTrue("Socket created after close must be disposed", socket.isClosed)
    }
}
