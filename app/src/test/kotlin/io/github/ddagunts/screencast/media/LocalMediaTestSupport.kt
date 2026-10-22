// Fork modification by bearyjd: raw-socket HTTP client and fake sources for local-media server tests.
package io.github.ddagunts.screencast.media

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

class RawResponse(val status: Int, val headers: Map<String, String>, val body: ByteArray)

/** Minimal HTTP/1.1 client: lets tests send Origin/Range headers and any method/path verbatim. */
fun rawHttp(port: Int, method: String, path: String, headers: Map<String, String> = emptyMap()): RawResponse {
    Socket("127.0.0.1", port).use { socket ->
        socket.soTimeout = 10_000
        val request = buildString {
            append("$method $path HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nConnection: close\r\n")
            headers.forEach { (k, v) -> append("$k: $v\r\n") }
            if (method in setOf("POST", "PUT", "DELETE")) append("Content-Length: 0\r\n")
            append("\r\n")
        }
        socket.getOutputStream().apply { write(request.toByteArray(Charsets.ISO_8859_1)); flush() }
        val raw = ByteArrayOutputStream().also { socket.getInputStream().copyTo(it) }.toByteArray()
        val split = indexOfHeaderEnd(raw)
        val head = String(raw, 0, split, Charsets.ISO_8859_1).split("\r\n")
        val status = head.first().split(' ')[1].toInt()
        val parsed = head.drop(1).filter { ':' in it }.associate {
            it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim()
        }
        return RawResponse(status, parsed, raw.copyOfRange(split + 4, raw.size))
    }
}

private fun indexOfHeaderEnd(raw: ByteArray): Int {
    for (i in 0..raw.size - 4) {
        if (raw[i] == '\r'.code.toByte() && raw[i + 1] == '\n'.code.toByte() &&
            raw[i + 2] == '\r'.code.toByte() && raw[i + 3] == '\n'.code.toByte()) return i
    }
    throw IOException("no header terminator")
}

/** Counts closes so tests can prove the fd-owning source is released exactly once. */
class FakeSource(private val bytes: ByteArray, private val gate: CountDownLatch? = null) : LocalMediaSource {
    val closes = AtomicInteger()
    @Volatile var maxChunk = 0
    override val length: Long get() = bytes.size.toLong()
    override fun readAt(dst: ByteBuffer, position: Long): Int {
        gate?.await()
        if (position >= bytes.size) return -1
        maxChunk = maxOf(maxChunk, dst.remaining())
        val n = minOf(dst.remaining().toLong(), bytes.size - position).toInt()
        dst.put(bytes, position.toInt(), n)
        return n
    }
    override fun close() { closes.incrementAndGet() }
}

fun tempMediaFile(size: Int): Pair<File, ByteArray> {
    val data = ByteArray(size).also { java.util.Random(42).nextBytes(it) }
    val file = File.createTempFile("local-media", ".mp4").apply { deleteOnExit(); writeBytes(data) }
    return file to data
}

fun fileSource(file: File, onClose: () -> Unit = {}): LocalMediaSource =
    FileChannelMediaSource(RandomAccessFile(file, "r").channel, onClose)

class FakeClock(var now: Long = 1_000L) : () -> Long {
    override fun invoke() = now
}

/** Endless-looking zero-filled source for disconnect tests (no allocation proportional to length). */
class ZeroSource(override val length: Long) : LocalMediaSource {
    override fun readAt(dst: ByteBuffer, position: Long): Int {
        if (position >= length) return -1
        val n = minOf(dst.remaining().toLong(), length - position).toInt()
        dst.put(ByteArray(n)); return n
    }
    override fun close() {}
}

/** Claims [length] bytes but reports end-of-file at [actual]. */
class ShortSource(override val length: Long, private val actual: Long) : LocalMediaSource {
    override fun readAt(dst: ByteBuffer, position: Long): Int {
        if (position >= actual) return -1
        val n = minOf(dst.remaining().toLong(), actual - position).toInt()
        dst.put(ByteArray(n)); return n
    }
    override fun close() {}
}
