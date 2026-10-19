package io.github.ddagunts.screencast.ui

import android.app.Application
import android.content.Intent
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MediaShareActivityTest {
    @Test fun shareEntryAndNewIntentOnlyPreviewAndNeverStartCapture() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val share = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "https://example/one.mp4")
        val resolved = app.packageManager.queryIntentActivities(share, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
        assertTrue(resolved.any { it.activityInfo.name == MediaShareActivity::class.java.name })
        val activityController = Robolectric.buildActivity(MediaShareActivity::class.java, share).create()
        val activity = activityController.get()
        val vm = ViewModelProvider(activity)[MediaShareViewModel::class.java]
        assertEquals("https://example/one.mp4", vm.controller.state.value.media?.url)
        assertNull(vm.controller.state.value.active)
        assertFalse(vm.controller.state.value.canCast)
        activityController.newIntent(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "https://example/two.mp3"))
        assertEquals("https://example/two.mp3", vm.controller.state.value.media?.url)
        vm.receiveInitial(share)
        assertEquals("https://example/two.mp3", vm.controller.state.value.media?.url)
        assertNull(shadowOf(app).nextStartedActivity)
        assertNull(shadowOf(app).nextStartedService)
        assertNull(shadowOf(activity).lastRequestedPermission)
        activityController.destroy()
    }
}
