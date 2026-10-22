// Fork modification by bearyjd: positional-read view of the one shared file; owns (and closes) its descriptor.
package io.github.ddagunts.screencast.media

import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.atomic.AtomicBoolean

interface LocalMediaSource : Closeable {
    val length: Long
    /** Thread-safe read at an absolute position; never moves a shared cursor. */
    fun readAt(dst: ByteBuffer, position: Long): Int
}

/** Creates independent (dup'd) sources so the original grant-holding fd can outlive or predecease them. */
interface LocalMediaHandle : Closeable {
    fun openSource(): LocalMediaSource?
}

class FileChannelMediaSource(private val channel: FileChannel, private val onClose: () -> Unit = {}) : LocalMediaSource {
    private val closed = AtomicBoolean(false)
    override val length: Long = runCatching { channel.size() }.getOrDefault(-1L)
    override fun readAt(dst: ByteBuffer, position: Long): Int = channel.read(dst, position)
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try { channel.close() } finally { onClose() }
    }
}
