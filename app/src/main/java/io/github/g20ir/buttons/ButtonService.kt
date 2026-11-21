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
 * switched on under Settings → Accessibility. Keys without an action are passed on untouched.
 * A button with a "when held" action fires its normal action on release instead of on press.
 */
class ButtonService : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var helper: AdbKeyHelper? = null
    private val holdTimers = HashMap<RemoteButton, Runnable>()

    override fun onServiceConnected() {
        ButtonStore.init(this)
        ButtonStore.serviceRunning.value = true
        scope.launch {
            ButtonStore.helperEnabled.collect { on -> if (on) startHelper() else stopHelper() }
        }
    }

    private fun startHelper() {
        if (helper != null) return
        val h = AdbKeyHelper(
            filesDir,
            onPress = { p -> main.post { handle(p.button, p.down, 0) } },
            log = { android.util.Log.i("G20IR", it) },
        )
        helper = h
        ButtonStore.keySender = { code -> h.sendKey(code) }
        h.start()
    }

    private fun stopHelper() {
        ButtonStore.keySender = null
        helper?.stop()
        helper = null
    }

    /** Returns true when the key was handled (and must not reach other apps). */
    private fun handle(b: RemoteButton, down: Boolean, repeat: Int): Boolean {
        val press = ButtonStore.action(b)
        val hold = ButtonStore.holdAction(b)
        val pressOk = ButtonStore.canPerform(press)
        val holdOk = ButtonStore.canPerform(hold)
        if (!pressOk && !holdOk) {
            if (down && repeat == 0) {
                val why = if (press is ButtonAction.SendKey || hold is ButtonAction.SendKey) " (needs the helper)" else ""
                ButtonStore.lastPress.value = "${b.label} → passed on$why"
            }
            return false
        }
        if (down) {
            if (repeat > 0 || holdTimers.containsKey(b)) return true
            if (!holdOk) {
                run(b, press, "")
                return true
            }
            val r = Runnable {
                holdTimers.remove(b)
                run(b, hold, " (held)")
            }
            holdTimers[b] = r
            main.postDelayed(r, HOLD_MS)
        } else {
            holdTimers.remove(b)?.let {
                main.removeCallbacks(it)
                if (pressOk) run(b, press, "")
            }
        }
        return true
    }

    private fun run(b: RemoteButton, a: ButtonAction, how: String) {
        ButtonStore.lastPress.value = "${b.label}$how → ${a.describe()}"
        ButtonStore.perform(this, a)
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val down = event.action == KeyEvent.ACTION_DOWN
        if (event.scanCode == KEY_UNKNOWN) {
            // One of the four app buttons (or another unnamed key).
            if (ButtonStore.helperStatus.value == ButtonStore.HelperStatus.Running) {
                return true // the helper knows which one it was and handles it
            }
            val a = ButtonStore.sharedAppAction.value
            if (down && event.repeatCount == 0) ButtonStore.lastPress.value = "App button (helper not running) → ${a.describe()}"
            if (!ButtonStore.canPerform(a)) return false
            if (down && event.repeatCount == 0) ButtonStore.perform(this, a)
            return true
        }
        val b = RemoteButton.fromScanCode(event.scanCode) ?: return false
        return handle(b, down, event.repeatCount)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        holdTimers.values.forEach(main::removeCallbacks)
        stopHelper()
        scope.cancel()
        ButtonStore.serviceRunning.value = false
        super.onDestroy()
    }

    companion object {
        const val HOLD_MS = 600L
    }
}
