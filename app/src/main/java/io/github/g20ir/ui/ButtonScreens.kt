package io.github.g20ir.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import io.github.g20ir.buttons.ButtonAction
import io.github.g20ir.buttons.ButtonStore
import io.github.g20ir.buttons.RemoteButton

@Composable
fun ButtonsScreen(vm: AppViewModel) {
    val actions by ButtonStore.actions.collectAsStateWithLifecycle()
    val shared by ButtonStore.sharedAppAction.collectAsStateWithLifecycle()
    val running by ButtonStore.serviceRunning.collectAsStateWithLifecycle()
    val helperOn by ButtonStore.helperEnabled.collectAsStateWithLifecycle()
    val helper by ButtonStore.helperStatus.collectAsStateWithLifecycle()
    val last by ButtonStore.lastPress.collectAsStateWithLifecycle()
    ButtonsContent(vm, actions, shared, running, helperOn, helper, last)
}

@Composable
fun ButtonsContent(
    vm: AppViewModel,
    actions: Map<RemoteButton, ButtonAction>,
    shared: ButtonAction,
    running: Boolean,
    helperOn: Boolean,
    helper: ButtonStore.HelperStatus,
    last: String?,
) {
    val warn = Color(0xFFFFB74D)
    Title("Remote buttons", "Choose what the G20's Bluetooth-only buttons do")
    val first = rememberInitialFocus()
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Section("1. Button service")
            if (running) {
                Body("On. It starts by itself after every reboot.")
            } else {
                Body("Off. Turn on \"G20 remote buttons\" under Settings → Device Preferences → Accessibility.", warn)
                Spacer(Modifier.height(8.dp))
                Button(onClick = { vm.openAccessibilitySettings() }) { Text("Open accessibility settings") }
            }

            Section("2. Helper for the app buttons (optional)")
            Body("YouTube, Netflix, Prime Video and rocket all reach Android as the same unknown key. The helper tells them apart by reading the remote through this Shield's own adb (Network debugging).")
            Spacer(Modifier.height(4.dp))
            val (statusText, color) = when {
                !helperOn -> "Off: the four app buttons share one action" to MaterialTheme.colorScheme.onBackground
                !running -> "Waiting for the button service" to warn
                helper is ButtonStore.HelperStatus.Running -> "Running: each app button has its own action" to MaterialTheme.colorScheme.primary
                helper is ButtonStore.HelperStatus.Connecting ->
                    "Connecting… If the TV asks \"Allow network debugging?\", tick \"Always allow\" and choose OK." to warn
                helper is ButtonStore.HelperStatus.Failed -> "Not connected: ${helper.message}" to Color(0xFFFF8A80)
                else -> "Off" to MaterialTheme.colorScheme.onBackground
            }
            Body(statusText, color)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { ButtonStore.setHelperEnabled(!helperOn) }) {
                    Text(if (helperOn) "Turn helper off" else "Turn helper on")
                }
            }
            Body("Needs Settings → Device Preferences → Developer options → Network debugging on. The helper has the same rights as \"adb shell\" and only uses them to read the remote's key presses.")

            Section("Test")
            Body(last?.let { "Last button: $it" } ?: "Press a button on the remote to see what it does.")
            Spacer(Modifier.height(12.dp))
            Button(onClick = { vm.back() }, modifier = androidx.compose.ui.Modifier) { Text("Done") }
        }
        LazyColumn(Modifier.weight(1f)) {
            item { Section("Buttons") }
            items(RemoteButton.entries.size) { i ->
                val b = RemoteButton.entries[i]
                val a = actions[b] ?: ButtonAction.None
                val note = if (b.isAppKey && !(helperOn && helper is ButtonStore.HelperStatus.Running)) " (needs the helper)" else ""
                Item(b.label, a.describe() + note, if (i == 0) first else null) { vm.go(Screen.PickAction(b)) }
            }
            item {
                Item("App buttons without the helper", "All four: " + shared.describe()) { vm.go(Screen.PickAction(null)) }
            }
        }
    }
}

@Composable
fun PickActionScreen(vm: AppViewModel, button: RemoteButton?) {
    val apps = remember { vm.launchableApps() }
    val actions by ButtonStore.actions.collectAsStateWithLifecycle()
    val shared by ButtonStore.sharedAppAction.collectAsStateWithLifecycle()
    val current = if (button == null) shared else actions[button] ?: ButtonAction.None
    Title(
        if (button == null) "App buttons without the helper" else button.label,
        "Now: ${current.describe()}",
    )
    val first = rememberInitialFocus()
    LazyColumn {
        item { Section("Actions") }
        items(ButtonAction.fixed.size) { i ->
            val a = ButtonAction.fixed[i]
            Item(a.describe(), null, if (i == 0) first else null) { vm.pickAction(button, a) }
        }
        item { Section("Open an app") }
        items(apps) { a -> Item(a.label, a.pkg) { vm.pickAction(button, a) } }
    }
}
