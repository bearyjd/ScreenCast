// Fork modification by bearyjd: service is a thin shell; these pin start/stop idempotence and the re-share race at the host seam.
package io.github.ddagunts.screencast

import android.content.Intent
import io.github.ddagunts.screencast.media.FakeSource
import io.github.ddagunts.screencast.media.LocalMediaHost
import java.net.InetAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

class TestLocalMediaService : LocalMediaForegroundService() {
    override val host: LocalMediaHost get() = testHost
    companion object { var testHost = LocalMediaHost(cleanupDispatcher = Dispatchers.Unconfined) }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocalMediaForegroundServiceTest {
    private val host = LocalMediaHost(cleanupDispatcher = Dispatchers.Unconfined).also { TestLocalMediaService.testHost = it }
    @After fun tearDown() = host.stop()

    private fun service(action: String?) = Robolectric.buildService(
        TestLocalMediaService::class.java,
        Intent(androidx.test.core.app.ApplicationProvider.getApplicationContext(), TestLocalMediaService::class.java).setAction(action),
    ).create()
    private fun serve(): Pair<FakeSource, Long> {
        val s = FakeSource(ByteArray(10))
        val id = runBlocking { host.begin(s, "video/mp4", InetAddress.getByName("127.0.0.1"), "127.0.0.1") }.id
        return s to id
    }

    @Test fun startWithActiveSessionGoesForegroundAndKeepsServing() {
        val (s, _) = serve()
        val svc = service(LocalMediaForegroundService.ACTION_START).startCommand(0, 1)
        assertNotNull(shadowOf(svc.get()).lastForegroundNotification)
        assertFalse(shadowOf(svc.get()).isStoppedBySelf)
        assertEquals(0, s.closes.get()); assertTrue(host.active.value)
    }
    @Test fun startWithoutSessionStopsImmediately() {
        assertTrue(shadowOf(service(LocalMediaForegroundService.ACTION_START).startCommand(0, 1).get()).isStoppedBySelf)
    }
    @Test fun stopActionClosesSourceOnceAndIsIdempotent() {
        val (s, _) = serve()
        val svc = service(LocalMediaForegroundService.ACTION_STOP).startCommand(0, 1)
        assertEquals(1, s.closes.get()); assertFalse(host.active.value)
        assertTrue(shadowOf(svc.get()).isStoppedBySelf)
        service(LocalMediaForegroundService.ACTION_STOP).startCommand(0, 2)
        assertEquals(1, s.closes.get())
    }
    @Test fun unknownOrNullIntentsNeverStartServing() {
        assertTrue(shadowOf(service(null).startCommand(0, 1).get()).isStoppedBySelf)
    }
    @Test fun destroyStopsTheSessionItWasServing() {
        val (s, _) = serve()
        service(LocalMediaForegroundService.ACTION_START).startCommand(0, 1).destroy()
        assertEquals(1, s.closes.get())
    }
    @Test fun staleServiceDestroyNeverKillsAReSharedSession() {
        val (old, _) = serve()
        val svc = service(LocalMediaForegroundService.ACTION_START).startCommand(0, 1)
        val (newer, newerId) = serve()           // re-share replaces the old session
        svc.destroy()                            // stale service from the first share goes away
        assertEquals(1, old.closes.get())
        assertEquals(0, newer.closes.get()); assertEquals(newerId, host.currentId.value)
    }
    @Test fun secondStartRebindsServiceToTheNewSession() {
        serve()
        val svc = service(LocalMediaForegroundService.ACTION_START).startCommand(0, 1)
        val (newer, _) = serve()
        svc.startCommand(0, 2)
        svc.destroy()
        assertEquals(1, newer.closes.get())
    }
}
