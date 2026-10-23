package io.github.ddagunts.screencast.cast

import io.github.ddagunts.screencast.androidtv.AndroidTvCertFactory
import java.net.InetAddress
import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import org.junit.After
import org.junit.Assert.assertThrows
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

    @Before fun startSelfSignedServer() {
        val m = AndroidTvCertFactory.generate()
        val pw = "x".toCharArray()
        val ks = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry("k", m.keyPair.private, pw, arrayOf(m.cert))
        }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, pw) }
        val ctx = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
        server = ctx.serverSocketFactory.createServerSocket(0, 1, InetAddress.getLoopbackAddress()) as SSLServerSocket
        Thread { runCatching { (server.accept() as SSLSocket).apply { startHandshake() } } }.apply { isDaemon = true }.start()
    }

    @After fun tearDown() {
        channel?.close()
        runCatching { server.close() }
    }

    @Test fun castChannelAcceptsUnverifiedSelfSignedReceiverCertificate() {
        val ch = CastChannel("127.0.0.1", server.localPort).also { channel = it }
        ch.connect {} // would throw SSLHandshakeException if the cert were verified
    }

    @Test fun trustAllIsScopedToCastChannelNotTheJvmDefault() {
        val plain = SSLContext.getDefault().socketFactory.createSocket("127.0.0.1", server.localPort) as SSLSocket
        try {
            assertThrows(SSLHandshakeException::class.java) { plain.startHandshake() }
        } finally {
            runCatching { plain.close() }
        }
    }
}
