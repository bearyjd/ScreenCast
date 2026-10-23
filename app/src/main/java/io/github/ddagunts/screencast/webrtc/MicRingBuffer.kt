package io.github.ddagunts.screencast.webrtc

/**
 * Bounded mono PCM16 FIFO between the mic reader thread (producer) and the ADM audio
 * thread (consumer). Never blocks on data: a full buffer drops the OLDEST samples
 * (counted in [droppedSamples]), an empty buffer returns fewer samples (underrun).
 * Locking is a plain monitor held only for an array copy of at most one callback's worth
 * of samples (10-20 ms of PCM), so the audio thread is never parked on I/O.
 */
internal class MicRingBuffer(val capacity: Int) {
    init { require(capacity > 0) }
    private val lock = Any()
    private val data = ShortArray(capacity)
    private var head = 0 // next read index
    private var size = 0
    private var dropped = 0L

    val available: Int get() = synchronized(lock) { size }
    val droppedSamples: Long get() = synchronized(lock) { dropped }

    fun write(src: ShortArray, count: Int) {
        val n = count.coerceIn(0, src.size)
        synchronized(lock) {
            val skip = maxOf(0, n - capacity) // only the newest [capacity] samples can survive
            var from = skip
            dropped += skip
            val keep = n - skip
            val overflow = size + keep - capacity
            if (overflow > 0) { head = (head + overflow) % capacity; size -= overflow; dropped += overflow }
            var w = (head + size) % capacity
            while (from < n) { data[w] = src[from++]; w = if (w + 1 == capacity) 0 else w + 1 }
            size += keep
        }
    }

    /** Copies up to [count] samples into [dst]; returns how many were available (possibly 0). */
    fun read(dst: ShortArray, count: Int): Int = synchronized(lock) {
        val n = minOf(count.coerceAtLeast(0), size, dst.size)
        for (i in 0 until n) dst[i] = data[(head + i) % capacity]
        head = (head + n) % capacity
        size -= n
        n
    }

    /** Drops the OLDEST samples until at most [target] remain (counted in [droppedSamples]). */
    fun trimTo(target: Int) = synchronized(lock) {
        val excess = size - target.coerceAtLeast(0)
        if (excess > 0) { head = (head + excess) % capacity; size -= excess; dropped += excess }
    }

    fun clear() = synchronized(lock) { head = 0; size = 0 }
}
