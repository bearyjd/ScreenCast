package io.github.ddagunts.screencast.ui

import io.github.ddagunts.screencast.webrtc.PermissionAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class MicDialogStateTest {
    private fun show() = micDialogReduce(false, MicDialogEvent.Evaluate(MicLaunchAction.SHOW_RATIONALE))

    @Test fun evaluateShowsDialogWithoutPersisting() {
        val s = show()
        assertEquals(MicDialogStep(true, MicDialogEffect.NONE), s)
    }

    @Test fun recreateBeforeAnsweringShowsAgain() {
        // The flag is not written until an answer, so re-evaluating on a fresh activity shows it again.
        val first = show()
        assertEquals(MicDialogEffect.NONE, first.effect)
        assertEquals(true, show().visible)
    }

    @Test fun reentryWhileVisibleDoesNotStackOrPersist() =
        assertEquals(MicDialogStep(true, MicDialogEffect.NONE),
            micDialogReduce(true, MicDialogEvent.Evaluate(MicLaunchAction.SHOW_RATIONALE)))

    @Test fun alreadyGrantedOrShownKeepsHidden() =
        assertEquals(MicDialogStep(false, MicDialogEffect.NONE),
            micDialogReduce(false, MicDialogEvent.Evaluate(MicLaunchAction.NONE)))

    @Test fun grantPathContinueHidesPersistsAndRequests() =
        assertEquals(MicDialogStep(false, MicDialogEffect.MARK_SHOWN_AND_REQUEST), micDialogReduce(true, MicDialogEvent.Continue))

    @Test fun notNowHidesAndPersists() =
        assertEquals(MicDialogStep(false, MicDialogEffect.MARK_SHOWN), micDialogReduce(true, MicDialogEvent.NotNow))

    @Test fun dismissIsNotNow() =
        assertEquals(micDialogReduce(true, MicDialogEvent.NotNow), micDialogReduce(true, MicDialogEvent.Dismiss))

    @Test fun doubleTapContinueOnlyActsOnce() =
        assertEquals(MicDialogStep(false, MicDialogEffect.NONE), micDialogReduce(false, MicDialogEvent.Continue))

    @Test fun noLateAnswerWhenHidden() {
        assertEquals(MicDialogEffect.NONE, micDialogReduce(false, MicDialogEvent.NotNow).effect)
        assertEquals(MicDialogEffect.NONE, micDialogReduce(false, MicDialogEvent.Dismiss).effect)
    }
}

class MicBannerTest {
    private fun banner(
        sdk: Int = 34, declared: Boolean = true, granted: Boolean = false, shown: Boolean = true,
        asked: Boolean = false, rationale: Boolean = false,
    ) = micBanner(sdk, declared, granted, shown, asked, rationale)

    @Test fun notNowThenBannerOffersAllow() {
        val b = banner()
        assertNotNull(b)
        assertEquals(PermissionAction.REQUEST, b!!.action)
        assertEquals("Allow", b.buttonLabel)
    }

    @Test fun permanentDenialSwitchesToOpenSettings() {
        val b = banner(asked = true, rationale = false)!!
        assertEquals(PermissionAction.OPEN_SETTINGS, b.action)
        assertEquals("Open app settings", b.buttonLabel)
    }

    @Test fun softDenialStillOffersAllow() =
        assertEquals(PermissionAction.REQUEST, banner(asked = true, rationale = true)!!.action)

    @Test fun hiddenWhenGranted() = assertNull(banner(granted = true))
    @Test fun hiddenBeforeRationaleAnswered() = assertNull(banner(shown = false))
    @Test fun hiddenBelowApi29() = assertNull(banner(sdk = 28))
    @Test fun hiddenWhenPermissionNotDeclared() = assertNull(banner(declared = false))
}
