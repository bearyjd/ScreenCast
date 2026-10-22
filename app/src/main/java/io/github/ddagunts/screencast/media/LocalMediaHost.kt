// Fork modification by bearyjd: process-wide owner of the single active local-media server and its file descriptor.
package io.github.ddagunts.screencast.media

import io.github.ddagunts.screencast.util.LogRepository
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class LocalMediaStart(val url: String, val id: Long)

/**
 * At most one session. [stop] (user-initiated, unconditional) and [stop] by id (owner-initiated) are idempotent and
 * always close the source (the fd) AFTER the server has stopped. Server shutdown runs on [cleanupDispatcher] so callers
 * on the main thread never block. The Android service is a thin shell: it keeps the process alive, shows the Stop
 * notification and calls [reapIfExpired] periodically. If the process is killed everything dies with it.
 */
class LocalMediaHost(
    private val clock: () -> Long = System::currentTimeMillis,
    private val idleTimeoutMs: Long = LocalMediaPolicy.IDLE_TIMEOUT_MS,
    private val maxLifetimeMs: Long = LocalMediaPolicy.MAX_LIFETIME_MS,
    private val onResponse: (Int) -> Unit = {},
    private val onFirstOrigin: (String) -> Unit = {},
    cleanupDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val beforeRegister: suspend () -> Unit = {},
) {
    private class Session(val id: Long, val server: LocalMediaServer, val source: LocalMediaSource, val startedAt: Long)
    private val lock = Any()
    private val ids = AtomicLong(0)
    private var session: Session? = null
    private var stopEpoch = 0L
    private val cleanup = CoroutineScope(SupervisorJob() + cleanupDispatcher)
    private val _active = MutableStateFlow(false)
    private val _currentId = MutableStateFlow<Long?>(null)
    val active: StateFlow<Boolean> = _active
    val currentId: StateFlow<Long?> = _currentId

    /**
     * Takes ownership of [source] (closed on any failure, including a [stop] that raced this call).
     * Returns the URL the receiver should fetch and the session id for [stop].
     */
    suspend fun begin(source: LocalMediaSource, contentType: String, receiver: InetAddress, bindHost: String): LocalMediaStart {
        val epoch = synchronized(lock) { stopEpoch }
        val server = try {
            require(LocalMediaPolicy.extensionFor(contentType) != null) { "unsupported type" }
            LocalMediaServer(source, contentType, LocalMediaServer.newToken(), receiver, bindHost, clock,
                onFirstOrigin = onFirstOrigin, onResponse = onResponse)
        } catch (e: Exception) { source.close(); throw e }
        val port = try { server.start().also { beforeRegister() } } catch (e: Exception) { release(server, source); throw e }
        val (started, previous) = synchronized(lock) {
            if (stopEpoch != epoch) return@synchronized null to null
            val id = ids.incrementAndGet()
            val replaced = session
            session = Session(id, server, source, clock()); publish()
            LocalMediaStart("http://$bindHost:$port${server.path}", id) to replaced
        }
        previous?.let { release(it.server, it.source) }
        if (started == null) { release(server, source); throw IllegalStateException("stopped while starting") }
        return started
    }

    fun stop() {
        val old = synchronized(lock) { stopEpoch++; session.also { session = null; publish() } }
        old?.let { release(it.server, it.source) }
    }

    /** Stops the session only if it is still [id]. Returns whether it stopped one. */
    fun stop(id: Long): Boolean {
        val old = synchronized(lock) { session?.takeIf { it.id == id }?.also { session = null; publish() } }
        old?.let { release(it.server, it.source) }
        return old != null
    }

    /** Stops the session if idle or past its absolute lifetime. Returns true if it stopped one. */
    fun reapIfExpired(): Boolean {
        val current = synchronized(lock) { session } ?: return false
        val expired = clock() - current.startedAt >= maxLifetimeMs || current.server.idleMillis() >= idleTimeoutMs
        return expired && stop(current.id)
    }

    private fun publish() { _currentId.value = session?.id; _active.value = session != null }

    private fun release(server: LocalMediaServer, source: LocalMediaSource) {
        cleanup.launch { try { server.stop() } finally { source.close() } }
    }

    companion object {
        // Logs status codes and the first Origin string only: never file names, bytes, tokens or peer addresses.
        val shared = LocalMediaHost(
            onResponse = { LogRepository.log(LogRepository.Level.D, "LocalMedia", "response $it") },
            onFirstOrigin = { LogRepository.log(LogRepository.Level.I, "LocalMedia", "first request origin: $it") },
        )
    }
}
