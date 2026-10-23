package io.github.ddagunts.screencast

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import io.github.ddagunts.screencast.webrtc.FakeStore
import io.github.ddagunts.screencast.webrtc.FakeVolume
import io.github.ddagunts.screencast.webrtc.MediaMuteGuard
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.webrtc.PeerConnection.PeerConnectionState

// Pins that the "Stop" action in the foreground notification really ends the cast.
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WebRtcNotificationStopTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Before fun setUp() {
        MuteWiringService.guard = MediaMuteGuard(FakeVolume(), FakeStore(), clock = { 1L }, bootCount = { 3 }, log = {})
        MuteWiringService.throwOnClose = false
        WebRtcForegroundService.liveCast = false
    }
    @After fun tearDown() { WebRtcForegroundService.liveCast = false }

    private fun startIntent() = Intent().setAction(WebRtcForegroundService.ACTION_START)
        .putExtra(WebRtcForegroundService.EXTRA_DEVICE_NAME, "TV")
        .putExtra(WebRtcForegroundService.EXTRA_DEVICE_HOST, "192.0.2.1")
        .putExtra(WebRtcForegroundService.EXTRA_APP_ID, "receiver")
        .putExtra(WebRtcForegroundService.EXTRA_RESULT_DATA, Intent())

    @Test fun `notification Stop action targets the service and stops the live cast`() = runTest {
        MuteWiringService.dispatcher = StandardTestDispatcher(testScheduler)
        val controller = Robolectric.buildService(MuteWiringService::class.java).create()
        val svc = controller.get()
        runCurrent()
        svc.onStartCommand(startIntent(), 0, 1)
        runCurrent(); MuteWiringService.transport!!.ready(); advanceTimeBy(50); runCurrent()
        MuteWiringService.peer!!.connectionState.value = PeerConnectionState.CONNECTED
        runCurrent()
        assertTrue(WebRtcForegroundService.liveCast)

        val notification: Notification = shadowOf(svc).lastForegroundNotification
        assertNotNull(notification)
        val stop = notification.actions.single { it.title == "Stop" }

        // The PendingIntent must be an immutable getService() for our own service with ACTION_STOP.
        val shadowPi = shadowOf(stop.actionIntent)
        assertTrue(shadowPi.isServiceIntent)
        assertTrue(shadowPi.flags and PendingIntent.FLAG_IMMUTABLE != 0)
        val intent = shadowPi.savedIntents.single()
        assertEquals(WebRtcForegroundService.ACTION_STOP, intent.action)
        assertEquals(WebRtcForegroundService::class.java.name, intent.component?.className)

        // Delivering that intent (what the system does on tap) ends the cast and the service.
        svc.onStartCommand(intent, 0, 2)
        runCurrent()
        assertFalse(WebRtcForegroundService.liveCast)
        assertTrue(shadowOf(svc).isStoppedBySelf)
    }
}
