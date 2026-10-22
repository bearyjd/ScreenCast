// Fork modification by bearyjd: bounded positional-read -> write loop with a per-write timeout.
package io.github.ddagunts.screencast.media

import java.io.IOException
import java.nio.ByteBuffer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Copies bytes [start]..[end] (inclusive) of [source] through [write] in chunks of at most [bufferBytes].
 * A write that makes no progress for [writeTimeoutMs] (half-open client) fails with IOException so the caller's
 * permit is released; the timeout is converted so it is never mistaken for coroutine cancellation.
 */
internal suspend fun pumpRange(
    source: LocalMediaSource, start: Long, end: Long, bufferBytes: Int, writeTimeoutMs: Long,
    readDispatcher: CoroutineDispatcher, onProgress: () -> Unit, write: suspend (ByteArray, Int, Int) -> Unit,
) {
    val buf = ByteArray(minOf(bufferBytes.toLong(), end - start + 1).toInt())
    val bb = ByteBuffer.wrap(buf)
    var pos = start
    while (pos <= end) {
        bb.clear().limit(minOf(buf.size.toLong(), end - pos + 1).toInt())
        val n = withContext(readDispatcher) { source.readAt(bb, pos) }
        // File shrank or fd died mid-response: abort the connection rather than send a lying body.
        if (n <= 0) throw IOException("source ended early")
        try { withTimeout(writeTimeoutMs) { write(buf, 0, n) } }
        catch (e: TimeoutCancellationException) { throw IOException("write stalled", e) }
        onProgress()
        pos += n
    }
}
