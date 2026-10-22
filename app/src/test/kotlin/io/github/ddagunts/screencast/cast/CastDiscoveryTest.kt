package io.github.ddagunts.screencast.cast

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetAddress

private class FakeBackend : NsdBackend {
    var startCount = 0
    var stopCount = 0
    var lockAcquired = 0
    var lockReleased = 0
    var startError: Throwable? = null
    var failSynchronously = false
    var discoveryEvents: DiscoveryEvents? = null
    val resolveCalls = mutableListOf<String>()
    val resolveEvents = mutableMapOf<String, ResolveEvents>()
    val canceled = mutableListOf<String>()

    override fun startDiscovery(events: DiscoveryEvents) {
        startCount++
        startError?.let { throw it }
        discoveryEvents = events
        if (failSynchronously) events.onStartFailed()
    }
    override fun stopDiscovery() { stopCount++ }
    override fun resolve(service: DiscoveredService, events: ResolveEvents): Cancelable {
        resolveCalls += service.name
        resolveEvents[service.name] = events
        return Cancelable { canceled += service.name }
    }
    override fun acquireMulticastLock() { lockAcquired++ }
    override fun releaseMulticastLock() { lockReleased++ }

    fun found(name: String) = discoveryEvents!!.onServiceFound(DiscoveredService(name))
    fun resolved(name: String, friendly: String?, port: Int, vararg ips: String, id: String? = null) =
        resolveEvents.getValue(name).onResolved(
            ResolvedService(name, friendly, ips.map { InetAddress.getByName(it) }, port, id)
        )
}

