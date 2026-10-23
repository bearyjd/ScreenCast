package io.github.ddagunts.screencast.webrtc

import java.nio.ByteBuffer

// Mic path tuning (48 kHz samples). The TV speaker is re-captured by the phone mic ~200-400 ms later,
// so the mic is attenuated and gated: quiet room/TV sound is closed out, speech opens the gate.
/** Mic gain, round(32768 * 10^(-9/20)) in Q15: -9 dB. */
internal const val MIC_GAIN_Q15 = 11627
/** Gate opens when a raw (pre-gain) mic sample reaches this magnitude (about -27 dBFS). */
internal const val MIC_GATE_OPEN_THRESHOLD = 1500
/** 5 ms ramp to fully open. */
internal const val MIC_GATE_ATTACK_SAMPLES = 240
/** Stays open 150 ms after the last loud sample (bridges speech gaps and zero crossings). */
internal const val MIC_GATE_HOLD_SAMPLES = 7200
/** 100 ms ramp to fully closed. */
internal const val MIC_GATE_RELEASE_SAMPLES = 4800
/** Feedback safeguard: mixed-in mic peak at/above this (post gain and gate; about -11 dBFS) ... */
internal const val FEEDBACK_PEAK_THRESHOLD = 9000
/** ... continuously for 3 s at 48 kHz means a howl, not speech: mute the mic mix for the session. */
internal const val FEEDBACK_LIMIT_SAMPLES = 3 * 48_000

private const val UNITY_Q15 = 32768

/**
 * Downward gate with attack/hold/release. Its gain (Q15) is also what attenuates the playback in the
 * mixer, so playback stays byte-identical while the gate is closed. Audio-thread only; no allocation.
 */
internal class MicGate(
    attackSamples: Int = MIC_GATE_ATTACK_SAMPLES,
    private val holdSamples: Int = MIC_GATE_HOLD_SAMPLES,
    releaseSamples: Int = MIC_GATE_RELEASE_SAMPLES,
    private val openThreshold: Int = MIC_GATE_OPEN_THRESHOLD,
) {
    private val attackStep = maxOf(1, UNITY_Q15 / maxOf(1, attackSamples))
    private val releaseStep = maxOf(1, UNITY_Q15 / maxOf(1, releaseSamples))
    private var gain = 0
    private var holdLeft = 0
    private var blockPeak = 0

    val isOpen: Boolean get() = gain > 0 || holdLeft > 0

    /** Feeds one raw mic magnitude; returns the gate gain (Q15, 0..32768) to apply to this sample. */
    fun step(magnitude: Int): Int {
        val open = if (magnitude >= openThreshold) { holdLeft = holdSamples; true }
        else if (holdLeft > 0) { holdLeft--; true }
        else false
        gain = if (open) minOf(UNITY_Q15, gain + attackStep) else maxOf(0, gain - releaseStep)
        return gain
    }

    fun notePeak(v: Int) { if (v > blockPeak) blockPeak = v }

    /** Largest mic contribution since the last call. */
    fun takeBlockPeak(): Int { val p = blockPeak; blockPeak = 0; return p }
}

/** Mutes the mic mix for the rest of the session once its level stays high for [limitSamples]. */
internal class FeedbackGuard(
    private val limitSamples: Int = FEEDBACK_LIMIT_SAMPLES,
    private val peakThreshold: Int = FEEDBACK_PEAK_THRESHOLD,
) {
    @Volatile var tripped = false
        private set
    private var run = 0

    /** [peak] of one block of [frames] mono samples. Returns true once (and forever after) tripped. */
    fun onBlock(peak: Int, frames: Int): Boolean {
        if (tripped) return true
        run = if (peak >= peakThreshold) run + frames else 0
        if (run >= limitSamples) tripped = true
        return tripped
    }
}

/**
 * Pure PCM16 mixing for the opt-in microphone mix. No Android types.
 *
 * Playback is 48 kHz stereo interleaved PCM16 little-endian; the mic is mono PCM16.
 * The mic is scaled by [MIC_GAIN_Q15] and the [MicGate]; the playback is attenuated by -3 dB only
 * in proportion to the gate gain (unchanged while the gate is closed). The sum is saturated.
 * Samples are read and written byte-wise (little-endian), so the result does not
 * depend on the ByteBuffer's own byte order, and the buffer position is never moved.
 */
internal object PcmMixer {
    /** round(32768 * 10^(-3/20)) = 0.7071 in Q15: -3.01 dB. Playback gain while the gate is fully open. */
    const val GAIN_Q15 = 23170
    const val MIC_GAIN_Q15 = io.github.ddagunts.screencast.webrtc.MIC_GAIN_Q15

    fun clamp16(v: Int): Int = if (v > Short.MAX_VALUE) Short.MAX_VALUE.toInt() else if (v < Short.MIN_VALUE) Short.MIN_VALUE.toInt() else v

    /**
     * Mixes the first [micCount] samples of [mic] into the stereo frames of [buf] in
     * [pos, pos+len), in place. Frames beyond [micCount] (mic underrun) feed a silent mic to the gate.
     * A trailing partial frame is left untouched. Returns the number of whole frames processed.
     */
    fun mixMonoIntoStereo(buf: ByteBuffer, pos: Int, len: Int, mic: ShortArray, micCount: Int, gate: MicGate): Int {
        if (pos < 0 || len <= 0) return 0
        val end = minOf(pos + len, buf.limit())
        val frames = (end - pos) / FRAME_BYTES
        val micAvail = micCount.coerceIn(0, mic.size)
        for (f in 0 until frames) {
            val x = if (f < micAvail) mic[f].toInt() else 0
            val g = gate.step(if (x < 0) -x else x)
            val m = (((x * MIC_GAIN_Q15) shr 15) * g) shr 15
            gate.notePeak(if (m < 0) -m else m)
            val pbGain = UNITY_Q15 - (((UNITY_Q15 - GAIN_Q15) * g) shr 15)
            val i = pos + f * FRAME_BYTES
            mixSample(buf, i, pbGain, m)
            mixSample(buf, i + 2, pbGain, m)
        }
        return frames
    }

    private fun mixSample(buf: ByteBuffer, i: Int, pbGain: Int, micScaled: Int) {
        val p = (buf.get(i).toInt() and 0xFF) or (buf.get(i + 1).toInt() shl 8)
        val out = clamp16(((p.toShort().toInt() * pbGain) shr 15) + micScaled)
        buf.put(i, out.toByte())
        buf.put(i + 1, (out shr 8).toByte())
    }

    private const val FRAME_BYTES = 4
}
