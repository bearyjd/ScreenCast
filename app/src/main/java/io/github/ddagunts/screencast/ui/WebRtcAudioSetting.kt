package io.github.ddagunts.screencast.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.ddagunts.screencast.webrtc.PermissionAction
import io.github.ddagunts.screencast.webrtc.WebRtcConfigStore
import io.github.ddagunts.screencast.webrtc.audioNote
import io.github.ddagunts.screencast.webrtc.permissionAction
import io.github.ddagunts.screencast.webrtc.resolveAudioMode

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

// WebRTC audio toggle. Audio is playback capture (media and game audio from
// apps that allow capture); the microphone is not used. RECORD_AUDIO is still
// required because AudioRecord demands it even for playback capture.
@Composable
fun WebRtcAudioSetting(requested: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    val ctx = LocalContext.current
    val store = remember { WebRtcConfigStore(ctx) }
    fun granted() = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED
    var micGranted by remember { mutableStateOf(granted()) }
    var asked by remember { mutableStateOf(store.micPermissionAsked) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        micGranted = ok
        if (!ok) { store.micPermissionAsked = true; asked = true }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) { micGranted = granted(); asked = store.micPermissionAsked }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.toggleable(value = requested, enabled = enabled, role = Role.Switch, onValueChange = onChange),
    ) {
        Text("Audio (playback capture)", modifier = Modifier.weight(1f))
        Switch(checked = requested, onCheckedChange = null, enabled = enabled)
    }
    Text(
        "Sound from any app that allows audio capture is sent, even if you share only one app. The microphone is not used.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val mode = resolveAudioMode(requested, micGranted, Build.VERSION.SDK_INT)
    audioNote(mode)?.let { note ->
        Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
    if (requested && Build.VERSION.SDK_INT >= 29 && !micGranted) {
        val rationale = ctx.findActivity()?.let {
            ActivityCompat.shouldShowRequestPermissionRationale(it, Manifest.permission.RECORD_AUDIO)
        } ?: false
        when (permissionAction(micGranted, asked, rationale)) {
            PermissionAction.REQUEST -> OutlinedButton(onClick = { launcher.launch(Manifest.permission.RECORD_AUDIO) }) {
                Text("Allow Microphone permission")
            }
            PermissionAction.OPEN_SETTINGS -> OutlinedButton(onClick = {
                ctx.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }) { Text("Open app settings") }
            PermissionAction.NONE -> Unit
        }
    }
}
