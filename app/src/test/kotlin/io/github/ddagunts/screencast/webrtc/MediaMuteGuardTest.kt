package io.github.ddagunts.screencast.webrtc

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

internal class FakeVolume(var muted: Boolean = false, var fixed: Boolean = false, var level: Int = 9) : MediaVolumeControl {
    val events = mutableListOf<String>()
    var failMute: Throwable? = null
    var failUnmute: Throwable? = null
    var failIsMuted: Throwable? = null
    var muteIgnored = false
    var throwAfterMuteTakesEffect: Throwable? = null
    var onMute: () -> Unit = {}
    override fun isVolumeFixed() = fixed
    override fun isMuted(): Boolean { failIsMuted?.let { throw it }; return muted }
    override fun mute() { failMute?.let { throw it }; events += "mute"; onMute(); if (!muteIgnored) muted = true; throwAfterMuteTakesEffect?.let { throw it } }
    override fun unmute() { failUnmute?.let { throw it }; events += "unmute"; muted = false }
}

internal class FakeStore(var marker: MuteMarker? = null) : MuteMarkerStore {
    var failWrite: Throwable? = null
    var failClear: Throwable? = null
    override fun read(): MuteMarker? = marker
    override fun write(marker: MuteMarker) { failWrite?.let { throw it }; this.marker = marker }
    override fun clear() { failClear?.let { throw it }; marker = null }
}

class MediaMuteGuardTest {
    private val vol = FakeVolume()
    private val store = FakeStore()
    private val logs = mutableListOf<String>()
    private var now = 1234L
    private var boot = 7
    private fun guard() = MediaMuteGuard(vol, store, clock = { now }, bootCount = { boot }, log = { logs += it })
    private fun freshMarker(session: String = "old") = MuteMarker(session, boot, now)
    private val sixHours = 6L * 60 * 60 * 1000

    @Test fun `mute writes marker then mutes and restore unmutes and clears`() {
        val g = guard()
        g.mute("s1")
        assertTrue(vol.muted)
        assertEquals(MuteMarker("s1", 7, 1234L), store.marker)
        g.restore()
        assertFalse(vol.muted)
        assertNull(store.marker)
    }

    @Test fun `marker is persisted before the stream is muted`() {
        var markerAtMute: MuteMarker? = null
        vol.onMute = { markerAtMute = store.marker }
        guard().mute("s")
        assertNotNull(markerAtMute)
    }

    @Test fun `already muted stream is left alone and no marker is written`() {
        vol.muted = true
        val g = guard()
        g.mute("s1")
        assertTrue(vol.events.isEmpty())
        assertNull(store.marker)
        g.restore()
        assertTrue(vol.muted)
    }

    @Test fun `user unmuting mid-cast is not undone`() {
        val g = guard()
        g.mute("s1")
        vol.muted = false
        g.restore()
        assertEquals(listOf("mute"), vol.events)
        assertFalse(vol.muted)
        assertNull(store.marker)
    }

    @Test fun `user volume zero without mute flag is never touched`() {
        vol.level = 0
        val g = guard()
        g.mute("s1") // not muted, so we mute and mark; restore then unmutes only the mute flag
        g.restore()
        assertEquals(0, vol.level)
        assertFalse(vol.muted)
    }

    @Test fun `fixed volume device skips the feature`() {
        vol.fixed = true
        guard().mute("s1")
        assertTrue(vol.events.isEmpty())
        assertNull(store.marker)
        assertTrue(logs.any { it.contains("fixed") })
    }

    @Test fun `stale marker with muted stream and idle unmutes and clears`() {
        store.marker = freshMarker()
        vol.muted = true
        guard().recover { false }
        assertFalse(vol.muted)
        assertNull(store.marker)
    }

    @Test fun `stale marker with stream not muted clears only`() {
        store.marker = freshMarker()
        guard().recover { false }
        assertTrue(vol.events.isEmpty())
        assertNull(store.marker)
    }

    @Test fun `recover skips while a cast is active`() {
        store.marker = freshMarker("live")
        vol.muted = true
        guard().recover { true }
        assertTrue(vol.muted)
        assertNotNull(store.marker)
    }

    @Test fun `recover re-checks cast state under the lock right before unmuting`() {
        store.marker = freshMarker("s")
        vol.muted = true
        val answers = ArrayDeque(listOf(true))
        guard().recover { answers.removeFirstOrNull() ?: false }
        assertTrue("single check must be honoured", vol.muted)
    }

    @Test fun `no marker restore is a no-op`() {
        vol.muted = true // user's own mute
        guard().restore()
        assertTrue(vol.events.isEmpty())
        assertTrue(vol.muted)
    }

    @Test fun `restore is idempotent`() {
        val g = guard()
        g.mute("s1")
        g.restore()
        vol.muted = true // user mutes manually afterwards
        g.restore()
        assertTrue(vol.muted)
        assertEquals(listOf("mute", "unmute"), vol.events)
    }

