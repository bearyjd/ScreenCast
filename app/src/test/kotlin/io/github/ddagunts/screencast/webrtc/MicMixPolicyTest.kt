package io.github.ddagunts.screencast.webrtc

import android.content.Context
import android.content.pm.ServiceInfo
import androidx.test.core.app.ApplicationProvider
import java.io.File
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

    @Test fun `mic mix copy covers indicator echo and room sound`() {
        val c = MIC_MIX_EXPLANATION.lowercase()
        listOf("indicator", "headphones", "room", "off by default").forEach { assertTrue(it, c.contains(it)) }
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

class MicMixSourceInvariantTest {
    private val root = File("src/main/java/io/github/ddagunts/screencast")
    private val manifest = File("src/main/AndroidManifest.xml").readText()

    @Test fun `microphone audio source appears only in the dedicated mic file`() {
        val offenders = root.walkTopDown().filter { it.extension == "kt" }
            .filter { Regex("""AudioSource\.(MIC|VOICE_\w+|CAMCORDER|DEFAULT|UNPROCESSED)""").containsMatchIn(it.readText()) }
            .map { it.name }.toList()
        assertEquals(listOf("AndroidMicSource.kt"), offenders)
    }

    @Test fun `ADM is still pinned to REMOTE_SUBMIX`() {
        val peer = File(root, "webrtc/WebRtcPeer.kt").readText()
        assertTrue(peer.contains(".setAudioSource(AUDIO_SOURCE_REMOTE_SUBMIX)"))
    }

    @Test fun `manifest declares mic FGS permission and only the WebRTC service has the mic type`() {
        assertTrue(manifest.contains("android.permission.FOREGROUND_SERVICE_MICROPHONE"))
        val services = Regex("""<service[^>]*?/>""", RegexOption.DOT_MATCHES_ALL).findAll(manifest).map { it.value }.toList()
        val withMic = services.filter { it.contains("microphone") }
        assertEquals(1, withMic.size)
        assertTrue(withMic[0].contains(".WebRtcForegroundService"))
        assertTrue(withMic[0].contains("mediaProjection|microphone"))
    }
}
