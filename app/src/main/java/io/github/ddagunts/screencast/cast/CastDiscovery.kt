package io.github.ddagunts.screencast.cast

import android.content.Context
import io.github.ddagunts.screencast.util.logD
import io.github.ddagunts.screencast.util.logE
import io.github.ddagunts.screencast.util.logI
import io.github.ddagunts.screencast.util.logW
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean

data class CastDevice(val name: String, val host: String, val port: Int)

// Cast V2 always listens on 8009; mDNS advertises it but a manually entered
// device skips mDNS, so we use this default.
const val CHROMECAST_DEFAULT_PORT = 8009

// Process-wide owner of the single "_googlecast._tcp" scan. Screens acquire a
// Lease in onStart and release it in onStop; the first lease starts the scan
// (and the multicast lock), the last release stops both. NSD callbacks arrive
// on arbitrary threads, so every mutation happens under [lock]; published state
// is immutable (StateFlow of an immutable list).
class CastDiscovery(
    private val backend: NsdBackend,
    private val scope: CoroutineScope,
) {
    companion object {
        const val RESOLVE_RETRY_BASE_MS = 200L
        const val MAX_RESOLVE_RETRIES = 3
        const val MAX_TRACKED_SERVICES = 64

        @Volatile private var instance: CastDiscovery? = null

        fun shared(context: Context): CastDiscovery = instance ?: synchronized(this) {
            instance ?: CastDiscovery(
                AndroidNsdBackend(context.applicationContext),
                CoroutineScope(SupervisorJob() + Dispatchers.Default),
            ).also { instance = it }
        }
    }

    inner class Lease internal constructor() {
        private val released = AtomicBoolean(false)
        fun release() { if (released.compareAndSet(false, true)) releaseLease() }
    }

    private class Resolving(
        val id: Long,
        val service: DiscoveredService,
        val attempts: Int,
        val handle: Cancelable? = null,
        val retry: Job? = null,
    )

    private class Tracked(val device: CastDevice, val key: String, val rank: Int)

    private val lock = Any()
    private var leases = 0
    private var scanning = false
    private var generation = 0L
    private var nextResolveId = 0L
    private var resolving: Map<String, Resolving> = emptyMap()
    private var byService: Map<String, Tracked> = emptyMap()
    private var capLogged = false

    private val _devices = MutableStateFlow<List<CastDevice>>(emptyList())
    val devices: StateFlow<List<CastDevice>> = _devices
    private val _status = MutableStateFlow<DiscoveryStatus>(DiscoveryStatus.Idle)
    val status: StateFlow<DiscoveryStatus> = _status

    fun acquire(): Lease = synchronized(lock) {
        leases++
        if (leases == 1) startScan()
        Lease()
    }

    private fun releaseLease() {
        synchronized(lock) {
            leases--
            if (leases == 0) stopScan()
        }
    }

    /** Restarts the scan (e.g. the Retry button). No-op when nobody holds a lease. */
    fun rescan() {
        synchronized(lock) {
            if (leases == 0) return
            stopScan()
            startScan()
        }
    }

    private fun startScan() {
        val gen = ++generation
        backend.acquireMulticastLock()
        scanning = true
        _status.value = DiscoveryStatus.Scanning // before start: a synchronous failure must win
        try {
            backend.startDiscovery(object : DiscoveryEvents {
                override fun onServiceFound(service: DiscoveredService) {
                    synchronized(lock) {
                        if (gen != generation) return
                        if (service.name !in resolving && resolving.size >= MAX_TRACKED_SERVICES) {
                            if (!capLogged) { capLogged = true; logW("too many mDNS services; ignoring extras") }
                            return
                        }
                        logD("service found")
                        beginResolve(service, attempts = 0)
                    }
                }
                override fun onServiceLost(name: String) {
                    synchronized(lock) {
                        if (gen != generation) return
                        logI("service lost")
                        forget(name)
                    }
                }
                override fun onStartFailed() {
                    synchronized(lock) {
                        if (gen != generation) return
                        logE("discovery start failed")
                        fail(DiscoveryFailure.START_FAILED)
                    }
                }
            })
        } catch (e: SecurityException) {
            logE("discovery start denied: ${e.message}")
            fail(DiscoveryFailure.PERMISSION_DENIED)
        } catch (e: Exception) {
            logE("discovery start threw: ${e.message}")
            fail(DiscoveryFailure.UNKNOWN)
        }
    }

    // Scan never (or no longer) running: drop resolves, devices and the lock,
    // but leave the lease count alone so rescan() can try again.
    private fun fail(reason: DiscoveryFailure) {
        clearResolves()
        if (scanning) { scanning = false; backend.releaseMulticastLock() }
        _status.value = DiscoveryStatus.Failed(reason)
    }

    private fun stopScan() {
        generation++
        capLogged = false
        clearResolves()
        if (scanning) {
            scanning = false
            runCatching { backend.stopDiscovery() }.onFailure { logW("stop failed: ${it.message}") }
            backend.releaseMulticastLock()
        }
        _status.value = DiscoveryStatus.Idle
    }

    private fun clearResolves() {
        resolving.values.forEach { cancelResolve(it) }
        resolving = emptyMap()
        byService = emptyMap()
        _devices.value = emptyList()
    }

    private fun cancelResolve(r: Resolving) {
        r.retry?.cancel()
        r.handle?.let { h -> runCatching { h.cancel() } }
    }

    private fun beginResolve(service: DiscoveredService, attempts: Int) {
        resolving[service.name]?.let { cancelResolve(it) }
        val id = ++nextResolveId
        val gen = generation
        resolving = resolving + (service.name to Resolving(id, service, attempts))
        val handle = try {
            backend.resolve(service, object : ResolveEvents {
                override fun onResolved(resolved: ResolvedService) = synchronized(lock) {
                    if (isCurrent(gen, service.name, id)) handleResolved(resolved)
                }
                override fun onFailed(failure: ResolveFailure) = synchronized(lock) {
                    if (isCurrent(gen, service.name, id)) handleResolveFailed(service, attempts, failure)
                }
                override fun onLost() = synchronized(lock) {
                    if (isCurrent(gen, service.name, id)) { logI("service lost"); forget(service.name) }
                }
            })
        } catch (e: Exception) {
            logE("resolve threw: ${e.message}")
            return
        }
        val cur = resolving[service.name]
        if (cur?.id == id) resolving = resolving + (service.name to Resolving(id, service, attempts, handle = handle))
        else runCatching { handle.cancel() } // lost/replaced while still registering
    }

    private fun isCurrent(gen: Long, name: String, id: Long) =
        gen == generation && resolving[name]?.id == id

    private fun handleResolved(r: ResolvedService) {
        val usable = r.addresses.filter { isAllowedAddress(it) }
        val address = pickAddress(usable)
        val host = address?.hostAddress
        if (host == null || !isValidPort(r.port)) {
            // An update that is no longer usable must not leave the old entry published.
            logW("resolved service has no usable address/port — dropping")
            if (r.name in byService) { byService = byService - r.name; publish() }
            return
        }
        val device = CastDevice(sanitizeDeviceName(r.friendlyName, r.name), host, r.port)
        val key = r.id?.trim()?.takeIf { it.isNotEmpty() }?.let { "id:$it" } ?: "addr:$host:${r.port}"
        byService = byService + (r.name to Tracked(device, key, rank(address)))
        publish()
        logI("resolved a device on port ${r.port}")
    }

    private fun handleResolveFailed(service: DiscoveredService, attempts: Int, failure: ResolveFailure) {
        logE("resolve failed ($failure)")
        if (failure != ResolveFailure.ALREADY_ACTIVE || attempts >= MAX_RESOLVE_RETRIES) return
        val cur = resolving.getValue(service.name)
        cur.retry?.cancel()
        val id = cur.id
        val job = scope.launch {
            delay(RESOLVE_RETRY_BASE_MS shl attempts)
            synchronized(lock) {
                if (resolving[service.name]?.id != id) return@synchronized
                // Retry job is the one running; drop the reference so beginResolve doesn't cancel it.
                resolving = resolving + (service.name to Resolving(id, service, attempts))
                beginResolve(service, attempts + 1)
            }
        }
        resolving = resolving + (service.name to Resolving(id, service, attempts, retry = job))
    }

    private fun forget(name: String) {
        resolving[name]?.let { cancelResolve(it) }
        resolving = resolving - name
        if (name in byService) {
            byService = byService - name
            publish()
        }
    }

    // One entry per device: its TXT id when advertised, else host+port (Cast
    // groups and speaker pairs share an IP but not a port). When one device
    // resolves via several addresses the best-ranked (IPv4) wins.
    private fun publish() {
        _devices.value = byService.values
            .groupBy { it.key }
            .values
            .map { group -> group.minWith(compareBy<Tracked>({ it.rank }, { it.device.name })).device }
            .sortedBy { it.name }
    }

    // IPv4 first (what the Cast socket and WebRTC signalling reliably reach),
    // then ULA IPv6; link-local addresses need a scope id and lose.
    private fun rank(a: InetAddress): Int = when {
        a is Inet4Address && !a.isLinkLocalAddress -> 0
        a is Inet4Address -> 1
        !a.isLinkLocalAddress -> 2
        else -> 3
    }

    private fun pickAddress(addresses: List<InetAddress>): InetAddress? = addresses.minByOrNull { rank(it) }
}
