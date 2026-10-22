// Fork modification by bearyjd: ViewModel wiring of local shares (accept-time rejection, URL path unchanged).
package io.github.ddagunts.screencast.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import io.github.ddagunts.screencast.util.logI
import io.github.ddagunts.screencast.util.LogRepository
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MediaShareLocalViewModelTest {
    private fun vm() = MediaShareViewModel(ApplicationProvider.getApplicationContext<Application>())

    @Test fun fileSchemeShareIsRejectedWithoutTouchingTheFile() {
        val vm = vm()
        vm.receive(Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM, Uri.parse("file:///data/data/x/secret.mp4")))
        assertNull(vm.controller.state.value.media)
        assertNotNull(vm.controller.state.value.error)
        assertFalse(vm.controller.state.value.canCast)
    }
    @Test fun disallowedContentTypeIsRejectedAtAcceptTime() {
        val vm = vm().apply { ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined }
        vm.receive(Intent(Intent.ACTION_SEND).setType("video/webm").putExtra(Intent.EXTRA_STREAM, Uri.parse("content://nobody/1")))
        assertEquals(LocalMediaOpener.BAD_TYPE, vm.controller.state.value.error)
        assertNull(vm.controller.state.value.media)
    }
    @Test fun urlShareStillWorksSynchronously() {
        val vm = vm()
        vm.receive(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "https://example/a.mp4"))
        assertEquals("https://example/a.mp4", vm.controller.state.value.media?.url)
        assertNull(vm.controller.state.value.media?.localName)
    }
    @Test fun mediaPathTokenIsRedactedFromLogs() {
        object {}.logI("GET /m/SecretToken_-123/media.mp4 done")
        val last = LogRepository.flow.replayCache.last().msg
        assertFalse(last, last.contains("SecretToken"))
        assertTrue(last, last.contains("/m/***/"))
    }
}
