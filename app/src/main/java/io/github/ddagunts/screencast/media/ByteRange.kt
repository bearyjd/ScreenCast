// Fork modification by bearyjd: single-range parsing for the local-media server (RFC 9110 section 14).
package io.github.ddagunts.screencast.media

sealed interface ByteRange {
    /** Serve the whole representation with 200. */
    data object Full : ByteRange
    data class Partial(val start: Long, val end: Long) : ByteRange
    /** Respond 416 with `Content-Range: bytes *` slash length. */
    data object Unsatisfiable : ByteRange
}

// Policy: absent header or a non-"bytes" unit is ignored (200). A multi-range request is ignored too (200 full),
// which RFC 9110 permits and avoids multipart responses. An inverted spec (5-2) or over-long header is ignored (200).
// Non-numeric specs and unsatisfiable single ranges (start past EOF, suffix 0, any suffix on an empty body) get 416.
object ByteRangeParser {
    private const val MAX_HEADER = 128
    private val digits = Regex("[0-9]{1,18}")

    fun parse(header: String?, length: Long): ByteRange {
        if (header == null) return ByteRange.Full
        val trimmed = header.trim()
        if (!trimmed.startsWith("bytes=", ignoreCase = true)) return ByteRange.Full
        if (trimmed.length > MAX_HEADER) return ByteRange.Full
        val spec = trimmed.substring(6).trim()
        if (',' in spec) return ByteRange.Full
        val dash = spec.indexOf('-')
        if (dash < 0) return ByteRange.Unsatisfiable
        val first = spec.substring(0, dash).trim()
        val last = spec.substring(dash + 1).trim()
        if (first.isEmpty()) return suffix(last, length)
        if (!digits.matches(first)) return ByteRange.Unsatisfiable
        val start = first.toLong()
        if (start >= length) return ByteRange.Unsatisfiable
        if (last.isEmpty()) return ByteRange.Partial(start, length - 1)
        if (!digits.matches(last)) return ByteRange.Unsatisfiable
        val end = last.toLong()
        return if (end < start) ByteRange.Full else ByteRange.Partial(start, minOf(end, length - 1))
    }

    private fun suffix(last: String, length: Long): ByteRange {
        if (!digits.matches(last)) return ByteRange.Unsatisfiable
        val n = last.toLong()
        return if (n == 0L || length <= 0L) ByteRange.Unsatisfiable else ByteRange.Partial(maxOf(0L, length - n), length - 1)
    }
}
