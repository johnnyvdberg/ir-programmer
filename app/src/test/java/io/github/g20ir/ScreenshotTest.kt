package io.github.g20ir

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.CompositionLocalProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.android.resources.ScreenOrientation
import io.github.g20ir.ble.G20Remote
import io.github.g20ir.ir.IrSignal
import io.github.g20ir.ir.Protocol
import io.github.g20ir.model.Assignment
import io.github.g20ir.model.G20Key
import io.github.g20ir.model.RemoteConfig
import io.github.g20ir.ui.App
import io.github.g20ir.ui.AppViewModel
import io.github.g20ir.ui.Phase
import io.github.g20ir.ui.Role
import io.github.g20ir.ui.Screen
import io.github.g20ir.ui.TestState
import io.github.g20ir.ui.UiState
import io.github.g20ir.ui.CheckState
import io.github.g20ir.model.ConfigIo
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * Renders the real screens at Android TV resolution (1920x1080, xhdpi = 960x540 dp).
 * Run: ./gradlew :app:recordPaparazziDebug  -> app/src/test/snapshots/images/
 */
class ScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(
            screenWidth = 1920,
            screenHeight = 1080,
            xdpi = 320,
            ydpi = 320,
            density = Density.XHIGH,
            orientation = ScreenOrientation.LANDSCAPE,
            softButtons = false,
        ),
        showSystemUi = false,
        maxPercentDifference = 0.1,
    )

    private val dbJson = File("src/main/assets/irdb.json").readText()

    private fun sony(cmd: Long) = IrSignal.Parsed(Protocol.SIRC, 1, cmd)

    private val sonyConfig = RemoteConfig(
        mapOf(
            G20Key.POWER to Assignment(sony(21), "Sony (Sony Bravia) – tested"),
            G20Key.INPUT to Assignment(sony(37), "Sony (Sony Bravia) – tested"),
            G20Key.VOL_UP to Assignment(sony(18), "Sony (Sony Bravia) – tested"),
            G20Key.VOL_DOWN to Assignment(sony(19), "Sony (Sony Bravia) – tested"),
            G20Key.MUTE to Assignment(sony(20), "Sony (Sony Bravia) – tested"),
        ),
    )

    private val connected: (UiState) -> UiState = {
        it.copy(remoteName = "G20 Remote", remoteState = G20Remote.State.READY, mtu = 180, remoteAddress = "F4:22:7A:00:11:22")
    }

    private fun shot(state: (UiState) -> UiState) {
        val app = FakeApp(paparazzi.context)
        val vm = AppViewModel(app) { dbJson }
        vm.setStateForPreview { state(connected(it)) }
        val owner = FakeBackOwner()
        paparazzi.snapshot {
            CompositionLocalProvider(LocalOnBackPressedDispatcherOwner provides owner) { App(vm) }
        }
    }

    private fun test(phase: Phase, key: G20Key, verifying: Boolean = false, confirmed: Set<G20Key> = emptySet(), msg: String = "") =
        TestState(
            title = "Sony TV",
            phase = phase,
            key = key,
            candidateLabel = "Sony code 1/4 (Sony Bravia, Sony Bravia KD-55XF80xx-49XF80xx-43XF80xx, Sony Bravia KDL-46W905A +9)",
            verifying = verifying,
            message = msg,
            confirmed = confirmed.associateWith { sony(18) },
        )

    @Test fun s1_home_empty() = shot { it }
    @Test fun s2_pick_role() = shot { it.copy(screen = Screen.PickRole) }
    @Test fun s3_pick_brand() = shot { it.copy(screen = Screen.PickBrand(Role.TV, "TV")) }
    @Test fun s4_test_wait_press() = shot { it.copy(screen = Screen.Test, test = test(Phase.WAIT_PRESS, G20Key.VOL_UP)) }
    @Test fun s5_test_ask() = shot { it.copy(screen = Screen.Test, test = test(Phase.ASK, G20Key.VOL_UP)) }
    @Test fun s6_test_verify_input() = shot {
        it.copy(
            screen = Screen.Test,
            test = test(Phase.ASK, G20Key.INPUT, verifying = true,
                confirmed = setOf(G20Key.VOL_UP, G20Key.VOL_DOWN, G20Key.MUTE)),
        )
    }
    @Test fun s7_test_done() = shot {
        it.copy(screen = Screen.Test, config = sonyConfig,
            test = test(Phase.DONE, G20Key.POWER, verifying = true, confirmed = G20Key.entries.toSet(), msg = "All keys work."))
    }
    @Test fun s8_home_configured() = shot { it.copy(config = sonyConfig, applied = sonyConfig) }
    @Test fun s9_edit_key() = shot { it.copy(config = sonyConfig, screen = Screen.EditKey(G20Key.INPUT)) }
    @Test fun s10_check_remote() = shot {
        it.copy(
            config = sonyConfig.with(G20Key.POWER, null),
            screen = Screen.CheckRemote,
            check = CheckState(
                info = listOf(
                    "Manufacturer" to "Ohsung", "Model" to "G20", "Firmware" to "V1.08",
                    "Battery" to "87 %", "Address" to "F4:22:7A:00:11:22", "MTU" to "180 (max 177 bytes per code)",
                ),
                loading = false,
                results = mapOf(
                    G20Key.VOL_UP to "Sends IR (reported by the remote)",
                    G20Key.MUTE to "Sends Bluetooth (no IR code)",
                    G20Key.INPUT to "Sends IR (reported by the remote)",
                ),
            ),
        )
    }
    @Test fun s11_transfer() = shot {
        it.copy(config = sonyConfig, screen = Screen.Transfer, transferUrls = listOf("http://192.168.1.42:8080/"),
            exportPath = "/storage/emulated/0/Android/data/io.github.g20ir/files/g20-ir-config.json")
    }
    @Test fun s12_import_confirm() = shot {
        it.copy(config = sonyConfig, screen = Screen.Transfer,
            pendingImport = ConfigIo.import(ConfigIo.export(sonyConfig, G20Key.INPUT.keyId)))
    }
}

