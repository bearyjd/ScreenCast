package io.github.ddagunts.screencast.cast

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CastLaunchWaitTest {
    @Test fun `receiver session arriving after eight seconds is accepted`() = runBlocking {
        var transportId: String? = null
        var sessionId: String? = null
        launch {
            delay(8_000)
            transportId = "transport-id"
            sessionId = "session-id"
        }

        assertTrue(waitForReceiverSession(hasSession = { transportId != null && sessionId != null }))
    }

    @Test fun `receiver session wait is bounded`() = runBlocking {
        assertFalse(waitForReceiverSession(hasSession = { false }, timeoutMillis = 100))
    }
}
