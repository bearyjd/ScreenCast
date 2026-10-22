// Fork modification by bearyjd: injectable boundaries for session lifecycle tests.
package io.github.ddagunts.screencast.webrtc

import android.content.Intent
import io.github.ddagunts.screencast.cast.CastChannel
import io.github.ddagunts.screencast.cast.CastMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection

internal interface SessionTransport {
    val incoming: Flow<CastMessage>
    fun connect(onClose: (Throwable?) -> Unit): Job
    suspend fun send(message: CastMessage)
    fun close()
}

internal class CastSessionTransport(host: String, port: Int) : SessionTransport {
    private val channel = CastChannel(host, port)
    override val incoming = channel.incoming
    override fun connect(onClose: (Throwable?) -> Unit) = channel.connect(onClose)
    override suspend fun send(message: CastMessage) = channel.send(message)
    override fun close() = channel.close()
}

internal interface SessionPeer {
    val connectionState: StateFlow<PeerConnection.PeerConnectionState>
    // Non-null once the negotiated codec differs from the requested one.
    val codecMismatch: StateFlow<String?> get() = NO_CODEC_MISMATCH
    fun build()
    fun addScreenSource(data: Intent, onProjectionStopped: () -> Unit)
    suspend fun createOffer(): String
    suspend fun setRemoteAnswer(sdp: String)
    fun addRemoteIceCandidate(candidate: IceCandidate)
    fun close()
}

private val NO_CODEC_MISMATCH: StateFlow<String?> = MutableStateFlow(null)
