// Fork modification by bearyjd: per-write timeout so a half-open client cannot hold a permit forever.
package io.github.ddagunts.screencast.media

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class LocalMediaPumpTest {
    @Test fun copiesExactRangeInBoundedChunks() = runTest {
        val data = ByteArray(1000) { it.toByte() }
        val out = java.io.ByteArrayOutputStream(); var maxChunk = 0
        pumpRange(FakeSource(data), 100, 899, 64, 1_000, Dispatchers.Unconfined, { }) { b, o, n -> maxChunk = maxOf(maxChunk, n); out.write(b, o, n) }
        assertArrayEquals(data.copyOfRange(100, 900), out.toByteArray())
        assertTrue(maxChunk <= 64)
    }
    @Test fun stalledWriterTimesOutWithIoExceptionNotCancellation() = runTest {
        val outcome = CompletableDeferred<Throwable?>()
        val job = launch {
            outcome.complete(try { pumpRange(FakeSource(ByteArray(100)), 0, 99, 64, 5_000, Dispatchers.Unconfined, { }) { _, _, _ -> awaitCancellation() }; null }
                catch (t: Throwable) { t })
        }
        runCurrent(); advanceTimeBy(4_999); runCurrent()
        assertFalse(outcome.isCompleted)
        advanceTimeBy(2); runCurrent()
        assertTrue(outcome.await() is IOException)
        job.join()
    }
    @Test fun progressIsReportedPerWrite() = runTest {
        var ticks = 0
        pumpRange(FakeSource(ByteArray(300)), 0, 299, 100, 1_000, Dispatchers.Unconfined, { ticks++ }) { _, _, _ -> }
        assertEquals(3, ticks)
    }
    @Test fun earlyEndOfSourceFails() = runTest {
        assertThrows(IOException::class.java) { kotlinx.coroutines.runBlocking {
            pumpRange(ShortSource(1000, 10), 0, 999, 64, 1_000, Dispatchers.Unconfined, { }) { _, _, _ -> } } }
    }
}
