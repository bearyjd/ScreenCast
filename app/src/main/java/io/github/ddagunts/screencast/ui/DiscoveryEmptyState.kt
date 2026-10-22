package io.github.ddagunts.screencast.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleStartEffect
import io.github.ddagunts.screencast.R
import io.github.ddagunts.screencast.cast.DiscoveryFailure
import io.github.ddagunts.screencast.cast.DiscoveryStatus
import kotlinx.coroutines.delay

private const val DISCOVERY_GIVE_UP_MS = 30_000L

// Shared "no devices yet" body for the three pickers. Shows a spinner-less
// "looking" line while scanning; after 30 s without results, or immediately on
// a failure, it explains why and offers Retry plus a pointer at Connect by IP.
@Composable
fun DiscoveryEmptyState(status: DiscoveryStatus, onRetry: () -> Unit, showConnectByIpHint: Boolean = true) {
    var timedOut by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(attempt) { timedOut = false; delay(DISCOVERY_GIVE_UP_MS); timedOut = true }

    val failed = status as? DiscoveryStatus.Failed
    if (failed == null && !timedOut) {
        Text(stringResource(R.string.discovery_scanning), style = MaterialTheme.typography.bodyMedium)
        return
    }
    Column {
        Text(
            stringResource(
                when {
                    failed?.reason == DiscoveryFailure.PERMISSION_DENIED -> R.string.discovery_permission_denied
                    failed != null -> R.string.discovery_start_failed
                    else -> R.string.discovery_none_found
                }
            ),
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedButton(onClick = { attempt++; onRetry() }) { Text(stringResource(R.string.discovery_retry)) }
        if (showConnectByIpHint) Text(
            stringResource(R.string.discovery_connect_by_ip_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// Holds a discovery lease only while the hosting screen is STARTED and composed.
@Composable
fun DiscoveryLifecycle(start: () -> Unit, stop: () -> Unit) {
    LifecycleStartEffect(Unit) {
        start()
        onStopOrDispose { stop() }
    }
}
