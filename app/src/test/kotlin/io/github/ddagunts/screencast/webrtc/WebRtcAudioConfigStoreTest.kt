package io.github.ddagunts.screencast.webrtc

import android.Manifest
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WebRtcAudioConfigStoreTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()

    @Test fun `audio is requested by default`() {
        assertTrue(WebRtcConfigStore(ctx).audioEnabled)
        assertTrue(WebRtcConfigStore(ctx).snapshot().audioRequested)
    }

    @Test fun `user can turn audio off and it sticks`() {
        WebRtcConfigStore(ctx).audioEnabled = false
        assertFalse(WebRtcConfigStore(ctx).audioEnabled)
        assertFalse(WebRtcConfigStore(ctx).snapshot().audioRequested)
        WebRtcConfigStore(ctx).audioEnabled = true
        assertTrue(WebRtcConfigStore(ctx).audioEnabled)
    }

    @Test fun `legacy stored false does not switch audio off`() {
        ctx.getSharedPreferences("webrtc_config", Context.MODE_PRIVATE).edit().putBoolean("audio_enabled", false).commit()
        assertTrue(WebRtcConfigStore(ctx).audioEnabled)
    }

    @Test fun `mic permission asked flag round trips`() {
        assertFalse(WebRtcConfigStore(ctx).micPermissionAsked)
        WebRtcConfigStore(ctx).micPermissionAsked = true
        assertTrue(WebRtcConfigStore(ctx).micPermissionAsked)
    }

    @Test fun `snapshot is not audio-active without RECORD_AUDIO`() {
        val s = WebRtcConfigStore(ctx).snapshot()
        assertTrue(s.audioRequested)
        assertFalse(s.recordAudioGranted)
        assertFalse(s.audioActive)
    }

    @Test fun `snapshot is audio-active once RECORD_AUDIO is granted`() {
        shadowOf(ctx as android.app.Application).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val s = WebRtcConfigStore(ctx).snapshot()
        assertTrue(s.recordAudioGranted)
        assertTrue(s.audioActive)
        WebRtcConfigStore(ctx).audioEnabled = false
        assertEquals(false, WebRtcConfigStore(ctx).snapshot().audioActive)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MuteWhileCastingStoreTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()

    @Test fun `mute while casting is off by default`() {
        assertFalse(WebRtcConfigStore(ctx).muteWhileCasting)
        assertFalse(WebRtcConfigStore(ctx).snapshot().muteWhileCasting)
    }

    @Test fun `mute while casting persists`() {
        WebRtcConfigStore(ctx).muteWhileCasting = true
        assertTrue(WebRtcConfigStore(ctx).muteWhileCasting)
        assertTrue(WebRtcConfigStore(ctx).snapshot().muteWhileCasting)
    }

    @Test fun `prefs marker store round trips and clears`() {
        val s = PrefsMuteMarkerStore(ctx)
        assertEquals(null, s.read())
        s.write(MuteMarker(7, "abc", 99L))
        assertEquals(MuteMarker(7, "abc", 99L), PrefsMuteMarkerStore(ctx).read())
        s.clear()
        assertEquals(null, PrefsMuteMarkerStore(ctx).read())
    }
}
