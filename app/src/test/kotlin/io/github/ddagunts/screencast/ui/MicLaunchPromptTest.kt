package io.github.ddagunts.screencast.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class MicLaunchPromptTest {
    @Test fun undeclaredNeverPrompts() =
        assertEquals(MicLaunchAction.NONE, micLaunchAction(declared = false, granted = false, rationaleShown = false, sdkInt = 34))

    @Test fun grantedNeverPrompts() =
        assertEquals(MicLaunchAction.NONE, micLaunchAction(declared = true, granted = true, rationaleShown = false, sdkInt = 34))

    @Test fun firstLaunchExplainsFirst() =
        assertEquals(MicLaunchAction.SHOW_RATIONALE, micLaunchAction(declared = true, granted = false, rationaleShown = false, sdkInt = 34))

    @Test fun neverReasksAfterRationaleDecision() =
        assertEquals(MicLaunchAction.NONE, micLaunchAction(declared = true, granted = false, rationaleShown = true, sdkInt = 34))

    @Test fun belowApi29NeverPrompts() =
        assertEquals(MicLaunchAction.NONE, micLaunchAction(declared = true, granted = false, rationaleShown = false, sdkInt = 28))

    @Test fun api29Prompts() =
        assertEquals(MicLaunchAction.SHOW_RATIONALE, micLaunchAction(declared = true, granted = false, rationaleShown = false, sdkInt = 29))
}
