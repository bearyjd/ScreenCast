// Fork modification by bearyjd: a session's claim on the shared host survives release/attach in either order.
package io.github.ddagunts.screencast.media

import java.net.InetAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class ServingClaimTest {
    private val host = LocalMediaHost(cleanupDispatcher = Dispatchers.Unconfined)
    @After fun tearDown() = host.stop()
    private fun begin(s: FakeSource) = runBlocking { host.begin(s, "video/mp4", InetAddress.getByName("127.0.0.1"), "127.0.0.1") }

    @Test fun releaseAfterAttachStopsOwnSessionOnly() {
        val mine = FakeSource(ByteArray(4)); val claim = ServingClaim(host)
        assertTrue(claim.attach(begin(mine).id))
        claim.release(); claim.release()
        assertEquals(1, mine.closes.get()); assertFalse(host.active.value)
    }
    @Test fun releaseBeforeAttachRefusesAttachSoCallerStopsIt() {
        val claim = ServingClaim(host); claim.release()
        val s = FakeSource(ByteArray(4)); val start = begin(s)
        assertFalse(claim.attach(start.id))
        assertEquals(0, s.closes.get()) // caller must stop(id); the claim never stops what it does not own
        assertTrue(host.stop(start.id)); assertEquals(1, s.closes.get())
    }
    @Test fun staleClaimNeverKillsNewerSession() {
        val old = ServingClaim(host); old.attach(begin(FakeSource(ByteArray(4))).id)
        val newer = FakeSource(ByteArray(4)); begin(newer)
        old.release()
        assertEquals(0, newer.closes.get()); assertTrue(host.active.value)
    }
    @Test fun neverAttachedReleaseIsHarmless() { ServingClaim(host).release(); assertFalse(host.active.value) }
}
