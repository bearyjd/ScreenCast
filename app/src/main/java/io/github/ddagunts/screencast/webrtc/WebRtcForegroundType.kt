package io.github.ddagunts.screencast.webrtc

import android.content.pm.ServiceInfo

/**
 * Foreground service type for the WebRTC cast service. The MICROPHONE type is added ONLY when
 * the user opted into the mic mix AND RECORD_AUDIO is granted (Android 14+ throws otherwise) AND
 * the OS has the type (API 30+). Everything else keeps today's type, so default behavior is unchanged.
 */
internal fun foregroundServiceType(micMix: Boolean, micPermissionGranted: Boolean, sdkInt: Int): Int = when {
    sdkInt < 29 -> 0
    micMix && micPermissionGranted && sdkInt >= 30 ->
        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
    else -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
}

/**
 * Calls [start] with [micType]; if the system refuses it (SecurityException,
 * ForegroundServiceStartNotAllowedException, ...) calls it again with [baseType] and returns false.
 * Returns true only when the mic type was accepted. When no mic type was requested
 * ([micType] == [baseType]) it makes one base call and returns false. A failure of the base call propagates,
 * exactly as before the mic mix existed.
 */
internal fun startForegroundWithMicFallback(micType: Int, baseType: Int, start: (Int) -> Unit, log: (String) -> Unit): Boolean {
    if (micType != baseType) {
        try {
            start(micType)
            return true
        } catch (e: RuntimeException) {
            log("webrtc: microphone foreground type refused ($e); mic mix off, playback audio only")
        }
    }
    start(baseType)
    return false
}
