package io.github.g20ir.buttons

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** Button settings and live status, shared by the accessibility service and the UI. */
object ButtonStore {
    sealed interface HelperStatus {
        data object Off : HelperStatus
        data object Connecting : HelperStatus
        data object Running : HelperStatus
        data class Failed(val message: String) : HelperStatus
    }

    /** Apps the defaults point at, if installed. */
    val DEFAULT_APPS = mapOf(
        RemoteButton.YOUTUBE to listOf("com.google.android.youtube.tv", "com.google.android.youtube.googletv"),
        RemoteButton.NETFLIX to listOf("com.netflix.ninja"),
        RemoteButton.PRIME to listOf("com.amazon.amazonvideo.livingroom", "com.amazon.amazonvideo.livingroom.nvidia"),
    )

    private var prefs: SharedPreferences? = null

    private val _actions = MutableStateFlow<Map<RemoteButton, ButtonAction>>(emptyMap())
    val actions: StateFlow<Map<RemoteButton, ButtonAction>> = _actions

    /** Optional action when a button is held (~0.6 s). None = no hold behaviour, no delay. */
    private val _holdActions = MutableStateFlow<Map<RemoteButton, ButtonAction>>(emptyMap())
    val holdActions: StateFlow<Map<RemoteButton, ButtonAction>> = _holdActions

    /**
     * Sends an Android key code through the adb helper; set by the service while the helper
     * runs. Apps can't inject keys themselves, adb shell (`input keyevent`) can.
     */
    @Volatile var keySender: ((Int) -> Unit)? = null

    /** What the four app buttons do when the helper isn't running (they look identical then). */
    private val _sharedAppAction = MutableStateFlow<ButtonAction>(ButtonAction.None)
    val sharedAppAction: StateFlow<ButtonAction> = _sharedAppAction

    private val _helperEnabled = MutableStateFlow(false)
    val helperEnabled: StateFlow<Boolean> = _helperEnabled

    val helperStatus = MutableStateFlow<HelperStatus>(HelperStatus.Off)
    val serviceRunning = MutableStateFlow(false)

    /** Last button seen, for the test line on the Buttons screen. */
    val lastPress = MutableStateFlow<String?>(null)

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences("buttons", Context.MODE_PRIVATE)
        prefs = p
        val pm = context.packageManager
        val map = RemoteButton.entries.associateWith { b ->
            ButtonAction.decode(p.getString(b.name, null)) ?: defaultFor(b, pm)
        }
        _actions.value = map
        _holdActions.value = RemoteButton.entries.associateWith { b ->
            ButtonAction.decode(p.getString("${b.name}_hold", null)) ?: ButtonAction.None
        }
        _sharedAppAction.value = ButtonAction.decode(p.getString("shared_app_keys", null)) ?: ButtonAction.None
        _helperEnabled.value = p.getBoolean("helper", false)
    }

    private fun defaultFor(b: RemoteButton, pm: PackageManager): ButtonAction {
        // The Shield's Android 11 key layout has no entries for these Linux keys, so apps (e.g.
        // TiviMate) get KEYCODE_UNKNOWN. Send the real key code instead.
        when (b) {
            RemoteButton.SETTINGS -> return ButtonAction.SystemSettings
            RemoteButton.RED -> return ButtonAction.SendKey(183, "PROG_RED")
            RemoteButton.GREEN -> return ButtonAction.SendKey(184, "PROG_GREEN")
            RemoteButton.YELLOW -> return ButtonAction.SendKey(185, "PROG_YELLOW")
            RemoteButton.BLUE -> return ButtonAction.SendKey(186, "PROG_BLUE")
            RemoteButton.INFO -> return ButtonAction.SendKey(165, "INFO")
            RemoteButton.SUBTITLES -> return ButtonAction.SendKey(175, "CAPTIONS")
            else -> {}
        }
        val pkg = DEFAULT_APPS[b]?.firstOrNull { isInstalled(pm, it) } ?: return ButtonAction.None
        return ButtonAction.App(pkg, b.label)
    }

    private fun isInstalled(pm: PackageManager, pkg: String) =
        try { pm.getPackageInfo(pkg, 0); true } catch (_: PackageManager.NameNotFoundException) { false }

    fun setAction(b: RemoteButton, a: ButtonAction) {
        prefs?.edit()?.putString(b.name, a.encode())?.apply()
        _actions.update { it + (b to a) }
    }

    fun setHoldAction(b: RemoteButton, a: ButtonAction) {
        prefs?.edit()?.putString("${b.name}_hold", a.encode())?.apply()
        _holdActions.update { it + (b to a) }
    }

    fun holdAction(b: RemoteButton): ButtonAction = _holdActions.value[b] ?: ButtonAction.None

    /** False for actions that can't run right now (Send key without the helper). */
    fun canPerform(a: ButtonAction): Boolean = when (a) {
        ButtonAction.None -> false
        is ButtonAction.SendKey -> keySender != null && helperStatus.value == HelperStatus.Running
        else -> true
    }

    fun setSharedAppAction(a: ButtonAction) {
        prefs?.edit()?.putString("shared_app_keys", a.encode())?.apply()
        _sharedAppAction.value = a
    }

    fun setHelperEnabled(on: Boolean) {
        prefs?.edit()?.putBoolean("helper", on)?.apply()
        _helperEnabled.value = on
    }

    fun action(b: RemoteButton): ButtonAction = _actions.value[b] ?: ButtonAction.None

    /** Runs an action from the accessibility service. Returns false if it could not. */
    fun perform(service: AccessibilityService, a: ButtonAction): Boolean = when (a) {
        ButtonAction.None -> false
        ButtonAction.Home -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        ButtonAction.Back -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        ButtonAction.Recents -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
        ButtonAction.Notifications -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
        ButtonAction.SystemSettings -> start(service, Intent(Settings.ACTION_SETTINGS))
        is ButtonAction.SendKey -> keySender?.let { it(a.code); true } ?: false
        is ButtonAction.App -> {
            val pm = service.packageManager
            val i = pm.getLeanbackLaunchIntentForPackage(a.pkg) ?: pm.getLaunchIntentForPackage(a.pkg)
            if (i == null) false else start(service, i)
        }
    }

    private fun start(c: Context, i: Intent): Boolean = try {
        c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
        true
    } catch (e: Exception) {
        android.util.Log.w("G20IR", "could not start ${i.component ?: i.action}: ${e.message}")
        false
    }
}
