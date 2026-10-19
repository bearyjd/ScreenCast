// Fork modification by bearyjd: preview and select before loading receiver-owned direct media.
package io.github.ddagunts.screencast.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.net.URI

@Composable
fun MediaShareScreen(vm: MediaShareViewModel) {
    val state by vm.controller.state.collectAsStateWithLifecycle()
    val devices by vm.discovered.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Cast shared media", style = MaterialTheme.typography.headlineSmall)
        Text("The receiver plays a direct MP4 or MP3 URL. Select a receiver, then tap Cast.")
        state.media?.let { media ->
            val uri = URI(media.url)
            Text("${uri.host}${uri.rawPath.orEmpty()}", style = MaterialTheme.typography.bodyLarge)
            Text(media.contentType)
            Text("URL query parameters are preserved for the receiver.", style = MaterialTheme.typography.bodySmall)
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Text("Receivers", style = MaterialTheme.typography.titleMedium)
        if (devices.isEmpty()) Text("Searching on your network… Saved receiver addresses also appear here.")
        devices.forEach { device ->
            OutlinedButton(onClick = { vm.controller.select(device) }, modifier = Modifier.fillMaxWidth()) {
                Text(if (state.selected == device) "Selected: ${device.name}" else device.name)
            }
        }
        Button(onClick = { vm.controller.cast() }, enabled = state.canCast && !state.connecting) { Text("Cast") }
        state.active?.let { device ->
            HorizontalDivider()
            Text(if (state.connecting) "Waiting for ${device.name} to confirm media…" else "Controlling ${device.name}")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { if (state.playing) vm.controller.pause() else vm.controller.play() }, enabled = !state.connecting) {
                    Text(if (state.playing) "Pause" else "Play")
                }
                Button(onClick = { vm.controller.stop() }, enabled = !state.connecting) { Text("Stop playback") }
            }
            OutlinedButton(onClick = { vm.controller.disconnect() }) { Text("Disconnect controls") }
        }
        Text("Closing this screen disconnects controls. Receiver playback may continue. Use Stop playback to stop it. Background controls are not maintained.", style = MaterialTheme.typography.bodySmall)
        Text("Webpages, YouTube/Spotify sessions, DRM and local files are unsupported. The URL must be reachable and playable by your receiver.", style = MaterialTheme.typography.bodySmall)
    }
}
