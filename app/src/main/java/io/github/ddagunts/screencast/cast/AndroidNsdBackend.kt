package io.github.ddagunts.screencast.cast

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import androidx.annotation.RequiresApi
import io.github.ddagunts.screencast.util.logE
import io.github.ddagunts.screencast.util.logI
import io.github.ddagunts.screencast.util.logW

class AndroidNsdBackend(context: Context) : NsdBackend {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    // Absent on exotic devices; discovery then just runs without the lock.
    private val wifi = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private var listener: NsdManager.DiscoveryListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    override fun startDiscovery(events: DiscoveryEvents) {
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) = logI("mDNS discovery started ($type)")
            override fun onDiscoveryStopped(type: String) = logI("mDNS discovery stopped")
            override fun onStartDiscoveryFailed(type: String, e: Int) {
                logE("discovery start failed $e")
                events.onStartFailed()
            }
            override fun onStopDiscoveryFailed(type: String, e: Int) = logE("discovery stop failed $e")
            override fun onServiceFound(info: NsdServiceInfo) =
                events.onServiceFound(DiscoveredService(info.serviceName, info))
            override fun onServiceLost(info: NsdServiceInfo) = events.onServiceLost(info.serviceName)
        }
        nsd.discoverServices("_googlecast._tcp", NsdManager.PROTOCOL_DNS_SD, l)
        listener = l
    }

    override fun stopDiscovery() {
        val l = listener ?: return
        listener = null
        nsd.stopServiceDiscovery(l)
    }

    override fun resolve(service: DiscoveredService, events: ResolveEvents): Cancelable {
        val info = service.platformInfo as NsdServiceInfo
        return if (Build.VERSION.SDK_INT >= 34) resolveWithCallback(info, events) else resolveLegacy(info, events)
    }

    @RequiresApi(34)
    private fun resolveWithCallback(info: NsdServiceInfo, events: ResolveEvents): Cancelable {
        val cb = object : NsdManager.ServiceInfoCallback {
            override fun onServiceInfoCallbackRegistrationFailed(e: Int) = events.onFailed(failureOf(e))
            override fun onServiceUpdated(updated: NsdServiceInfo) = events.onResolved(toResolved(info.serviceName, updated))
            override fun onServiceLost() = events.onLost()
            override fun onServiceInfoCallbackUnregistered() {}
        }
        nsd.registerServiceInfoCallback(info, { it.run() }, cb)
        return Cancelable {
            runCatching { nsd.unregisterServiceInfoCallback(cb) }
                .onFailure { logW("unregister service-info callback: ${it.message}") }
        }
    }

    private fun resolveLegacy(info: NsdServiceInfo, events: ResolveEvents): Cancelable {
        @Suppress("DEPRECATION")
        nsd.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(i: NsdServiceInfo, e: Int) = events.onFailed(failureOf(e))
            override fun onServiceResolved(i: NsdServiceInfo) = events.onResolved(toResolved(info.serviceName, i))
        })
        return Cancelable {} // legacy resolves are one-shot and not cancelable
    }

    private fun failureOf(code: Int) =
        if (code == NsdManager.FAILURE_ALREADY_ACTIVE) ResolveFailure.ALREADY_ACTIVE else ResolveFailure.OTHER

    private fun toResolved(key: String, info: NsdServiceInfo): ResolvedService {
        val addresses = if (Build.VERSION.SDK_INT >= 34) info.hostAddresses
        else @Suppress("DEPRECATION") listOfNotNull(info.host)
        val friendly = info.attributes["fn"]?.let { String(it, Charsets.UTF_8) }
        val id = info.attributes["id"]?.let { String(it, Charsets.UTF_8) }
        return ResolvedService(key, friendly, addresses, info.port, id)
    }

    @Synchronized override fun acquireMulticastLock() {
        val w = wifi ?: return logW("no WifiManager; scanning without multicast lock")
        runCatching {
            val lock = multicastLock ?: w.createMulticastLock("screencast-mdns").apply { setReferenceCounted(false) }
            multicastLock = lock
            lock.acquire()
        }.onFailure { logW("multicast lock acquire failed: ${it.message}") }
    }

    @Synchronized override fun releaseMulticastLock() {
        runCatching { multicastLock?.takeIf { it.isHeld }?.release() }
            .onFailure { logW("multicast lock release failed: ${it.message}") }
    }
}
