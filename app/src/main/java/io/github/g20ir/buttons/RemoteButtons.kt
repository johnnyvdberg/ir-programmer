package io.github.g20ir.buttons

/**
 * The G20's Bluetooth-only buttons. Measured on the owner's remote with `getevent -l`:
 * the HID consumer usage (MSC_SCAN 000cXXXX) and the Linux key code the kernel turns it into.
 * Android hands apps the Linux code as the KeyEvent scan code.
 *
 * The four app buttons all become KEY_UNKNOWN (240), so apps can't tell them apart; only the
 * raw usage (readable with adb-shell rights, see [AdbKeyHelper]) identifies them.
 */
enum class RemoteButton(val label: String, val usage: Int, val linuxKey: Int) {
    RED("Red", 0x69, 398),
    GREEN("Green", 0x6A, 399),
    YELLOW("Yellow", 0x6C, 400),
    BLUE("Blue", 0x6B, 401),
    YOUTUBE("YouTube", 0x77, KEY_UNKNOWN),
    NETFLIX("Netflix", 0x78, KEY_UNKNOWN),
    PRIME("Prime Video", 0x79, KEY_UNKNOWN),
    ROCKET("Rocket", 0x7A, KEY_UNKNOWN),
    SETTINGS("Settings (gear)", 0x96, 384), // KEY_TAPE
    INFO("Info", 0x1BD, 358),
    GUIDE("Guide", 0x8D, 362), // KEY_PROGRAM
    SUBTITLES("Subtitles", 0x61, 370),
    BOOKMARK("Bookmark", 0x22A, 156);

    val isAppKey: Boolean get() = linuxKey == KEY_UNKNOWN

    companion object {
        /** Button for a KeyEvent scan code; null for app buttons (ambiguous) and other keys. */
        fun fromScanCode(scanCode: Int): RemoteButton? =
            if (scanCode == KEY_UNKNOWN) null else entries.firstOrNull { it.linuxKey == scanCode }

        fun fromUsage(usage: Int): RemoteButton? = entries.firstOrNull { it.usage == usage }
    }
}

/** Linux KEY_UNKNOWN: what the kernel makes of usages it has no name for. */
const val KEY_UNKNOWN = 240

/** What a button does. [None] leaves the key to Android, so existing behaviour is kept. */
sealed interface ButtonAction {
    data object None : ButtonAction
    data object Home : ButtonAction
    data object Back : ButtonAction
    data object Recents : ButtonAction
    data object Notifications : ButtonAction
    data object SystemSettings : ButtonAction
    data class App(val pkg: String, val label: String) : ButtonAction

    fun describe(): String = when (this) {
        None -> "Nothing (default behaviour)"
        Home -> "Home"
        Back -> "Back"
        Recents -> "Recent apps"
        Notifications -> "Notifications"
        SystemSettings -> "Android TV settings"
        is App -> "Open $label"
    }

    fun encode(): String = when (this) {
        None -> "none"
        Home -> "home"
        Back -> "back"
        Recents -> "recents"
        Notifications -> "notifications"
        SystemSettings -> "settings"
        is App -> "app:$pkg|$label"
    }

    companion object {
        fun decode(s: String?): ButtonAction? = when {
            s == null -> null
            s == "none" -> None
            s == "home" -> Home
            s == "back" -> Back
            s == "recents" -> Recents
            s == "notifications" -> Notifications
            s == "settings" -> SystemSettings
            s.startsWith("app:") -> s.removePrefix("app:").split("|", limit = 2)
                .let { parts -> App(parts[0], parts.getOrElse(1) { parts[0] }) }
            else -> null
        }

        val fixed = listOf(None, Home, Back, Recents, Notifications, SystemSettings)
    }
}

/**
 * Parses `getevent -l` output and reports app-button presses. getevent prints the usage
 * (EV_MSC MSC_SCAN 000c0079) right before the key event (EV_KEY KEY_UNKNOWN DOWN).
 */
class GeteventParser {
    private val lastScan = HashMap<String, Int>()

    /** Returns the button whose DOWN event this line completes, or null. */
    fun feed(line: String): RemoteButton? {
        val parts = line.trim().split(Regex("\\s+"))
        if (parts.size < 3) return null
        val hasDevice = parts[0].endsWith(":")
        val dev = if (hasDevice) parts[0] else ""
        val p = if (hasDevice) parts.drop(1) else parts
        if (p.size < 3) return null
        when {
            p[0] == "EV_MSC" && p[1] == "MSC_SCAN" -> p[2].toIntOrNull(16)?.let { lastScan[dev] = it }
            p[0] == "EV_KEY" && p[2] == "DOWN" -> {
                val usage = (lastScan.remove(dev) ?: return null) and 0xFFFF
                val b = RemoteButton.fromUsage(usage) ?: return null
                return if (b.isAppKey) b else null
            }
            p[0] == "EV_KEY" -> lastScan.remove(dev)
        }
        return null
    }
}
