// Fork modification by bearyjd: separate explicit Share-to-Cast entry with no capture permissions.
package io.github.ddagunts.screencast.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier

class MediaShareActivity : ComponentActivity() {
    private val vm: MediaShareViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vm.receiveInitial(intent)
        setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) { MediaShareScreen(vm) } } }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        vm.receive(intent)
    }
    override fun onStart() { super.onStart(); vm.startDiscovery() }
    override fun onStop() { vm.stopDiscovery(); super.onStop() }
}
