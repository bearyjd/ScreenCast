// Fork modification by bearyjd: one share's claim on the shared host; safe against stop/cancel racing begin().
package io.github.ddagunts.screencast.media

/**
 * Created per cast session. [release] before [attach] makes the later [attach] return false (the caller then stops the
 * just-created session itself); [release] after [attach] stops exactly the session this claim owns, never a newer one.
 */
class ServingClaim(private val host: LocalMediaHost) {
    private var id: Long? = null
    private var released = false

    @Synchronized fun attach(sessionId: Long): Boolean {
        if (released) return false
        id = sessionId; return true
    }

    fun release() {
        val owned = synchronized(this) { released = true; id.also { id = null } }
        owned?.let { host.stop(it) }
    }
}
