package io.github.ddagunts.screencast.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

/**
 * One-time "Share audio?" explanation. Visibility is saved across recreation (rotation, dark-mode toggle);
 * [onMarkShown] runs only from an answer. [ready] gates the first evaluation until launch permission
 * prompts have settled, so this never stacks on a system dialog.
 */
@Composable
fun MicRationaleDialog(
    ready: Boolean,
    evaluate: () -> MicLaunchAction,
    onMarkShown: () -> Unit,
    onRequestMic: () -> Unit,
) {
    var visible by rememberSaveable { mutableStateOf(false) }
    fun apply(event: MicDialogEvent) {
        val step = micDialogReduce(visible, event)
        visible = step.visible
        if (step.effect != MicDialogEffect.NONE) onMarkShown()
        if (step.effect == MicDialogEffect.MARK_SHOWN_AND_REQUEST) onRequestMic()
    }
    LaunchedEffect(ready) { if (ready) apply(MicDialogEvent.Evaluate(evaluate())) }
    if (!visible) return
    AlertDialog(
        onDismissRequest = { apply(MicDialogEvent.Dismiss) },
        title = { Text("Share audio?") },
        text = {
            Text(
                "To send your phone's sound to the TV, Android requires the microphone permission. " +
                    "The microphone itself is not recorded; only audio from apps that allow capture is sent. " +
                    "You can skip this and cast without sound.",
            )
        },
        confirmButton = { TextButton(onClick = { apply(MicDialogEvent.Continue) }) { Text("Continue") } },
        dismissButton = { TextButton(onClick = { apply(MicDialogEvent.NotNow) }) { Text("Not now") } },
    )
}
