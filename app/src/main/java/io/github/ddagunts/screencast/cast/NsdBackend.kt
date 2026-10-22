package io.github.ddagunts.screencast.cast

import java.net.InetAddress

// Thin seam over NsdManager + WifiManager so CastDiscovery's ref-counting,
// dedupe and failure handling are unit-testable with a fake. Callbacks arrive
// on arbitrary NSD threads; CastDiscovery is responsible for synchronizing.

/** A service seen by mDNS but not yet resolved. [platformInfo] is backend-private. */
class DiscoveredService(val name: String, val platformInfo: Any? = null)

data class ResolvedService(
    val name: String,
    val friendlyName: String?,
    val addresses: List<InetAddress>,
    val port: Int,
    /** TXT 'id' (Cast device UUID), when advertised. */
    val id: String? = null,
)

enum class ResolveFailure { ALREADY_ACTIVE, OTHER }

interface DiscoveryEvents {
    fun onServiceFound(service: DiscoveredService)
    fun onServiceLost(name: String)
    fun onStartFailed()
}

interface ResolveEvents {
    fun onResolved(service: ResolvedService)
    fun onFailed(failure: ResolveFailure)
    fun onLost()
}

fun interface Cancelable {
    fun cancel()
}

interface NsdBackend {
    /** Begins the scan. May throw (SecurityException, IllegalArgumentException). */
    fun startDiscovery(events: DiscoveryEvents)
    fun stopDiscovery()
    fun resolve(service: DiscoveredService, events: ResolveEvents): Cancelable
    fun acquireMulticastLock()
    fun releaseMulticastLock()
}

enum class DiscoveryFailure { PERMISSION_DENIED, START_FAILED, UNKNOWN }

sealed interface DiscoveryStatus {
    data object Idle : DiscoveryStatus
    data object Scanning : DiscoveryStatus
    data class Failed(val reason: DiscoveryFailure) : DiscoveryStatus
}
