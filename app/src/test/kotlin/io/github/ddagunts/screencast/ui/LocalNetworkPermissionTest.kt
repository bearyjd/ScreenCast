package io.github.ddagunts.screencast.ui

import android.app.Application
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalNetworkPermissionTest {
    @Test fun definedDangerousUngrantedRequests() =
        assertTrue(shouldRequestLocalNetwork(defined = true, dangerous = true, granted = false))

    @Test fun undefinedNeverRequests() =
        assertFalse(shouldRequestLocalNetwork(defined = false, dangerous = true, granted = false))

    @Test fun normalProtectionNeverRequests() =
        assertFalse(shouldRequestLocalNetwork(defined = true, dangerous = false, granted = false))

    @Test fun grantedNeverRequests() =
        assertFalse(shouldRequestLocalNetwork(defined = true, dangerous = true, granted = true))
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocalNetworkPermissionPlatformTest {
    private fun info(level: Int) = PermissionInfo().apply {
        name = ACCESS_LOCAL_NETWORK
        @Suppress("DEPRECATION") protectionLevel = level
    }

    @Test fun dangerousUngrantedRequests() =
        assertEquals(ACCESS_LOCAL_NETWORK, resolveLocalNetworkRequest({ info(PermissionInfo.PROTECTION_DANGEROUS) }, { false }))

    @Test fun normalIsNotRequested() =
        assertNull(resolveLocalNetworkRequest({ info(PermissionInfo.PROTECTION_NORMAL) }, { false }))

    @Test fun grantedIsNotRequested() =
        assertNull(resolveLocalNetworkRequest({ info(PermissionInfo.PROTECTION_DANGEROUS) }, { true }))

    @Test fun undefinedIsNotRequested() =
        assertNull(resolveLocalNetworkRequest({ null }, { false }))

    @Test fun lookupExceptionIsNotRequested() {
        assertNull(resolveLocalNetworkRequest({ throw RuntimeException("pm died") }, { false }))
        assertNull(resolveLocalNetworkRequest({ throw PackageManager.NameNotFoundException() }, { false }))
    }

    @Test fun realPackageManagerWithoutDefinitionIsNotRequested() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        assertNull(app.localNetworkPermissionToRequest())
    }

    @Test fun realPackageManagerWithDangerousDefinitionRequests() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app.packageManager).addPermissionInfo(info(PermissionInfo.PROTECTION_DANGEROUS))
        assertEquals(ACCESS_LOCAL_NETWORK, app.localNetworkPermissionToRequest())
    }
}
