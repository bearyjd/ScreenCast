package io.github.ddagunts.screencast.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.ddagunts.screencast.webrtc.PermissionAction
import io.github.ddagunts.screencast.webrtc.WebRtcConfigStore

internal const val LAUNCH_PROMPTS_PREFS = "launch_prompts"
internal const val MIC_RATIONALE_SHOWN_KEY = "mic_rationale_shown"

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

internal fun isRecordAudioDeclared(ctx: Context): Boolean =
    ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.GET_PERMISSIONS)
        .requestedPermissions.orEmpty().contains(Manifest.permission.RECORD_AUDIO)

/** Recovery path after "Not now": HLS streams silence without RECORD_AUDIO, so say so and offer a fix. */
@Composable
fun MicPermissionBanner(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val store = remember { WebRtcConfigStore(ctx) }
    val prefs = remember { ctx.getSharedPreferences(LAUNCH_PROMPTS_PREFS, Context.MODE_PRIVATE) }
    val declared = remember { isRecordAudioDeclared(ctx) }
    fun granted() = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED
    var micGranted by remember { mutableStateOf(granted()) }
    var asked by remember { mutableStateOf(store.micPermissionAsked) }
    var shown by remember { mutableStateOf(prefs.getBoolean(MIC_RATIONALE_SHOWN_KEY, false)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        micGranted = ok
        if (!ok) { store.micPermissionAsked = true; asked = true }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, prefs) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) {
                micGranted = granted(); asked = store.micPermissionAsked
                shown = prefs.getBoolean(MIC_RATIONALE_SHOWN_KEY, false)
            }
        }
        val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { p, key ->
            if (key == MIC_RATIONALE_SHOWN_KEY) shown = p.getBoolean(key, false)
        }
        lifecycle.addObserver(obs)
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
        onDispose { lifecycle.removeObserver(obs); prefs.unregisterOnSharedPreferenceChangeListener(prefListener) }
    }
    val rationale = ctx.findActivity()?.let {
        ActivityCompat.shouldShowRequestPermissionRationale(it, Manifest.permission.RECORD_AUDIO)
    } ?: false
    val banner = micBanner(Build.VERSION.SDK_INT, declared, micGranted, shown, asked, rationale) ?: return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Text(banner.message, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        OutlinedButton(onClick = {
            when (banner.action) {
                PermissionAction.REQUEST -> launcher.launch(Manifest.permission.RECORD_AUDIO)
                PermissionAction.OPEN_SETTINGS -> ctx.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                PermissionAction.NONE -> Unit
            }
        }) { Text(banner.buttonLabel) }
    }
}
