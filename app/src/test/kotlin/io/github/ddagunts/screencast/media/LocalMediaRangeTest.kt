// Fork modification by bearyjd: RFC 9110 single-range policy for the local-media server.
package io.github.ddagunts.screencast.media

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalMediaRangeTest {
    private fun p(h: String?, len: Long = 100) = ByteRangeParser.parse(h, len)

    @Test fun absentOrForeignUnitMeansFullBody() {
        assertEquals(ByteRange.Full, p(null))
        assertEquals(ByteRange.Full, p("items=0-1"))
    }
    @Test fun multiRangeIsAnsweredWithFullBody() = assertEquals(ByteRange.Full, p("bytes=0-1,5-6"))
    @Test fun singleByteAndOpenEnded() {
        assertEquals(ByteRange.Partial(0, 0), p("bytes=0-0"))
        assertEquals(ByteRange.Partial(5, 99), p("bytes=5-"))
        assertEquals(ByteRange.Partial(99, 99), p("bytes=99-"))
    }
    @Test fun suffixRangesAreClampedToFile() {
        assertEquals(ByteRange.Partial(95, 99), p("bytes=-5"))
        assertEquals(ByteRange.Partial(0, 99), p("bytes=-500"))
    }
    @Test fun endBeyondEofIsClamped() = assertEquals(ByteRange.Partial(90, 99), p("bytes=90-500"))
    @Test fun unsatisfiableAndMalformedAre416() {
        for (h in listOf("bytes=100-", "bytes=100-120", "bytes=-0", "bytes=a-b", "bytes=",
            "bytes=-", "bytes=0-99999999999999999999", "bytes=+1-2", "bytes= 1-2x")) {
            assertEquals(h, ByteRange.Unsatisfiable, p(h))
        }
    }
    @Test fun whitespaceAroundIsTolerated() = assertEquals(ByteRange.Partial(1, 2), p("bytes= 1-2 "))
    @Test fun invertedSpecAndOverlongHeaderAreIgnoredPerRfc9110() {
        assertEquals(ByteRange.Full, p("bytes=5-2"))
        assertEquals(ByteRange.Full, p("bytes=" + "9".repeat(300)))
    }
    @Test fun suffixOnEmptyRepresentationIsUnsatisfiable() = assertEquals(ByteRange.Unsatisfiable, p("bytes=-5", 0))
}
