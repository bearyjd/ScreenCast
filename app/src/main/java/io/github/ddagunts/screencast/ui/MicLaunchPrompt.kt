package io.github.ddagunts.screencast.ui

enum class MicLaunchAction { NONE, SHOW_RATIONALE }

/**
 * Launch-time RECORD_AUDIO decision. The permission is only needed so AudioRecord can capture
 * playback audio; explain that once, then never auto-ask again (Settings still offers a request).
 */
fun micLaunchAction(declared: Boolean, granted: Boolean, rationaleShown: Boolean): MicLaunchAction =
    if (declared && !granted && !rationaleShown) MicLaunchAction.SHOW_RATIONALE else MicLaunchAction.NONE
