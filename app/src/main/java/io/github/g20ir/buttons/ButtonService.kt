package io.github.g20ir.buttons

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Gives the G20's Bluetooth-only buttons an action. Android starts it at boot once it is
 * switched on under Settings → Accessibility. Keys set to "Nothing" are passed on untouched.
 */
class ButtonService : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var helper: AdbKeyHelper? = null

    override fun onServiceConnected() {
        ButtonStore.init(this)
        ButtonStore.serviceRunning.value = true
        scope.launch {
            ButtonStore.helperEnabled.collect { on -> if (on) startHelper() else stopHelper() }
        }
    }

    private fun startHelper() {
        if (helper != null) return
        helper = AdbKeyHelper(
            filesDir,
            onPress = { b -> main.post { press(b) } },
            log = { android.util.Log.i("G20IR", it) },
        ).also { it.start() }
    }

    private fun stopHelper() {
        helper?.stop()
        helper = null
    }

    private fun press(b: RemoteButton) {
        val a = ButtonStore.action(b)
        ButtonStore.lastPress.value = "${b.label} → ${a.describe()}"
        ButtonStore.perform(this, a)
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val down = event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0
        if (event.scanCode == KEY_UNKNOWN) {
            // One of the four app buttons, or another unnamed key.
            if (ButtonStore.helperStatus.value == ButtonStore.HelperStatus.Running) {
                return true // the helper knows which one it was and acts on it
            }
            val a = ButtonStore.sharedAppAction.value
            if (down) ButtonStore.lastPress.value = "App button (helper not running) → ${a.describe()}"
            if (a == ButtonAction.None) return false
            if (down) ButtonStore.perform(this, a)
            return true
        }
        val b = RemoteButton.fromScanCode(event.scanCode) ?: return false
        val a = ButtonStore.action(b)
        if (down) ButtonStore.lastPress.value = "${b.label} → ${a.describe()}"
        if (a == ButtonAction.None) return false
        if (down) ButtonStore.perform(this, a)
        return true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        stopHelper()
        scope.cancel()
        ButtonStore.serviceRunning.value = false
        super.onDestroy()
    }
}
