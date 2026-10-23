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
