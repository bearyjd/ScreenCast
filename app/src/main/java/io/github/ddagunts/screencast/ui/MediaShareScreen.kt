// Fork modification by bearyjd: preview and select before loading receiver-owned direct media.
package io.github.ddagunts.screencast.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.github.ddagunts.screencast.cast.CastDevice
import io.github.ddagunts.screencast.media.LocalMediaPolicy
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.net.URI

/** Wording of the pre-send confirmation; both labels come from untrusted sources, so they are sanitised. */
internal fun localSendPrompt(fileName: String, device: CastDevice): String =
    "Send \"${LocalMediaPolicy.sanitizeLabel(fileName, "this file")}\" to this receiver?\n" +
        "Receiver: ${LocalMediaPolicy.sanitizeLabel(device.name, "unnamed", 40)}\n" +
        "Address: ${LocalMediaPolicy.sanitizeLabel(device.host, "unknown", 45)}"

@Composable
fun MediaShareScreen(vm: MediaShareViewModel) {
    val state by vm.controller.state.collectAsStateWithLifecycle()
    val devices by vm.discovered.collectAsStateWithLifecycle()
    val discoveryStatus by vm.discoveryStatus.collectAsStateWithLifecycle()
    val sharing by vm.sharingActive.collectAsStateWithLifecycle()
    var confirmSend by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Cast shared media", style = MaterialTheme.typography.headlineSmall)
        Text("The receiver plays a direct MP4 or MP3 URL, or a video/audio file shared from another app. Select a receiver, then tap Cast.")
        state.media?.let { media ->
            if (media.localName != null) {
                Text(media.localName, style = MaterialTheme.typography.bodyLarge)
                Text(media.contentType)
                Text("This file is sent unencrypted over this Wi-Fi to the selected receiver. Anyone on the network who can intercept traffic could read it, so use only networks you trust.", style = MaterialTheme.typography.bodySmall)
            } else {
                val uri = URI(media.url)
                Text("${uri.host}${uri.rawPath.orEmpty()}", style = MaterialTheme.typography.bodyLarge)
                Text(media.contentType)
                Text("URL query parameters are preserved for the receiver.", style = MaterialTheme.typography.bodySmall)
            }
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Text("Receivers", style = MaterialTheme.typography.titleMedium)
        if (devices.isEmpty()) DiscoveryEmptyState(discoveryStatus, { vm.rescanDiscovery() }, showConnectByIpHint = false)
        devices.forEach { device ->
            OutlinedButton(onClick = { vm.controller.select(device) }, modifier = Modifier.fillMaxWidth()) {
                Text(if (state.selected == device) "Selected: ${device.name}" else device.name)
            }
        }
        Button(onClick = { if (state.media?.localName != null) confirmSend = true else vm.controller.cast() },
            enabled = state.canCast && !state.connecting) { Text("Cast") }
        val local = state.media?.localName
        val target = state.selected
        if (confirmSend && local != null && target != null) {
            AlertDialog(
                onDismissRequest = { confirmSend = false },
                title = { Text("Send file to receiver?") },
                text = { Text(localSendPrompt(local, target)) },
                confirmButton = { TextButton(onClick = { confirmSend = false; vm.controller.cast() }) { Text("Send") } },
                dismissButton = { TextButton(onClick = { confirmSend = false }) { Text("Cancel") } },
            )
        }
        if (sharing && state.active == null) {
            HorizontalDivider()
            Text("Sharing a file from this phone is active.")
            Button(onClick = { vm.stopSharing() }) { Text("Stop sharing") }
        }
        state.active?.let { device ->
            HorizontalDivider()
            Text(if (state.connecting) "Waiting for ${device.name} to confirm media…" else "Controlling ${device.name}")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { if (state.playing) vm.controller.pause() else vm.controller.play() }, enabled = !state.connecting) {
                    Text(if (state.playing) "Pause" else "Play")
                }
                Button(onClick = { vm.controller.stop() }, enabled = !state.connecting) { Text("Stop playback") }
            }
            OutlinedButton(onClick = { vm.controller.end() }) { Text(if (state.activeLocal) "Stop sharing and disconnect" else "Disconnect controls") }
        }
        Text("Closing this screen disconnects controls. Receiver playback may continue. Use Stop playback to stop it. Background controls are not maintained.", style = MaterialTheme.typography.bodySmall)
        if (state.media?.localName != null || sharing) {
            Text("A shared file keeps being served from this phone (see the notification) after you leave this screen, until you stop it, it times out, or the app is closed. Only MP4 video and MP3/M4A audio stored on the phone work.", style = MaterialTheme.typography.bodySmall)
        }
        Text("Webpages, YouTube/Spotify sessions, DRM and cloud-only items are unsupported. URLs must be reachable and playable by your receiver.", style = MaterialTheme.typography.bodySmall)
    }
}
