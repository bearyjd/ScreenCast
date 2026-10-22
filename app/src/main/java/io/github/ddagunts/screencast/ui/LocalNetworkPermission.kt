package io.github.ddagunts.screencast.ui

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import androidx.core.content.ContextCompat

// Android 16+ gates LAN access (incl. mDNS) behind ACCESS_LOCAL_NETWORK. The
// constant is a string so we compile against older platforms; older releases
// don't define the permission and must not be asked for it.
const val ACCESS_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"

fun shouldRequestLocalNetwork(defined: Boolean, dangerous: Boolean, granted: Boolean): Boolean =
    defined && dangerous && !granted

/** The permission to request now, or null if the platform doesn't define it as a runtime permission or it is held. */
fun Context.localNetworkPermissionToRequest(): String? = resolveLocalNetworkRequest(
    getInfo = {
        try {
            packageManager.getPermissionInfo(ACCESS_LOCAL_NETWORK, 0)
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    },
    isGranted = {
        ContextCompat.checkSelfPermission(this, ACCESS_LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED
    },
)

// Any failure looking the permission up means "don't ask": a prompt for a
// permission we can't classify is worse than none.
internal fun resolveLocalNetworkRequest(getInfo: () -> PermissionInfo?, isGranted: () -> Boolean): String? = try {
    val info = getInfo()
    @Suppress("DEPRECATION")
    val dangerous = info != null &&
        (info.protectionLevel and PermissionInfo.PROTECTION_MASK_BASE) == PermissionInfo.PROTECTION_DANGEROUS
    ACCESS_LOCAL_NETWORK.takeIf { shouldRequestLocalNetwork(info != null, dangerous, isGranted()) }
} catch (e: Exception) {
    null
}
