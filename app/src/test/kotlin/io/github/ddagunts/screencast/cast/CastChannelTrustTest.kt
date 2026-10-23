package io.github.ddagunts.screencast.cast

import io.github.ddagunts.screencast.androidtv.AndroidTvCertFactory
import java.net.InetAddress
import java.security.KeyStore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import org.junit.After
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins the KNOWN, documented gap (SECURITY.md): the Cast control channel does NOT verify the receiver
 * certificate or run Cast device-auth. These tests fail if someone changes that behavior either way, so
 * the change is deliberate. Full device-auth verification is out of scope.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CastChannelTrustTest {
    private lateinit var server: SSLServerSocket
    private var channel: CastChannel? = null

    private val handshakeDone = CountDownLatch(1)

    @Before fun startSelfSignedServer() {
        val m = AndroidTvCertFactory.generate()
        val pw = "x".toCharArray()
        val ks = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry("k", m.keyPair.private, pw, arrayOf(m.cert))
        }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, pw) }
        val ctx = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
        server = ctx.serverSocketFactory.createServerSocket(0, 5, InetAddress.getLoopbackAddress()) as SSLServerSocket
        // Accept in a loop: the first (client-trusted) handshake must complete; later rejected ones just fail.
        Thread {
            while (!server.isClosed) {
                runCatching {
                    (server.accept() as SSLSocket).apply { startHandshake(); handshakeDone.countDown() }
                }
            }
        }.apply { isDaemon = true }.start()
    }

    @After fun tearDown() {
        channel?.close()
        runCatching { server.close() }
    }

    // KNOWN GAP (SECURITY.md): the receiver certificate is NOT verified. This pins the current behavior.
    @Test fun knownGap_castChannelAcceptsUnverifiedSelfSignedReceiverCertificate() {
        val ch = CastChannel("127.0.0.1", server.localPort).also { channel = it }
        ch.connect {} // would throw SSLHandshakeException if the cert were verified
        assertTrue("server-side handshake did not complete", handshakeDone.await(5, TimeUnit.SECONDS))
    }

    @Test fun trustAllIsScopedToCastChannelNotTheJvmDefault() {
        val factoryBefore = HttpsURLConnection.getDefaultSSLSocketFactory()
        val ch = CastChannel("127.0.0.1", server.localPort).also { channel = it }
        ch.connect {} // trust-all handshake against the self-signed server succeeds first
        assertTrue("server-side handshake did not complete", handshakeDone.await(5, TimeUnit.SECONDS))

        val plain = SSLContext.getDefault().socketFactory.createSocket("127.0.0.1", server.localPort) as SSLSocket
        try {
            assertThrows(SSLHandshakeException::class.java) { plain.startHandshake() }
        } finally {
            runCatching { plain.close() }
        }
        assertSame(factoryBefore, HttpsURLConnection.getDefaultSSLSocketFactory())
    }
}
