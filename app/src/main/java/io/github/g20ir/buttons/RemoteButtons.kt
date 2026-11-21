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

    /** Sends an Android key code (via the adb helper, like `input keyevent`). */
    data class SendKey(val code: Int, val name: String) : ButtonAction

    fun describe(): String = when (this) {
        None -> "Nothing (default behaviour)"
        Home -> "Home"
        Back -> "Back"
        Recents -> "Recent apps"
        Notifications -> "Notifications"
        SystemSettings -> "Android TV settings"
        is App -> "Open $label"
        is SendKey -> "Send key $name"
    }

    fun encode(): String = when (this) {
        None -> "none"
        Home -> "home"
        Back -> "back"
        Recents -> "recents"
        Notifications -> "notifications"
        SystemSettings -> "settings"
        is App -> "app:$pkg|$label"
        is SendKey -> "key:$code|$name"
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
            s.startsWith("key:") -> s.removePrefix("key:").split("|", limit = 2).let { parts ->
                parts[0].toIntOrNull()?.let { SendKey(it, parts.getOrElse(1) { "key ${parts[0]}" }) }
            }
            s.startsWith("app:") -> s.removePrefix("app:").split("|", limit = 2)
                .let { parts -> App(parts[0], parts.getOrElse(1) { parts[0] }) }
            else -> null
        }

        val fixed = listOf(None, Home, Back, Recents, Notifications, SystemSettings)
    }
}

/** Android key codes offered by "Send key", grouped for the picker. */
object KeyCatalog {
    data class Key(val code: Int, val name: String, val group: String, val minSdk: Int = 1) {
        val action get() = ButtonAction.SendKey(code, name)
    }

    private const val TV = "TV and colour keys"
    private const val MEDIA = "Media"
    private const val NAV = "Navigation and system"
    private const val NUM = "Numbers"
    private const val GTV = "Google TV keys"

    val keys: List<Key> = listOf(
        Key(183, "PROG_RED", TV), Key(184, "PROG_GREEN", TV), Key(185, "PROG_YELLOW", TV), Key(186, "PROG_BLUE", TV),
        Key(165, "INFO", TV), Key(172, "GUIDE", TV), Key(175, "CAPTIONS", TV), Key(174, "BOOKMARK", TV),
        Key(173, "DVR", TV), Key(166, "CHANNEL_UP", TV), Key(167, "CHANNEL_DOWN", TV),
        Key(229, "LAST_CHANNEL", TV), Key(170, "TV", TV), Key(178, "TV_INPUT", TV), Key(233, "TV_TELETEXT", TV),
        Key(222, "MEDIA_AUDIO_TRACK", TV), Key(255, "TV_ZOOM_MODE", TV), Key(230, "TV_DATA_SERVICE", TV),
        Key(85, "MEDIA_PLAY_PAUSE", MEDIA), Key(126, "MEDIA_PLAY", MEDIA), Key(127, "MEDIA_PAUSE", MEDIA),
        Key(86, "MEDIA_STOP", MEDIA), Key(87, "MEDIA_NEXT", MEDIA), Key(88, "MEDIA_PREVIOUS", MEDIA),
        Key(89, "MEDIA_REWIND", MEDIA), Key(90, "MEDIA_FAST_FORWARD", MEDIA), Key(130, "MEDIA_RECORD", MEDIA),
        Key(226, "MEDIA_TOP_MENU", MEDIA),
        Key(19, "DPAD_UP", NAV), Key(20, "DPAD_DOWN", NAV), Key(21, "DPAD_LEFT", NAV), Key(22, "DPAD_RIGHT", NAV),
        Key(23, "DPAD_CENTER", NAV), Key(4, "BACK", NAV), Key(3, "HOME", NAV), Key(82, "MENU", NAV),
        Key(84, "SEARCH", NAV), Key(176, "SETTINGS", NAV), Key(83, "NOTIFICATION", NAV),
        Key(219, "ASSIST", NAV), Key(231, "VOICE_ASSIST", NAV), Key(171, "WINDOW", NAV), Key(26, "POWER", NAV),
        Key(223, "SLEEP", NAV), Key(224, "WAKEUP", NAV),
        Key(284, "ALL_APPS", GTV), Key(288, "PROFILE_SWITCH", GTV), Key(285, "REFRESH", GTV),
        Key(286, "THUMBS_UP", GTV), Key(287, "THUMBS_DOWN", GTV),
    ) + (0..9).map { Key(7 + it, "$it", NUM) } + // KEYCODE_0..KEYCODE_9
        (1..8).map { Key(288 + it, "VIDEO_APP_$it", GTV, 33) } +
        (1..4).map { Key(296 + it, "FEATURED_APP_$it", GTV, 33) } +
        (1..4).map { Key(300 + it, "DEMO_APP_$it", GTV, 33) }

    val groups: List<String> = listOf(TV, MEDIA, NAV, NUM, GTV)

    fun byCode(code: Int) = keys.firstOrNull { it.code == code }
}

/** A press or release of an app button, as read by [GeteventParser]. */
data class KeyPress(val button: RemoteButton, val down: Boolean)

/**
 * Parses `getevent -l` output and reports app-button presses. getevent prints the usage
 * (EV_MSC MSC_SCAN 000c0079) right before the key event (EV_KEY KEY_UNKNOWN DOWN).
 */
class GeteventParser {
    private val lastScan = HashMap<String, Int>()

    /** Returns the app-button press or release this line completes, or null. */
    fun feed(line: String): KeyPress? {
        val parts = line.trim().split(Regex("\\s+"))
        if (parts.size < 3) return null
        val hasDevice = parts[0].endsWith(":")
        val dev = if (hasDevice) parts[0] else ""
        val p = if (hasDevice) parts.drop(1) else parts
        if (p.size < 3) return null
        when {
            p[0] == "EV_MSC" && p[1] == "MSC_SCAN" -> p[2].toIntOrNull(16)?.let { lastScan[dev] = it }
            p[0] == "EV_KEY" && (p[2] == "DOWN" || p[2] == "UP") -> {
                val usage = (lastScan.remove(dev) ?: return null) and 0xFFFF
                val b = RemoteButton.fromUsage(usage) ?: return null
                return if (b.isAppKey) KeyPress(b, p[2] == "DOWN") else null
            }
            p[0] == "EV_KEY" -> lastScan.remove(dev)
        }
        return null
    }
}
