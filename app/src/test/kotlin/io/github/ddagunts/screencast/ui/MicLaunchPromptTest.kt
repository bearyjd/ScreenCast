package io.github.ddagunts.screencast.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class MicLaunchPromptTest {
    @Test fun undeclaredNeverPrompts() =
        assertEquals(MicLaunchAction.NONE, micLaunchAction(declared = false, granted = false, rationaleShown = false))

    @Test fun grantedNeverPrompts() =
        assertEquals(MicLaunchAction.NONE, micLaunchAction(declared = true, granted = true, rationaleShown = false))

    @Test fun firstLaunchExplainsFirst() =
        assertEquals(MicLaunchAction.SHOW_RATIONALE, micLaunchAction(declared = true, granted = false, rationaleShown = false))

    @Test fun neverReasksAfterRationaleDecision() =
        assertEquals(MicLaunchAction.NONE, micLaunchAction(declared = true, granted = false, rationaleShown = true))
}
