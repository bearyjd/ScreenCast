// Fork modification by bearyjd: makes native stats calls and peer disposal mutually exclusive.
package io.github.ddagunts.screencast.webrtc

// Wraps calls into native code. run() refuses to start once closeAndAwait() has
// begun; closeAndAwait() blocks (bounded) until in-flight calls have returned.
// The monitor is never held while the block runs, only for counter updates.
class StatsCallGuard {
    private val lock = Object()
    private var closing = false
    private var inFlight = 0

    fun <T> run(block: () -> T): T? {
        synchronized(lock) {
            if (closing) return null
            inFlight++
        }
        try {
            return block()
        } finally {
            synchronized(lock) {
                inFlight--
                lock.notifyAll()
            }
        }
    }

    // Returns true when no call is in flight on return, false if the bound elapsed.
    fun closeAndAwait(timeoutMs: Long): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        synchronized(lock) {
            closing = true
            while (inFlight > 0) {
                val remainingMs = (deadline - System.nanoTime()) / 1_000_000
                if (remainingMs <= 0) return false
                try {
                    lock.wait(remainingMs)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return inFlight == 0
                }
            }
            return true
        }
    }
}
