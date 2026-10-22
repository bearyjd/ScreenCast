package io.github.ddagunts.screencast.webrtc

import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the exact org.webrtc members that WebRtcPlaybackSwap reaches by reflection
 * (io.github.webrtc-sdk:android:125.6422.07). If a library upgrade renames, retypes or
 * removes any of them, this test fails instead of audio silently degrading at runtime.
 * Classes are loaded without initialisation, so no native library is needed.
 *
 * What this cannot prove (bytecode behaviour, re-verify by javap on upgrade):
 * with useAudioRecord == false initRecordingIfNeeded() allocates the buffers but creates no
 * AudioRecord; startRecordingImpl starts the field's record; AudioRecordThread.run re-creates
 * a record via initAudioRecord() (source = the ADM audioSource) when the field is null and
 * useAudioRecord is true, and releases the record when useAudioRecord turns false.
 */
class WebRtcLibraryContractTest {
    private fun load(n: String): Class<*> = Class.forName(n, false, javaClass.classLoader)
    private val record = load(AdmReflection.RECORD_CLASS)
    private val adm = load(AdmReflection.ADM_CLASS)

    private fun field(c: Class<*>, n: String): Field = c.getDeclaredField(n)
    private fun method(c: Class<*>, n: String, vararg p: Class<*>): Method = c.getDeclaredMethod(n, *p)

    @Test fun `WebRtcAudioRecord is package private so reflection needs setAccessible`() {
        assertTrue(!Modifier.isPublic(record.modifiers))
    }

    @Test fun `private volatile AudioRecord audioRecord`() {
        val f = field(record, AdmReflection.F_AUDIO_RECORD)
        assertEquals("android.media.AudioRecord", f.type.name)
        assertTrue(Modifier.isPrivate(f.modifiers))
        assertTrue(Modifier.isVolatile(f.modifiers))
        assertTrue(!Modifier.isStatic(f.modifiers) && !Modifier.isFinal(f.modifiers))
    }

    @Test fun `private final Object audioRecordStateLock`() {
        val f = field(record, AdmReflection.F_STATE_LOCK)
        assertEquals(Any::class.java, f.type)
        assertTrue(Modifier.isFinal(f.modifiers) && !Modifier.isStatic(f.modifiers))
    }

    @Test fun `private boolean useAudioRecord exists`() {
        assertEquals(Boolean::class.javaPrimitiveType, field(record, "useAudioRecord").type)
    }

    @Test fun `initRecordingIfNeeded returns boolean`() {
        val m = method(record, AdmReflection.M_INIT_IF_NEEDED)
        assertEquals(Boolean::class.javaPrimitiveType, m.returnType)
        assertTrue(Modifier.isPublic(m.modifiers))
    }

    @Test fun `private AudioRecordThread audioThread`() {
        val f = field(record, AdmReflection.F_AUDIO_THREAD)
        assertEquals("org.webrtc.audio.WebRtcAudioRecord\$AudioRecordThread", f.type.name)
        assertTrue(Modifier.isPrivate(f.modifiers) && !Modifier.isStatic(f.modifiers))
    }

    @Test fun `Builder setAudioSource takes int`() {
        val b = load("org.webrtc.audio.JavaAudioDeviceModule\$Builder")
        assertEquals(b, b.getDeclaredMethod("setAudioSource", Int::class.javaPrimitiveType!!).returnType)
    }

    @Test fun `setUseAudioRecord takes boolean`() {
        val m = method(record, AdmReflection.M_SET_USE_AUDIO_RECORD, Boolean::class.javaPrimitiveType!!)
        assertEquals(Void.TYPE, m.returnType)
    }

    @Test fun `JavaAudioDeviceModule exposes public final audioInput of WebRtcAudioRecord`() {
        val f = field(adm, AdmReflection.F_AUDIO_INPUT)
        assertEquals(record, f.type)
        assertTrue(Modifier.isPublic(f.modifiers) && Modifier.isFinal(f.modifiers))
    }

    @Test fun `setAudioRecordEnabled delegates to setUseAudioRecord`() {
        val m = method(adm, "setAudioRecordEnabled", Boolean::class.javaPrimitiveType!!)
        assertTrue(Modifier.isPublic(m.modifiers))
    }

    @Test fun `ADM callbacks used by the safety guard keep their shape`() {
        val state = load("org.webrtc.audio.JavaAudioDeviceModule\$AudioRecordStateCallback")
        assertEquals(setOf("onWebRtcAudioRecordStart", "onWebRtcAudioRecordStop"), state.methods.map { it.name }.toSet())
        val err = load("org.webrtc.audio.JavaAudioDeviceModule\$AudioRecordErrorCallback")
        assertEquals(
            setOf("onWebRtcAudioRecordInitError", "onWebRtcAudioRecordStartError", "onWebRtcAudioRecordError"),
            err.methods.map { it.name }.toSet(),
        )
    }

    @Test fun `app code never sets a preferred input device on a recorder`() {
        // Calling it on our installed playback record could re-route capture; only the
        // library may do it on records it builds itself. Comment lines are ignored.
        val root = java.io.File("src/main/java")
        assertTrue("run from the module dir", root.isDirectory)
        val hits = root.walkTopDown().filter { it.extension == "kt" }.flatMap { f ->
            f.readLines().filterNot { it.trim().startsWith("//") || it.trim().startsWith("*") }
                .filter { Regex("setPreferred(Input)?Device").containsMatchIn(it) }.map { "${f.name}: $it" }
        }.toList()
        assertTrue("forbidden calls: $hits", hits.isEmpty())
    }
}
