package io.github.g20ir.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.autoConnect() else vm.toast("Bluetooth permission is needed to talk to the remote.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { App(vm) }
        if (Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            permission.launch(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            vm.autoConnect()
        }
    }
}

private val Colors = darkColorScheme(
    primary = Color(0xFF8AB4F8),
    onPrimary = Color(0xFF0B1B33),
    surface = Color(0xFF1B1F24),
    onSurface = Color(0xFFE6E8EB),
    background = Color(0xFF111418),
    onBackground = Color(0xFFE6E8EB),
)

@Composable
fun App(vm: AppViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    MaterialTheme(colorScheme = Colors) {
        androidx.compose.material3.MaterialTheme(colorScheme = androidx.compose.material3.darkColorScheme()) {
            BackHandler(enabled = ui.screen != Screen.Home) { vm.back() }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .padding(horizontal = 48.dp, vertical = 27.dp),
            ) {
                Column(Modifier.fillMaxSize()) {
                    when (val s = ui.screen) {
                        Screen.Home -> HomeScreen(vm, ui)
                        Screen.PickRemote -> PickRemoteScreen(vm)
                        Screen.PickRole -> PickRoleScreen(vm)
                        is Screen.PickCategory -> PickCategoryScreen(vm, s)
                        is Screen.PickBrand -> PickBrandScreen(vm, s)
                        is Screen.PickCode -> PickCodeScreen(vm, s)
                        Screen.Test -> TestScreen(vm, ui)
                        is Screen.EditKey -> EditKeyScreen(vm, ui, s.key)
                        Screen.Diagnostics -> DiagnosticsScreen(vm, ui)
                    }
                }
                StatusOverlay(ui, vm)
            }
        }
    }
}
