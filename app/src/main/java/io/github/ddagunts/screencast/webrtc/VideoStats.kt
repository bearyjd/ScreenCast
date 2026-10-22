// Fork modification by bearyjd: pure parsing/formatting of outbound video stats.
package io.github.ddagunts.screencast.webrtc

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

// Mirror of an RTCStats entry: type, timestamp (microseconds) and members.
data class StatEntry(val type: String, val timestampUs: Double, val members: Map<String, Any?>)

data class VideoStatsSample(
    val codecMime: String?,
    val encoderImpl: String?,
    val powerEfficient: Boolean?,
    val fps: Double?,
    val width: Int?,
    val height: Int?,
    val framesEncoded: Long?,
    val totalEncodeTimeSec: Double?,
    val bytesSent: Long?,
    val qualityLimit: String?,
    val nack: Long?,
    val pli: Long?,
    val timestampMs: Double?,
)

private const val NA = "n/a"

private fun Map<String, Any?>.num(key: String): Number? = this[key] as? Number
private fun Map<String, Any?>.str(key: String): String? = this[key] as? String

fun parseVideoStats(report: Map<String, StatEntry>): VideoStatsSample? {
    // Prefer the primary stream: the video outbound-rtp with the most bytes,
    // ignoring RTX retransmission streams.
    val out = report.values
        .filter {
            it.type == "outbound-rtp" &&
                (it.members.str("kind") ?: it.members.str("mediaType")) == "video" &&
                !isRtx(report, it)
        }
        .maxByOrNull { it.members.num("bytesSent")?.toLong() ?: -1L } ?: return null
    val m = out.members
    val codecMime = m.str("codecId")?.let { report[it] }
        ?.takeIf { it.type == "codec" }?.members?.str("mimeType")
    return VideoStatsSample(
        codecMime = codecMime,
        encoderImpl = m.str("encoderImplementation"),
        powerEfficient = m["powerEfficientEncoder"] as? Boolean,
        fps = m.num("framesPerSecond")?.toDouble(),
        width = m.num("frameWidth")?.toInt(),
        height = m.num("frameHeight")?.toInt(),
        framesEncoded = m.num("framesEncoded")?.toLong(),
        totalEncodeTimeSec = m.num("totalEncodeTime")?.toDouble(),
        bytesSent = m.num("bytesSent")?.toLong(),
        qualityLimit = m.str("qualityLimitationReason"),
        nack = m.num("nackCount")?.toLong(),
        pli = m.num("pliCount")?.toLong(),
        timestampMs = out.timestampUs / 1000.0,
    )
}

private fun isRtx(report: Map<String, StatEntry>, e: StatEntry): Boolean =
    e.members.str("codecId")?.let { report[it] }?.members?.str("mimeType")
        ?.endsWith("/rtx", ignoreCase = true) == true

fun formatVideoStats(cur: VideoStatsSample, prev: VideoStatsSample?): String {
    val res = if (cur.width != null && cur.height != null) "${cur.width}x${cur.height}" else NA
    return "video stats: codec=${cur.codecMime ?: NA} impl=${cur.encoderImpl ?: NA} " +
        "hw=${cur.powerEfficient ?: NA} fps=${cur.fps?.let { Math.round(it) } ?: NA} res=$res " +
        "encMsPerFrame=${encMsPerFrame(cur, prev) ?: NA} kbps=${kbps(cur, prev) ?: NA} " +
        "qualityLimit=${cur.qualityLimit ?: NA} nack=${cur.nack ?: NA} pli=${cur.pli ?: NA}"
}

private fun encMsPerFrame(cur: VideoStatsSample, prev: VideoStatsSample?): String? {
    if (prev == null) return null
    val frames = delta(cur.framesEncoded?.toDouble(), prev.framesEncoded?.toDouble()) ?: return null
    val time = delta(cur.totalEncodeTimeSec, prev.totalEncodeTimeSec) ?: return null
    if (frames <= 0.0) return null
    return "%.1f".format(java.util.Locale.ROOT, time * 1000.0 / frames)
}

private fun kbps(cur: VideoStatsSample, prev: VideoStatsSample?): String? {
    if (prev == null) return null
    val bytes = delta(cur.bytesSent?.toDouble(), prev.bytesSent?.toDouble()) ?: return null
    val ms = delta(cur.timestampMs, prev.timestampMs) ?: return null
    if (ms <= 0.0) return null
    return Math.round(bytes * 8.0 / ms).toString()
}

// Null when either side is missing or the counter went backwards (reset).
private fun delta(cur: Double?, prev: Double?): Double? {
    if (cur == null || prev == null) return null
    return (cur - prev).takeIf { it >= 0.0 }
}

// Polls `fetch` every `intervalMs` and logs one line per usable report. Holds no
// locks; ends only by cancellation. Fetch failures are logged and skipped.
class VideoStatsSampler(
    private val fetch: suspend () -> Map<String, StatEntry>?,
    private val log: (String) -> Unit,
    private val warn: (String) -> Unit = {},
    private val intervalMs: Long = 3_000,
    private val onSample: (VideoStatsSample) -> Unit = {},
) {
    suspend fun run() {
        var prev: VideoStatsSample? = null
        while (true) {
            val sample = try {
                fetch()?.let(::parseVideoStats)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                warn("video stats sample failed: ${e.javaClass.simpleName}")
                null
            }
            if (sample != null) {
                log(formatVideoStats(sample, prev))
                onSample(sample)
                prev = sample
            }
            delay(intervalMs)
        }
    }
}
