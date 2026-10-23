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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import io.github.ddagunts.screencast.webrtc.MIC_MIX_DIALOG_BODY
import io.github.ddagunts.screencast.webrtc.MIC_MIX_DIALOG_TITLE
import io.github.ddagunts.screencast.webrtc.MIC_MIX_EXPLANATION
import io.github.ddagunts.screencast.webrtc.MIC_MIX_LABEL
import io.github.ddagunts.screencast.webrtc.PermissionAction
import io.github.ddagunts.screencast.webrtc.WebRtcConfigStore
import io.github.ddagunts.screencast.webrtc.audioNote
import io.github.ddagunts.screencast.webrtc.audioSettingDescription
import io.github.ddagunts.screencast.webrtc.permissionAction
import io.github.ddagunts.screencast.webrtc.resolveAudioMode

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

// WebRTC audio toggle. Audio is playback capture (media and game audio from
// apps that allow capture). RECORD_AUDIO is required because AudioRecord demands
// it even for playback capture. The microphone itself is used ONLY when the second,
// default-off "Mix in microphone" switch is on (shown only while audio is on).
@Composable
fun WebRtcAudioSetting(
    requested: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit,
    micMix: Boolean, onMicMixChange: (Boolean) -> Unit,
) {
    val ctx = LocalContext.current
    val store = remember { WebRtcConfigStore(ctx) }
    fun granted() = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED
    var micGranted by remember { mutableStateOf(granted()) }
    var asked by remember { mutableStateOf(store.micPermissionAsked) }
    // True while the system prompt was launched on behalf of the mic mix switch: a denial leaves it off.
    var pendingMicMix by remember { mutableStateOf(false) }
    var showMicMixDialog by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        micGranted = ok
        if (!ok) { store.micPermissionAsked = true; asked = true }
        if (pendingMicMix) { pendingMicMix = false; onMicMixChange(ok) }
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
        audioSettingDescription(micMix && requested),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val mode = resolveAudioMode(requested, micGranted, Build.VERSION.SDK_INT)
    audioNote(mode, micMix)?.let { note ->
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
    if (requested) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.toggleable(
                value = micMix, enabled = enabled, role = Role.Switch,
                onValueChange = { on ->
                    when {
                        !on -> onMicMixChange(false)
                        micGranted -> onMicMixChange(true)
                        else -> showMicMixDialog = true // explain first; the system prompt comes after confirm
                    }
                },
            ),
        ) {
            Text(MIC_MIX_LABEL, modifier = Modifier.weight(1f))
            Switch(checked = micMix, onCheckedChange = null, enabled = enabled)
        }
        Text(
            MIC_MIX_EXPLANATION,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (micMix && !micGranted) {
            Text(
                "The microphone mix needs the Microphone permission; until it is allowed the cast has no audio.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
    if (showMicMixDialog) {
        AlertDialog(
            onDismissRequest = { showMicMixDialog = false },
            title = { Text(MIC_MIX_DIALOG_TITLE) },
            text = { Text(MIC_MIX_DIALOG_BODY) },
            confirmButton = {
                TextButton(onClick = {
                    showMicMixDialog = false
                    pendingMicMix = true
                    launcher.launch(Manifest.permission.RECORD_AUDIO)
                }) { Text("Continue") }
            },
            dismissButton = { TextButton(onClick = { showMicMixDialog = false }) { Text("Cancel") } },
        )
    }
}
