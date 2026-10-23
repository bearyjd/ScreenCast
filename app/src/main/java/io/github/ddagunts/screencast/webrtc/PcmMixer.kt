package io.github.ddagunts.screencast.webrtc

import java.nio.ByteBuffer

/**
 * Pure PCM16 mixing for the opt-in microphone mix. No Android types.
 *
 * Playback is 48 kHz stereo interleaved PCM16 little-endian; the mic is mono PCM16.
 * Each source is scaled by -3 dB (Q15 0.7071) before the sum so two full-scale
 * signals cannot clip hard; the sum is then saturated to the Short range.
 * Samples are read and written byte-wise (little-endian), so the result does not
 * depend on the ByteBuffer's own byte order, and the buffer position is never moved.
 */
internal object PcmMixer {
    /** round(32768 * 10^(-3/20)) = 0.7071 in Q15: -3.01 dB. */
    const val GAIN_Q15 = 23170

    fun clamp16(v: Int): Int = if (v > Short.MAX_VALUE) Short.MAX_VALUE.toInt() else if (v < Short.MIN_VALUE) Short.MIN_VALUE.toInt() else v

    /**
     * Mixes the first [micCount] samples of [mic] into the stereo frames of [buf] in
     * [pos, pos+len), in place. Frames beyond [micCount] (mic underrun) get silent mic but the
     * same playback gain, so the level does not pump. A trailing partial frame is left untouched.
     * Returns the number of whole frames processed.
     */
    fun mixMonoIntoStereo(buf: ByteBuffer, pos: Int, len: Int, mic: ShortArray, micCount: Int): Int {
        if (pos < 0 || len <= 0) return 0
        val end = minOf(pos + len, buf.limit())
        val frames = (end - pos) / FRAME_BYTES
        val micAvail = micCount.coerceIn(0, mic.size)
        for (f in 0 until frames) {
            val m = if (f < micAvail) (mic[f].toInt() * GAIN_Q15) shr 15 else 0
            val i = pos + f * FRAME_BYTES
            mixSample(buf, i, m)
            mixSample(buf, i + 2, m)
        }
        return frames
    }

    private fun mixSample(buf: ByteBuffer, i: Int, micScaled: Int) {
        val p = (buf.get(i).toInt() and 0xFF) or (buf.get(i + 1).toInt() shl 8)
        val out = clamp16(((p.toShort().toInt() * GAIN_Q15) shr 15) + micScaled)
        buf.put(i, out.toByte())
        buf.put(i + 1, (out shr 8).toByte())
    }

    private const val FRAME_BYTES = 4
}
