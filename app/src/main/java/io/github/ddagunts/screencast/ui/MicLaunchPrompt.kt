package io.github.ddagunts.screencast.ui

import io.github.ddagunts.screencast.webrtc.PermissionAction
import io.github.ddagunts.screencast.webrtc.permissionAction

enum class MicLaunchAction { NONE, SHOW_RATIONALE }

/** Playback capture needs API 29; below that HLS streams silence, so never ask there. */
private const val MIN_PLAYBACK_CAPTURE_SDK = 29

/**
 * Launch-time RECORD_AUDIO decision. The permission is only needed so AudioRecord can capture
 * playback audio; explain that once, then never auto-ask again (Settings still offers a request).
 */
fun micLaunchAction(declared: Boolean, granted: Boolean, rationaleShown: Boolean, sdkInt: Int): MicLaunchAction =
    if (sdkInt >= MIN_PLAYBACK_CAPTURE_SDK && declared && !granted && !rationaleShown) MicLaunchAction.SHOW_RATIONALE else MicLaunchAction.NONE

enum class MicDialogEffect { NONE, MARK_SHOWN, MARK_SHOWN_AND_REQUEST }

sealed interface MicDialogEvent {
    data class Evaluate(val action: MicLaunchAction) : MicDialogEvent
    data object Continue : MicDialogEvent
    data object NotNow : MicDialogEvent
    /** Back / tap-outside: counts as Not now. */
    data object Dismiss : MicDialogEvent
}

data class MicDialogStep(val visible: Boolean, val effect: MicDialogEffect)

/**
 * Rationale dialog state machine. The "shown" flag is persisted only by an answer (MARK_SHOWN*), never
 * on display, so a recreation before answering shows the dialog again. Answers while hidden are no-ops
 * (re-entry guard), and evaluating while visible never stacks a second dialog.
 */
fun micDialogReduce(visible: Boolean, event: MicDialogEvent): MicDialogStep = when (event) {
    is MicDialogEvent.Evaluate ->
        MicDialogStep(visible || event.action == MicLaunchAction.SHOW_RATIONALE, MicDialogEffect.NONE)
    MicDialogEvent.Continue ->
        if (visible) MicDialogStep(false, MicDialogEffect.MARK_SHOWN_AND_REQUEST) else MicDialogStep(false, MicDialogEffect.NONE)
    MicDialogEvent.NotNow, MicDialogEvent.Dismiss ->
        if (visible) MicDialogStep(false, MicDialogEffect.MARK_SHOWN) else MicDialogStep(false, MicDialogEffect.NONE)
}

data class MicBanner(val message: String, val action: PermissionAction, val buttonLabel: String)

/**
 * Recovery path after "Not now": shown on the main screen while phone audio cannot be captured.
 * Switches to "Open app settings" once the system will no longer show the prompt.
 */
fun micBanner(
    sdkInt: Int, declared: Boolean, granted: Boolean, rationaleShown: Boolean,
    askedBefore: Boolean, shouldShowRationale: Boolean,
): MicBanner? {
    if (sdkInt < MIN_PLAYBACK_CAPTURE_SDK || !declared || !rationaleShown) return null
    return when (permissionAction(granted, askedBefore, shouldShowRationale)) {
        PermissionAction.NONE -> null
        PermissionAction.REQUEST -> MicBanner("Phone audio isn't shared with the TV.", PermissionAction.REQUEST, "Allow")
        PermissionAction.OPEN_SETTINGS -> MicBanner(
            "Microphone permission is blocked, so phone audio isn't shared. Turn it on in app settings.",
            PermissionAction.OPEN_SETTINGS, "Open app settings",
        )
    }
}