/** An Application backed by the layoutlib context, with in-memory preferences. */
private class FakeApp(base: Context) : Application() {
    init {
        ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java)
            .apply { isAccessible = true }
            .invoke(this, base)
    }

    private val prefs = MemPrefs()
    override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs
    override fun getApplicationContext(): Context = this
}

private class MemPrefs : SharedPreferences {
    private val m = mutableMapOf<String, Any?>()
    override fun getAll(): MutableMap<String, *> = m
    override fun getString(k: String, d: String?) = m[k] as? String ?: d
    override fun getStringSet(k: String, d: MutableSet<String>?) = d
    override fun getInt(k: String, d: Int) = m[k] as? Int ?: d
    override fun getLong(k: String, d: Long) = m[k] as? Long ?: d
    override fun getFloat(k: String, d: Float) = m[k] as? Float ?: d
    override fun getBoolean(k: String, d: Boolean) = m[k] as? Boolean ?: d
    override fun contains(k: String) = k in m
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        override fun putString(k: String, v: String?) = apply { m[k] = v }
        override fun putStringSet(k: String, v: MutableSet<String>?) = apply { m[k] = v }
        override fun putInt(k: String, v: Int) = apply { m[k] = v }
        override fun putLong(k: String, v: Long) = apply { m[k] = v }
        override fun putFloat(k: String, v: Float) = apply { m[k] = v }
        override fun putBoolean(k: String, v: Boolean) = apply { m[k] = v }
        override fun remove(k: String) = apply { m.remove(k) }
        override fun clear() = apply { m.clear() }
        override fun commit() = true
        override fun apply() {}
    }
}

private class FakeBackOwner : OnBackPressedDispatcherOwner {
    private val registry = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
    override val lifecycle: Lifecycle get() = registry
    override val onBackPressedDispatcher = OnBackPressedDispatcher()
}
