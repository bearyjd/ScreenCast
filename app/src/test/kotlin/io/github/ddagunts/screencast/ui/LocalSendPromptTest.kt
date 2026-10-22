// Fork modification by bearyjd: confirmation wording names file, receiver and IP, with untrusted labels sanitised.
package io.github.ddagunts.screencast.ui

import io.github.ddagunts.screencast.cast.CastDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LocalSendPromptTest {
    @Test fun namesFileReceiverAndAddress() =
        assertEquals("Send \"Clip.mp4\" to this receiver?\nReceiver: Living Room TV\nAddress: 192.168.1.50", localSendPrompt("Clip.mp4", CastDevice("Living Room TV", "192.168.1.50", 8009)))
    @Test fun spoofedReceiverNameCannotInjectControlOrBidiCharacters() {
        val text = localSendPrompt("a‮b.mp4", CastDevice("TV\n(10.0.0.1)‮", "192.168.1.50", 8009))
        assertFalse(text.contains('\u202E'))
        val lines = text.lines()
        assertEquals(3, lines.size)
        assertEquals("Address: 192.168.1.50", lines[2])
    }
}
