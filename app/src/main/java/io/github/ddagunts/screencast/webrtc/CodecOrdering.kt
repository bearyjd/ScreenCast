// Fork modification by bearyjd: pure codec-preference ordering (no libwebrtc types).
package io.github.ddagunts.screencast.webrtc

interface CodecLike {
    val mimeType: String
    val parameters: Map<String, String>
}

data class CodecDescriptor(
    override val mimeType: String,
    override val parameters: Map<String, String> = emptyMap(),
) : CodecLike

data class CodecOrderResult<T>(val codecs: List<T>, val applied: Boolean)

// H.264 "High" family profile_idc is 0x64 (constrained-high shares it).
private const val H264_HIGH_PROFILE_PREFIX = "64"

// Moves every codec matching `pref` to the front (stable), keeping all other
// entries — including rtx/red/ulpfec/flexfec — after, in their original order.
// AUTO, or a preference with no matching entry, returns the input order.
fun <T : CodecLike> orderCodecs(codecs: List<T>, pref: VideoCodecPreference): CodecOrderResult<T> {
    val mime = pref.mimeType ?: return CodecOrderResult(codecs, applied = true)
    val (matching, rest) = codecs.partition { it.mimeType.equals(mime, ignoreCase = true) }
    if (matching.isEmpty()) return CodecOrderResult(codecs, applied = false)
    val ranked = if (pref == VideoCodecPreference.H264) {
        matching.sortedBy { if (isH264High(it)) 0 else 1 } // sortedBy is stable
    } else {
        matching
    }
    return CodecOrderResult(ranked + rest, applied = true)
}

private fun isH264High(codec: CodecLike): Boolean =
    codec.parameters["profile-level-id"]?.startsWith(H264_HIGH_PROFILE_PREFIX, ignoreCase = true) == true

// Short user-facing note when the receiver negotiated a different codec than
// requested; null for AUTO, a missing/blank/unrecognised mime, or a match.
fun codecMismatch(requested: VideoCodecPreference, negotiatedMime: String?, applied: Boolean = true): String? {
    if (requested == VideoCodecPreference.AUTO) return null
    val negotiated = VideoCodecPreference.entries.firstOrNull {
        it.mimeType != null && it.mimeType.equals(negotiatedMime?.trim(), ignoreCase = true)
    } ?: return null
    if (negotiated == requested) return null
    if (!applied) return "${requested.label} unavailable on this phone; using ${negotiated.label}"
    return "Requested ${requested.label} but the TV chose ${negotiated.label}"
}

// Reports a mismatch at most once per cast, on the first sample that carries a
// codec mime. `warn` is for logs, `publish` for UI state; neither gets IPs.
class CodecMismatchReporter(
    private val requested: VideoCodecPreference,
    private val warn: (String) -> Unit,
    private val publish: (String) -> Unit,
    private val applied: () -> Boolean = { true },
) {
    private val decided = java.util.concurrent.atomic.AtomicBoolean(false)

    fun onSample(sample: VideoStatsSample) {
        val mime = sample.codecMime?.takeIf { it.isNotBlank() } ?: return
        if (!decided.compareAndSet(false, true)) return
        codecMismatch(requested, mime, applied())?.let { warn(it); publish(it) }
    }
}
