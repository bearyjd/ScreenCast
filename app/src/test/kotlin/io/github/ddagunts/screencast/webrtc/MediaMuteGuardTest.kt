package io.github.ddagunts.screencast.webrtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeVolume(var volume: Int) : MediaVolumeControl {
    val sets = mutableListOf<Int>()
    var failGet: Throwable? = null
    var failSet: Throwable? = null
    override fun getMediaVolume(): Int { failGet?.let { throw it }; return volume }
    override fun setMediaVolume(level: Int) { failSet?.let { throw it }; sets += level; volume = level }
}

private class FakeStore(var marker: MuteMarker? = null) : MuteMarkerStore {
    var failWrite: Throwable? = null
    override fun read(): MuteMarker? = marker
    override fun write(marker: MuteMarker) { failWrite?.let { throw it }; this.marker = marker }
    override fun clear() { marker = null }
}

class MediaMuteGuardTest {
    private val vol = FakeVolume(9)
    private val store = FakeStore()
    private val logs = mutableListOf<String>()
    private fun guard() =
        MediaMuteGuard(vol, store, clock = { 1234L }, log = { logs += it })

    @Test fun `mute records marker before lowering and restore puts volume back`() {
        val g = guard()
        g.mute("s1")
        assertEquals(0, vol.volume)
        assertEquals(MuteMarker(9, "s1", 1234L), store.marker)
        g.restore()
        assertEquals(9, vol.volume)
        assertNull(store.marker)
    }

    @Test fun `marker is persisted before volume is changed`() {
        var markerAtSet: MuteMarker? = null
        val v = object : MediaVolumeControl {
            override fun getMediaVolume() = 5
            override fun setMediaVolume(level: Int) { markerAtSet = store.marker }
        }
        MediaMuteGuard(v, store, { 1L }, {}).mute("s")
        assertNotNull(markerAtSet)
    }

    @Test fun `user changed volume during cast is not clobbered`() {
        val g = guard()
        g.mute("s1")
        vol.volume = 4
        g.restore()
        assertEquals(4, vol.volume)
        assertNull(store.marker)
    }

    @Test fun `crash recovery restores from marker with a fresh guard`() {
        guard().mute("s1")
        val fresh = guard()
        fresh.restore()
        assertEquals(9, vol.volume)
        assertNull(store.marker)
    }

    @Test fun `no marker restore is a no-op`() {
        guard().restore()
        assertTrue(vol.sets.isEmpty())
    }

    @Test fun `restore is idempotent`() {
        val g = guard()
        g.mute("s1")
        g.restore()
        vol.volume = 0 // user mutes manually afterwards
        g.restore()
        assertEquals(0, vol.volume)
        assertEquals(listOf(0, 9), vol.sets)
    }

    @Test fun `already silent media volume writes no marker`() {
        vol.volume = 0
        guard().mute("s1")
        assertNull(store.marker)
        assertTrue(vol.sets.isEmpty())
    }

    @Test fun `stale marker is restored before a new mute so 0 is never recorded as original`() {
        guard().mute("old") // crash: volume 0, marker 9
        val g = guard()
        g.mute("new")
        assertEquals(MuteMarker(9, "new", 1234L), store.marker)
        g.restore()
        assertEquals(9, vol.volume)
    }

    @Test fun `marker write failure means volume is not touched`() {
        store.failWrite = SecurityException("no")
        guard().mute("s1")
        assertEquals(9, vol.volume)
        assertTrue(logs.any { it.contains("marker") })
    }

    @Test fun `set volume failure on mute clears marker and does not throw`() {
        vol.failSet = SecurityException("denied")
        guard().mute("s1")
        assertNull(store.marker)
        assertEquals(9, vol.volume)
        assertTrue(logs.isNotEmpty())
    }

    @Test fun `set volume failure on restore keeps marker for retry and does not throw`() {
        val g = guard()
        g.mute("s1")
        vol.failSet = SecurityException("denied")
        g.restore()
        assertNotNull(store.marker)
        vol.failSet = null
        g.restore()
        assertEquals(9, vol.volume)
        assertNull(store.marker)
    }

    @Test fun `get volume failure never throws`() {
        vol.failGet = IllegalStateException("boom")
        val g = guard()
        g.mute("s1")
        g.restore()
        assertTrue(logs.isNotEmpty())
    }

    @Test fun `corrupt negative marker is cleared without touching volume`() {
        store.marker = MuteMarker(-1, "x", 0L)
        guard().restore()
        assertNull(store.marker)
        assertTrue(vol.sets.isEmpty())
    }

    @Test fun `recoverIfIdle skips while a cast is active`() {
        guard().mute("s1")
        guard().recoverIfIdle(castActive = true)
        assertEquals(0, vol.volume)
        guard().recoverIfIdle(castActive = false)
        assertEquals(9, vol.volume)
    }
}