// Robolectric only so the android.util.Log-backed logI/logE helpers are callable.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class CastDiscoveryTest {
    private fun TestScope.newDiscovery(b: FakeBackend) = CastDiscovery(b, this)

    @Test fun twoLeasesShareOneScan() = runTest {
        val b = FakeBackend()
        val d = newDiscovery(b)
        val l1 = d.acquire()
        val l2 = d.acquire()
        assertEquals(1, b.startCount)
        assertEquals(DiscoveryStatus.Scanning, d.status.value)
        l1.release(); l2.release()
    }

    @Test fun lastReleaseStopsScanAndClearsState() = runTest {
        val b = FakeBackend()
        val d = newDiscovery(b)
        val l1 = d.acquire()
        val l2 = d.acquire()
        b.found("a"); b.resolved("a", "Den", 8009, "192.168.1.5")
        l1.release()
        assertEquals(0, b.stopCount)
        assertEquals(1, d.devices.value.size)
        l1.release() // double release must not underflow the count
        assertEquals(0, b.stopCount)
        l2.release()
        assertEquals(1, b.stopCount)
        assertEquals(DiscoveryStatus.Idle, d.status.value)
        assertTrue(d.devices.value.isEmpty())
        assertTrue("a" in b.canceled)
    }

    @Test fun reacquireAfterFullReleaseStartsNewScan() = runTest {
        val b = FakeBackend()
        val d = newDiscovery(b)
        d.acquire().release()
        d.acquire()
        assertEquals(2, b.startCount)
    }

    @Test fun resolvedDeviceUsesFriendlyNameAndPrefersIpv4() = runTest {
        val b = FakeBackend()
        val d = newDiscovery(b); d.acquire()
        b.found("a")
        b.resolved("a", "Living Room", 8009, "fe80::1", "fd00::1", "192.168.1.5")
        assertEquals(listOf(CastDevice("Living Room", "192.168.1.5", 8009)), d.devices.value)
    }

    @Test fun globalIpv6BeatsLinkLocalWhenNoIpv4() = runTest {
        val b = FakeBackend()
        val d = newDiscovery(b); d.acquire()
        b.found("a")
        b.resolved("a", null, 8009, "fe80::1", "fd00::1")
        assertEquals("fd00:0:0:0:0:0:0:1", d.devices.value.single().host)
        assertEquals("a", d.devices.value.single().name)
    }

    @Test fun servicesWithSameHostAreDeduped() = runTest {
        val b = FakeBackend()
        val d = newDiscovery(b); d.acquire()
        b.found("a"); b.found("b")
        b.resolved("a", "Den", 8009, "192.168.1.5")
        b.resolved("b", "Den (2)", 8009, "192.168.1.5")
        assertEquals(1, d.devices.value.size)
        assertEquals("192.168.1.5", d.devices.value.single().host)
    }

    @Test fun lostRemovesDeviceAndCancelsResolve() = runTest {
        val b = FakeBackend()
        val d = newDiscovery(b); d.acquire()
        b.found("a"); b.resolved("a", "Den", 8009, "192.168.1.5")
        b.discoveryEvents!!.onServiceLost("a")
        assertTrue(d.devices.value.isEmpty())
        assertTrue("a" in b.canceled)
    }

    @Test fun serviceInfoCallbackLostRemovesDevice() = runTest {
        val b = FakeBackend()
        val d = newDiscovery(b); d.acquire()
        b.found("a"); b.resolved("a", "Den", 8009, "192.168.1.5")
        b.resolveEvents.getValue("a").onLost()
        assertTrue(d.devices.value.isEmpty())
        assertTrue("a" in b.canceled)
    }

    @Test fun securityExceptionOnStartIsPermissionDenied() = runTest {
        val b = FakeBackend().apply { startError = SecurityException("no") }
        val d = newDiscovery(b); d.acquire()
        assertEquals(DiscoveryStatus.Failed(DiscoveryFailure.PERMISSION_DENIED), d.status.value)
        assertEquals(b.lockAcquired, b.lockReleased)
    }

    @Test fun otherExceptionOnStartIsUnknown() = runTest {
        val b = FakeBackend().apply { startError = IllegalArgumentException("x") }
        val d = newDiscovery(b); d.acquire()
        assertEquals(DiscoveryStatus.Failed(DiscoveryFailure.UNKNOWN), d.status.value)
    }

    @Test fun onStartDiscoveryFailedIsStartFailed() = runTest {
        val b = FakeBackend()
        val d = newDiscovery(b); d.acquire()
        b.discoveryEvents!!.onStartFailed()
        assertEquals(DiscoveryStatus.Failed(DiscoveryFailure.START_FAILED), d.status.value)
        assertEquals(b.lockAcquired, b.lockReleased)
    }

    @Test fun rescanRestartsAfterFailure() = runTest {
        val b = FakeBackend()
        val d = newDiscovery(b); d.acquire()
        b.discoveryEvents!!.onStartFailed()
        d.rescan()
        assertEquals(2, b.startCount)
        assertEquals(DiscoveryStatus.Scanning, d.status.value)
    }

    @Test fun rescanWithoutLeaseDoesNothing() = runTest {
        val b = FakeBackend()
        val d = newDiscovery(b)
        d.rescan()
        assertEquals(0, b.startCount)
    }

    @Test fun multicastLockHeldOnlyWhileScanning() = runTest {
        val b = FakeBackend()
        val d = newDiscovery(b)
        val l1 = d.acquire(); val l2 = d.acquire()
        assertEquals(1, b.lockAcquired)
        assertEquals(0, b.lockReleased)
        l1.release()
        assertEquals(0, b.lockReleased)
        l2.release()
        assertEquals(1, b.lockReleased)
    }

    @Test fun alreadyActiveResolveIsRetriedWithBackoff() = runTest {
        val b = FakeBackend()
        val d = newDiscovery(b); d.acquire()
        b.found("a")
        assertEquals(1, b.resolveCalls.size)
        b.resolveEvents.getValue("a").onFailed(ResolveFailure.ALREADY_ACTIVE)
        advanceTimeBy(CastDiscovery.RESOLVE_RETRY_BASE_MS + 1)
        assertEquals(2, b.resolveCalls.size)
        b.resolveEvents.getValue("a").onFailed(ResolveFailure.ALREADY_ACTIVE)
        advanceTimeBy(CastDiscovery.RESOLVE_RETRY_BASE_MS + 1) // second delay is longer
        assertEquals(2, b.resolveCalls.size)
        advanceTimeBy(CastDiscovery.RESOLVE_RETRY_BASE_MS * 2)
        assertEquals(3, b.resolveCalls.size)
    }

    @Test fun retriesAreBounded() = runTest {
        val b = FakeBackend()
        val d = newDiscovery(b); d.acquire()
        b.found("a")
        repeat(CastDiscovery.MAX_RESOLVE_RETRIES + 3) {
            b.resolveEvents.getValue("a").onFailed(ResolveFailure.ALREADY_ACTIVE)
            advanceTimeBy(60_000)
        }
        assertEquals(1 + CastDiscovery.MAX_RESOLVE_RETRIES, b.resolveCalls.size)
    }

    @Test fun otherResolveFailureIsNotRetried() = runTest {
        val b = FakeBackend()
        val d = newDiscovery(b); d.acquire()
        b.found("a")
        b.resolveEvents.getValue("a").onFailed(ResolveFailure.OTHER)
        advanceTimeBy(60_000)
        assertEquals(1, b.resolveCalls.size)
    }

    @Test fun retryDroppedWhenServiceLostMeanwhile() = runTest {
        val b = FakeBackend()
        val d = newDiscovery(b); d.acquire()
        b.found("a")
        b.resolveEvents.getValue("a").onFailed(ResolveFailure.ALREADY_ACTIVE)
        b.discoveryEvents!!.onServiceLost("a")
        advanceTimeBy(60_000)
        assertEquals(1, b.resolveCalls.size)
    }

    // ---- post-review ----

    @Test fun startFailureReportedSynchronouslyIsNotOverwrittenByScanning() = runTest {
        val b = FakeBackend().apply { failSynchronously = true }
        val d = newDiscovery(b); d.acquire()
        assertEquals(DiscoveryStatus.Failed(DiscoveryFailure.START_FAILED), d.status.value)
    }

    @Test fun trackedServicesAreCapped() = runTest {
        val b = FakeBackend()
        val d = newDiscovery(b); d.acquire()
        repeat(100) { b.found("svc$it") }
        assertTrue(b.resolveCalls.size <= CastDiscovery.MAX_TRACKED_SERVICES)
        repeat(100) { i -> if ("svc$i" in b.resolveEvents) b.resolved("svc$i", null, 8009, "10.0.0.${i + 1}") }
        assertTrue(d.devices.value.size <= CastDiscovery.MAX_TRACKED_SERVICES)
        assertEquals(CastDiscovery.MAX_TRACKED_SERVICES, b.resolveCalls.size)
    }

    @Test fun capFreesUpWhenServiceIsLost() = runTest {
        val b = FakeBackend()
        val d = newDiscovery(b); d.acquire()
        repeat(CastDiscovery.MAX_TRACKED_SERVICES) { b.found("svc$it") }
        b.discoveryEvents!!.onServiceLost("svc0")
        b.found("extra")
        assertTrue("extra" in b.resolveCalls)
    }

    @Test fun invalidPortIsDropped() = runTest {
        val b = FakeBackend()
        val d = newDiscovery(b); d.acquire()
        b.found("a"); b.resolved("a", "Den", 0, "192.168.1.5")
        b.found("b"); b.resolved("b", "Den", 70000, "192.168.1.6")
        assertTrue(d.devices.value.isEmpty())
    }

    @Test fun publicAndLoopbackAddressesAreDropped() = runTest {
        val b = FakeBackend()
        val d = newDiscovery(b); d.acquire()
        b.found("a"); b.resolved("a", "x", 8009, "8.8.8.8")
        b.found("b"); b.resolved("b", "x", 8009, "127.0.0.1")
        assertTrue(d.devices.value.isEmpty())
    }

    @Test fun nameIsSanitizedAndFallsBack() = runTest {
        val b = FakeBackend()
        val d = newDiscovery(b); d.acquire()
        b.found("svc"); b.resolved("svc", "  \u202EEvil\u0007 Room  ", 8009, "192.168.1.5")
        assertEquals("Evil Room", d.devices.value.single().name)
        b.resolved("svc", "   ", 8009, "192.168.1.5")
        assertEquals("svc", d.devices.value.single().name)
    }

    @Test fun staleResolvedUpdateWithNoUsableAddressRemovesDevice() = runTest {
        val b = FakeBackend()
        val d = newDiscovery(b); d.acquire()
        b.found("a"); b.resolved("a", "Den", 8009, "192.168.1.5")
        assertEquals(1, d.devices.value.size)
        b.resolved("a", "Den", 8009, "8.8.8.8")
        assertTrue(d.devices.value.isEmpty())
    }

    @Test fun sameHostDifferentPortIsKept() = runTest {
        val b = FakeBackend()
        val d = newDiscovery(b); d.acquire()
        b.found("a"); b.found("g")
        b.resolved("a", "Speaker", 8009, "192.168.1.5")
        b.resolved("g", "Group", 32187, "192.168.1.5")
        assertEquals(2, d.devices.value.size)
    }

    @Test fun sameIdAcrossAddressesPrefersIpv4() = runTest {
        val b = FakeBackend()
        val d = newDiscovery(b); d.acquire()
        b.found("a"); b.found("b")
        b.resolved("a", "Den", 8009, "fd00::5", id = "abc123")
        b.resolved("b", "Den", 8009, "192.168.1.5", id = "abc123")
        assertEquals(listOf("192.168.1.5"), d.devices.value.map { it.host })
    }

    @Test fun staleDiscoveryCallbackAfterReleaseIsIgnored() = runTest {
        val b = FakeBackend()
        val d = newDiscovery(b)
        val lease = d.acquire()
        val old = b.discoveryEvents!!
        lease.release()
        old.onServiceFound(DiscoveredService("late"))
        old.onStartFailed()
        assertTrue(b.resolveCalls.isEmpty())
        assertEquals(DiscoveryStatus.Idle, d.status.value)
    }

    @Test fun staleDiscoveryCallbackAfterRescanIsIgnored() = runTest {
        val b = FakeBackend()
        val d = newDiscovery(b); d.acquire()
        val old = b.discoveryEvents!!
        d.rescan()
        old.onServiceFound(DiscoveredService("late"))
        old.onStartFailed()
        assertTrue(b.resolveCalls.isEmpty())
        assertEquals(DiscoveryStatus.Scanning, d.status.value)
    }

    @Test fun staleResolveEventsAfterRefoundAreIgnored() = runTest {
        val b = FakeBackend()
        val d = newDiscovery(b); d.acquire()
        b.found("a")
        val first = b.resolveEvents.getValue("a")
        b.found("a")
        first.onResolved(ResolvedService("a", "Old", listOf(InetAddress.getByName("192.168.1.9")), 8009))
        assertTrue(d.devices.value.isEmpty())
        b.resolved("a", "New", 8009, "192.168.1.5")
        assertEquals("New", d.devices.value.single().name)
    }

    @Test fun doubleReleaseDoesNotStealAnotherLease() = runTest {
        val b = FakeBackend()
        val d = newDiscovery(b)
        val l1 = d.acquire(); val l2 = d.acquire()
        l1.release(); l1.release(); l1.release()
        assertEquals(0, b.stopCount)
        l2.release()
        assertEquals(1, b.stopCount)
    }
}
