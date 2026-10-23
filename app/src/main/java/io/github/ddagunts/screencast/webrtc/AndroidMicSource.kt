package io.github.ddagunts.screencast.webrtc

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder

/**
 * The ONLY place in the app that opens a microphone AudioRecord. It is created by
 * [MicMixController] solely when the user enabled the mic mix; it is entirely separate from the
 * libwebrtc ADM (which stays pinned to REMOTE_SUBMIX and guarded). Mono PCM16 at [sampleRate].
 */
internal class AndroidMicSource private constructor(private val rec: AudioRecord) : MicSource {
    override val initialized: Boolean get() = rec.state == AudioRecord.STATE_INITIALIZED
    override fun start(): Boolean = try {
        rec.startRecording(); rec.recordingState == AudioRecord.RECORDSTATE_RECORDING
    } catch (_: IllegalStateException) { false }
    override fun read(dst: ShortArray, size: Int): Int = rec.read(dst, 0, size) // blocking
    override fun stop() { runCatching { rec.stop() } }
    override fun release() { runCatching { rec.release() } }

    companion object {
        /** May throw (SecurityException, IllegalArgumentException); the controller handles that. */
        @SuppressLint("MissingPermission") // checked by MicMixController before the factory is called
        fun create(sampleRate: Int): MicSource {
            val min = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val bytes = maxOf(2 * maxOf(min, 0), sampleRate / 50 * 2) // >= 20 ms
            return AndroidMicSource(
                AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bytes),
            )
        }
    }
}
