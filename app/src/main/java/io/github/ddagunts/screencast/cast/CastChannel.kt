// Fork modification by bearyjd: expose transport boundary and log protocol metadata only.
package io.github.ddagunts.screencast.cast

import android.annotation.SuppressLint
import io.github.ddagunts.screencast.util.logD
import io.github.ddagunts.screencast.util.logE
import io.github.ddagunts.screencast.util.logI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

interface CastTransport {
    val incoming: kotlinx.coroutines.flow.Flow<CastMessage>
    fun connect(onClose: (Throwable?) -> Unit): Job
    suspend fun send(msg: CastMessage)
    fun close()
}

class CastChannel(
    private val host: String,
    private val port: Int,
    private val socketProvider: (() -> SSLSocket)? = null,
) : CastTransport {
    private var socket: SSLSocket? = null
    private var out: DataOutputStream? = null
    private var scope: CoroutineScope? = null
    private val writeLock = Mutex()
    private val lifecycleLock = Any()
    @Volatile private var closed = false

    private val _incoming = MutableSharedFlow<CastMessage>(extraBufferCapacity = 256)
    override val incoming = _incoming.asSharedFlow()

    override fun connect(onClose: (Throwable?) -> Unit): Job {
        check(!closed) { "Cast channel is closed" }
        val factory = trustAllFactory()
        val s = socketProvider?.invoke() ?: (factory.createSocket(host, port) as SSLSocket)
        try {
            synchronized(lifecycleLock) {
                check(!closed) { "Cast channel is closed" }
                socket = s
            }
            s.soTimeout = 15_000
            s.startHandshake()
            s.soTimeout = 0
            val readerScope = CoroutineScope(Dispatchers.IO)
            synchronized(lifecycleLock) {
                if (closed) {
                    readerScope.cancel()
                    error("Cast channel is closed")
                }
                out = DataOutputStream(s.outputStream)
                scope = readerScope
            }
            logI("Cast TLS connected")
            return readerScope.launch {
                val input = DataInputStream(s.inputStream)
                runCatching {
                    while (true) {
                        val len = input.readInt()
                        if (len <= 0 || len > 65536) error("bad frame length")
                        val buf = ByteArray(len)
                        input.readFully(buf)
                        val msg = CastMessage.decode(buf)
                        logD("Cast received bytes=$len")
                        _incoming.emit(msg)
                    }
                }.onFailure { e ->
                    logE("Cast read ended exceptionType=${e.javaClass.simpleName}")
                    onClose(e)
                }
            }
        } catch (e: Exception) {
            runCatching { s.close() }
            synchronized(lifecycleLock) { if (socket === s) socket = null }
            throw e
        }
    }

    override suspend fun send(msg: CastMessage) {
        val data = msg.encode()
        writeLock.withLock {
            val o = out ?: error("not connected")
            o.writeInt(data.size)
            o.write(data)
            o.flush()
        }
        logD("Cast sent bytes=${data.size}")
    }

    override fun close() {
        val owned = synchronized(lifecycleLock) {
            closed = true
            scope?.cancel()
            scope = null
            val current = socket
            socket = null
            out = null
            current
        }
        runCatching { owned?.close() }
    }

    // Chromecasts present a self-signed device certificate that rotates on
    // every reboot, so we cannot pin it and we don't bundle the Cast root CA.
    // The trust-all manager is only installed on this one SSLContext and handed
    // to the Cast socket; it is not set as the JVM default.
    @SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
    private fun trustAllFactory(): SSLSocketFactory {
        val tm = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, auth: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, auth: String) {}
            override fun getAcceptedIssuers() = emptyArray<X509Certificate>()
        }
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf(tm), SecureRandom())
        return ctx.socketFactory
    }
}
