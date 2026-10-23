package io.github.ddagunts.screencast.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role

// Opt-in: mute the phone's media stream for the duration of a WebRTC cast.
@Composable
fun MuteWhileCastingSetting(checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
    ) {
        Text("Mute phone while casting (experimental)", modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
    Text(
        "Experimental. Mutes this phone's media output (including navigation voice and text-to-speech) while casting and unmutes it when the cast stops. If you unmute the phone during the cast, it stays unmuted. Unverified: on some devices this may also silence the TV.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
