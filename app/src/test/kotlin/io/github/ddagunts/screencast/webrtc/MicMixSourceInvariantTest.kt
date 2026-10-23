package io.github.ddagunts.screencast.webrtc

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Static privacy invariants over ALL of src/main (java + kotlin), comments stripped:
 * only allow-listed files may construct a recorder or choose an audio source.
 * Mutation check: add `AudioRecord(1,` to any other main file and this test must fail.
 */
class MicMixSourceInvariantTest {
    private val mainDir: File = run {
        var d: File? = File(System.getProperty("user.dir")).absoluteFile
        while (d != null) {
            if (File(d, "src/main/AndroidManifest.xml").exists()) return@run File(d, "src/main")
            if (File(d, "app/src/main/AndroidManifest.xml").exists()) return@run File(d, "app/src/main")
            d = d.parentFile
        }
        error("cannot locate src/main from ${System.getProperty("user.dir")}")
    }
    private val manifest = File(mainDir, "AndroidManifest.xml").readText()

    private fun stripComments(s: String): String =
        s.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), " ").replace(Regex("(?m)(?<!:)//.*$"), "")

    private val sources: Map<String, String> = mainDir.walkTopDown()
        .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
        .associate { it.name to stripComments(it.readText()) }

    private fun offenders(pattern: Regex, allow: Set<String>, transform: (String) -> String = { it }) =
        sources.filter { (n, t) -> n !in allow && pattern.containsMatchIn(transform(t)) }.keys.sorted()

    @Test fun `scans the real source tree`() {
        assertTrue("found ${sources.size} files", sources.size > 20)
        assertTrue(sources.containsKey("AndroidMicSource.kt") && sources.containsKey("WebRtcPeer.kt"))
    }

    @Test fun `AudioRecord construction only in the mic source and the playback swap`() {
        // AudioEncoder.kt is the pre-existing HLS playback-capture recorder (no audio source of its own).
        val allow = setOf("AndroidMicSource.kt", "WebRtcPlaybackSwap.kt", "AudioEncoder.kt")
        assertEquals(emptyList<String>(), offenders(Regex("""\bAudioRecord\s*\("""), allow))
        assertEquals(emptyList<String>(), offenders(Regex("""\bAudioRecord\s*\.\s*Builder"""), allow))
    }

    @Test fun `the HLS encoder recorder is playback capture only`() {
        val t = sources.getValue("AudioEncoder.kt")
        assertTrue(t.contains("setAudioPlaybackCaptureConfig"))
        assertTrue(!Regex("""\bsetAudioSource\s*\(""").containsMatchIn(t))
    }

    @Test fun `MediaRecorder is not used outside the allow list`() {
        assertEquals(emptyList<String>(), offenders(Regex("""\bMediaRecorder\b"""), setOf("AndroidMicSource.kt", "WebRtcPlaybackSwap.kt")))
    }

    @Test fun `setAudioSource is only the pinned REMOTE_SUBMIX or in the allow list`() {
        val pinned = Regex("""setAudioSource\(\s*AUDIO_SOURCE_REMOTE_SUBMIX\s*\)""")
        assertEquals(
            emptyList<String>(),
            offenders(Regex("""\bsetAudioSource\s*\("""), setOf("AndroidMicSource.kt", "WebRtcPlaybackSwap.kt")) { pinned.replace(it, "") },
        )
    }

    @Test fun `no microphone class AudioSource names outside the dedicated mic file`() {
        assertEquals(
            emptyList<String>(),
            offenders(Regex("""AudioSource\.(MIC|VOICE_\w+|CAMCORDER|DEFAULT|UNPROCESSED)"""), setOf("AndroidMicSource.kt")),
        )
    }

    @Test fun `AndroidMicSource is referenced only from the peer and the mic mix files`() {
        val users = sources.filter { it.value.contains("AndroidMicSource") }.keys.sorted()
        assertEquals(listOf("AndroidMicSource.kt", "MicMix.kt", "WebRtcPeer.kt").filter { it in users }, users)
        assertTrue("WebRtcPeer.kt" in users)
    }

    @Test fun `the comment stripper hides comments but not code`() {
        assertEquals(" x", stripComments("/* AudioRecord( */ x").trim().let { " $it" })
        assertTrue(stripComments("val a = AudioRecord(1 // hi").contains("AudioRecord("))
        assertTrue(!stripComments("// AudioRecord(1,").contains("AudioRecord"))
    }

    @Test fun `ADM is still pinned to REMOTE_SUBMIX`() {
        assertTrue(File(mainDir, "java/io/github/ddagunts/screencast/webrtc/WebRtcPeer.kt").readText().contains(".setAudioSource(AUDIO_SOURCE_REMOTE_SUBMIX)"))
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