    @Test fun `mute not wanted any more by the time the lock is held does nothing`() {
        guard().mute("s1") { false }
        assertTrue(vol.events.isEmpty())
        assertNull(store.marker)
    }

    @Test fun `marker write failure aborts the mute`() {
        store.failWrite = SecurityException("no")
        guard().mute("s1")
        assertFalse(vol.muted)
        assertTrue(vol.events.isEmpty())
        assertTrue(logs.any { it.contains("marker") })
    }

    @Test fun `mute failure clears the marker and leaves volume state consistent`() {
        vol.failMute = SecurityException("denied")
        guard().mute("s1")
        assertNull(store.marker)
        assertFalse(vol.muted)
        assertTrue(logs.isNotEmpty())
    }

    @Test fun `mute that did not take effect clears the marker`() {
        vol.muteIgnored = true
        guard().mute("s1")
        assertNull(store.marker)
        assertFalse(vol.muted)
    }

    @Test fun `unmute failure keeps marker for retry`() {
        val g = guard()
        g.mute("s1")
        vol.failUnmute = SecurityException("denied")
        g.restore()
        assertTrue(vol.muted)
        assertNotNull(store.marker)
        vol.failUnmute = null
        g.restore()
        assertFalse(vol.muted)
        assertNull(store.marker)
    }

    @Test fun `isMuted failure never throws and keeps marker`() {
        val g = guard()
        g.mute("s1")
        vol.failIsMuted = IllegalStateException("boom")
        g.restore()
        assertTrue(vol.muted)
        assertNotNull(store.marker)
        assertTrue(logs.isNotEmpty())
    }

    @Test fun `marker clear failure after unmute is retried harmlessly`() {
        val g = guard()
        g.mute("s1")
        store.failClear = RuntimeException("disk")
        g.restore()
        assertFalse(vol.muted)
        assertNotNull(store.marker)
        store.failClear = null
        g.restore() // stream no longer muted: clear only, no second unmute
        assertNull(store.marker)
        assertEquals(listOf("mute", "unmute"), vol.events)
    }

    @Test fun `restore cannot interleave with an in-flight mute`() {
        val inMute = CountDownLatch(1)
        val release = CountDownLatch(1)
        vol.onMute = { inMute.countDown(); release.await(5, TimeUnit.SECONDS) }
        val g = guard()
        val a = Thread { g.mute("s1") }.also { it.start() }
        assertTrue(inMute.await(5, TimeUnit.SECONDS))
        val b = Thread { g.restore() }.also { it.start() }
        Thread.sleep(100) // b must be parked on the lock, not racing past an unfinished mute
        release.countDown()
        a.join(5000); b.join(5000)
        assertFalse("restore must see the completed mute and undo it", vol.muted)
        assertNull(store.marker)
        assertEquals(listOf("mute", "unmute"), vol.events)
    }

    @Test fun `marker from another boot is cleared without unmuting`() {
        store.marker = MuteMarker("old", boot - 1, now)
        vol.muted = true // a deliberate later mute by the user
        guard().recover { false }
        assertTrue(vol.muted)
        assertTrue(vol.events.isEmpty())
        assertNull(store.marker)
    }

    @Test fun `expired marker is cleared without unmuting`() {
        store.marker = MuteMarker("old", boot, now)
        now += sixHours + 1
        vol.muted = true
        guard().recover { false }
        assertTrue(vol.muted)
        assertNull(store.marker)
    }

    @Test fun `marker just inside the expiry window still unmutes`() {
        store.marker = MuteMarker("old", boot, now)
        now += sixHours
        vol.muted = true
        guard().recover { false }
        assertFalse(vol.muted)
        assertNull(store.marker)
    }

    @Test fun `elapsed clock regression clears without unmuting`() {
        store.marker = MuteMarker("old", boot, now + 5000)
        vol.muted = true
        guard().recover { false }
        assertTrue(vol.muted)
        assertNull(store.marker)
    }

    @Test fun `old format marker without boot count is stale`() {
        store.marker = MuteMarker("old", -1, now)
        vol.muted = true
        guard().recover { false }
        assertTrue(vol.muted)
        assertNull(store.marker)
    }

    @Test fun `unreadable current boot count is stale`() {
        store.marker = freshMarker()
        vol.muted = true
        boot = -1
        guard().recover { false }
        assertTrue(vol.muted)
        assertNull(store.marker)
    }

    @Test fun `mute that throws after taking effect keeps the marker`() {
        vol.throwAfterMuteTakesEffect = SecurityException("late")
        val g = guard()
        g.mute("s1")
        assertTrue(vol.muted)
        assertNotNull(store.marker)
        vol.throwAfterMuteTakesEffect = null
        g.restore()
        assertFalse(vol.muted)
        assertNull(store.marker)
    }

    @Test fun `verification read failing after mute keeps the marker`() {
        vol.onMute = { vol.failIsMuted = IllegalStateException("boom") }
        guard().mute("s1")
        assertNotNull(store.marker)
    }
}
