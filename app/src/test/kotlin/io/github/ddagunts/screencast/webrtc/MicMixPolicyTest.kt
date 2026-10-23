package io.github.ddagunts.screencast.webrtc

import android.content.Context
import android.content.pm.ServiceInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

class MicMixPolicyTest {
    private val proj = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
    private val mic = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE

    @Test fun `default keeps today's type`() {
        assertEquals(proj, foregroundServiceType(micMix = false, micPermissionGranted = true, sdkInt = 34))
        assertEquals(proj, foregroundServiceType(false, false, 34))
    }

    @Test fun `mic type only when micMix and permission granted`() {
        assertEquals(proj or mic, foregroundServiceType(true, true, 34))
        assertEquals(proj, foregroundServiceType(true, false, 34))
    }

    @Test fun `old SDKs`() {
        assertEquals(0, foregroundServiceType(true, true, 28))
        assertEquals(proj, foregroundServiceType(true, true, 29)) // MICROPHONE type needs API 30
        assertEquals(proj or mic, foregroundServiceType(true, true, 30))
    }

    @Test fun `copy is truthful in both states`() {
        val off = audioSettingDescription(micMix = false)
        val on = audioSettingDescription(micMix = true)
        assertTrue(off.contains("microphone is not used", true))
        assertFalse("must not claim the mic is unused when it is mixed in", on.contains("not used", true))
        assertTrue(on.contains("microphone", true))
        val note = audioNote(AudioMode.NEEDS_PERMISSION, micMix = true)!!
        assertFalse(note.contains("not used", true)); assertTrue(note.contains("Microphone"))
        assertTrue(audioNote(AudioMode.NEEDS_PERMISSION)!!.contains("not used"))
    }

    @Test fun `mic mix copy is truthful about indicator echo and the locked switch`() {
        listOf(MIC_MIX_EXPLANATION, MIC_MIX_DIALOG_BODY).forEach { text ->
            val c = text.lowercase()
            assertTrue(c, c.contains("echo"))
            assertTrue(c, c.contains("tv"))
            assertTrue(c, c.contains("android 12"))            // indicators exist from API 31
            assertTrue(c, c.contains("before your next cast")) // the switch is disabled during a cast
            assertTrue(c, c.contains("do not prevent"))        // phone headphones do not stop TV-to-mic echo
            assertFalse(c, c.contains("use headphones"))
            assertFalse(c, c.contains("avoid echo"))
        }
        assertTrue(MIC_MIX_EXPLANATION.lowercase().contains("off by default"))
        assertFalse(MIC_MIX_EXPLANATION.lowercase().contains("you can turn this off again at any time"))
        assertFalse(MIC_MIX_DIALOG_BODY.lowercase().contains("at any time"))
    }

    // ---- toggle / visibility policy ----
    @Test fun `every off to on toggle shows the consent dialog and off needs none`() {
        assertEquals(MicMixToggleAction.SHOW_CONSENT, micMixToggleAction(on = true))
        assertEquals(MicMixToggleAction.TURN_OFF, micMixToggleAction(on = false))
    }

    @Test fun `consent asks for the permission only when it is missing`() {
        assertEquals(MicMixConsentAction.ENABLE, micMixConsentAction(permissionGranted = true))
        assertEquals(MicMixConsentAction.REQUEST_PERMISSION, micMixConsentAction(permissionGranted = false))
    }

    @Test fun `switch is hidden below API 30 and when playback audio is off`() {
        assertFalse(micMixSwitchVisible(audioRequested = true, sdkInt = 29))
        assertFalse(micMixSwitchVisible(true, 28))
        assertTrue(micMixSwitchVisible(true, 30))
        assertTrue(micMixSwitchVisible(true, 36))
        assertFalse(micMixSwitchVisible(false, 34))
    }

    @Test fun `mic mix is never active below API 30`() {
        val cfg = WebRtcSessionConfig(VideoPreset.HD_30, 1, audioRequested = true, recordAudioGranted = true, sdkInt = 29, micMix = true)
        assertFalse(cfg.micMixActive)
        assertTrue(cfg.copy(sdkInt = 30).micMixActive)
    }

    // ---- foreground type fallback seam (C) ----
    @Test fun `mic type refused falls back to the base type and reports mic off`() {
        val calls = mutableListOf<Int>(); val log = mutableListOf<String>()
        val ok = startForegroundWithMicFallback(proj or mic, proj, { calls += it; if (it == proj or mic) throw SecurityException("nope") }, { log += it })
        assertFalse(ok); assertEquals(listOf(proj or mic, proj), calls)
        assertTrue(log.any { it.contains("SecurityException") })
    }

    @Test fun `any runtime exception from the mic type falls back`() {
        val calls = mutableListOf<Int>()
        val ok = startForegroundWithMicFallback(proj or mic, proj, { calls += it; if (calls.size == 1) throw IllegalStateException("not allowed") }, { })
        assertFalse(ok); assertEquals(2, calls.size)
    }

    @Test fun `mic type accepted reports mic on with a single call`() {
        val calls = mutableListOf<Int>()
        assertTrue(startForegroundWithMicFallback(proj or mic, proj, { calls += it }, { }))
        assertEquals(listOf(proj or mic), calls)
    }

    @Test fun `no mic type requested means one base call and mic off`() {
        val calls = mutableListOf<Int>()
        assertFalse(startForegroundWithMicFallback(proj, proj, { calls += it }, { }))
        assertEquals(listOf(proj), calls)
    }

    @Test fun `a failing base type still propagates`() {
        try { startForegroundWithMicFallback(proj, proj, { throw SecurityException("x") }, { }); org.junit.Assert.fail() }
        catch (_: SecurityException) { }
    }

    // ---- notification disclosure (D1) ----
    @Test fun `notification discloses the microphone only while the mix is on`() {
        assertTrue(webRtcNotificationBody(null, micMixOn = true).contains("Microphone is mixed in"))
        assertFalse(webRtcNotificationBody(null, micMixOn = false).contains("icrophone"))
        assertEquals("Tap Stop to end", webRtcNotificationBody(null, micMixOn = false))
        assertTrue(webRtcNotificationBody("boom", micMixOn = true).startsWith("boom"))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MicMixStoreTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()

    @Test fun `off by default`() {
        assertFalse(WebRtcConfigStore(ctx).micMix)
        assertFalse(WebRtcConfigStore(ctx).snapshot().micMix)
        assertFalse(WebRtcConfigStore(ctx).snapshot().micMixActive)
    }

    @Test fun `round trips and snapshots`() {
        WebRtcConfigStore(ctx).micMix = true
        assertTrue(WebRtcConfigStore(ctx).micMix)
        assertTrue(WebRtcConfigStore(ctx).snapshot().micMix)
        WebRtcConfigStore(ctx).micMix = false
        assertFalse(WebRtcConfigStore(ctx).micMix)
    }

    @Test fun `active only when audio is active`() {
        val base = WebRtcSessionConfig(VideoPreset.HD_30, 1, audioRequested = true, recordAudioGranted = true, sdkInt = 34, micMix = true)
        assertTrue(base.micMixActive)
        assertFalse(base.copy(audioRequested = false).micMixActive)
        assertFalse(base.copy(recordAudioGranted = false).micMixActive)
        assertFalse(base.copy(micMix = false).micMixActive)
    }
}
