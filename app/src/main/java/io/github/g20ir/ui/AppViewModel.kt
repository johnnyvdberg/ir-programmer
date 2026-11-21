package io.github.g20ir.ui

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.g20ir.ble.G20Remote
import io.github.g20ir.db.CodeDatabase
import io.github.g20ir.ir.EncodeException
import io.github.g20ir.ir.G20Payload
import io.github.g20ir.ir.IrSignal
import io.github.g20ir.model.Assignment
import io.github.g20ir.model.CodeSet
import io.github.g20ir.model.ConfigIo
import io.github.g20ir.net.WebTransfer
import android.view.KeyEvent
import java.io.File
import io.github.g20ir.model.G20Key
import io.github.g20ir.model.RemoteConfig
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** What the guided setup is configuring. */
enum class Role(val title: String, val subtitle: String, val keys: List<G20Key>, val categories: List<String>) {
    TV(
        "TV: all five keys",
        "Power, input, volume and mute all go to the TV",
        listOf(G20Key.POWER, G20Key.INPUT, G20Key.VOL_UP, G20Key.VOL_DOWN, G20Key.MUTE),
        listOf("TV", "Projector", "Monitor"),
    ),
    DISPLAY(
        "TV: power and input only",
        "Use this when volume is handled by a soundbar or receiver",
        listOf(G20Key.POWER, G20Key.INPUT),
        listOf("TV", "Projector", "Monitor"),
    ),
    AUDIO(
        "Soundbar / receiver: volume and mute",
        "Volume +, volume − and mute go to an audio device",
        listOf(G20Key.VOL_UP, G20Key.VOL_DOWN, G20Key.MUTE),
        listOf("Soundbar", "AV receiver", "Speaker", "TV"),
    ),
}

sealed interface Screen {
    data object Home : Screen
    data object PickRemote : Screen
    data object PickRole : Screen
    data class PickCategory(val role: Role?, val forKey: G20Key? = null) : Screen
    data class PickBrand(val role: Role?, val category: String, val forKey: G20Key? = null) : Screen
    data class PickCode(val key: G20Key, val category: String, val brand: String) : Screen
    data object Test : Screen
    data class EditKey(val key: G20Key) : Screen
    data object Diagnostics : Screen
    data object CheckRemote : Screen
    data object Transfer : Screen
    data object Buttons : Screen
    /** button == null: the shared action of the app buttons when the helper isn't running. */
    data class PickAction(val button: io.github.g20ir.buttons.RemoteButton?, val hold: Boolean = false) : Screen
}

data class CheckState(
    val info: List<Pair<String, String>> = emptyList(),
    val loading: Boolean = true,
    /** Per key: how the last press arrived. */
    val results: Map<G20Key, String> = emptyMap(),
    /** Raw log of what arrived, newest first. */
    val events: List<String> = emptyList(),
)

enum class Phase { PROGRAMMING, WAIT_PRESS, ASK, NO_MATCH, DONE, ERROR }

data class TestState(
    val title: String,
    val phase: Phase,
    /** The key the user should press now. */
    val key: G20Key,
    /** e.g. "Sony code 1/4 (Bravia, KD ...)" */
    val candidateLabel: String = "",
    /** Which step of the flow: finding a code set, or checking one key. */
    val verifying: Boolean = false,
    val message: String = "",
    val confirmed: Map<G20Key, IrSignal> = emptyMap(),
    val skipped: List<G20Key> = emptyList(),
)

data class UiState(
    val screen: Screen = Screen.Home,
    val remoteName: String? = null,
    val remoteState: G20Remote.State = G20Remote.State.DISCONNECTED,
    val mtu: Int = 23,
    val config: RemoteConfig = RemoteConfig(),
    val busy: String? = null,
    val toast: String? = null,
    val test: TestState? = null,
    val inputKeyId: Int = AUTO_INPUT_ID,
    val log: List<String> = emptyList(),
    /** Manual editor: signal currently on the remote for trial, not yet saved. */
    val trial: Pair<G20Key, Assignment>? = null,
    /** Bluetooth address of the selected remote; settings are stored per address. */
    val remoteAddress: String? = null,
    /** What this app last wrote to the remote successfully (null = unknown). */
    val applied: RemoteConfig? = null,
    val check: CheckState? = null,
    val pendingImport: ConfigIo.Imported? = null,
    val transferUrls: List<String> = emptyList(),
    val exportPath: String? = null,
    val codeFormat: G20Payload.Format = G20Payload.Format.SIMPLE,
) {
    /** Whether the remote holds exactly the saved codes. null = unknown. */
    val inSync: Boolean? get() = applied?.let { a -> a.keys.mapValues { it.value.signal } == config.keys.mapValues { it.value.signal } }
}

/** inputKeyId value meaning "write Input under both key IDs". */
const val AUTO_INPUT_ID = 0

@SuppressLint("MissingPermission")
class AppViewModel @JvmOverloads constructor(
    app: Application,
    /** Supplies the code database JSON; screenshot tests pass their own. */
    dbJson: (() -> String)? = null,
) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("g20", Context.MODE_PRIVATE)
    val db: CodeDatabase by lazy {
        CodeDatabase(dbJson?.invoke() ?: app.assets.open("irdb.json").bufferedReader().readText())
    }

    private fun loadConfig(key: String): RemoteConfig? =
        prefs.getString(key, null)?.let { runCatching { RemoteConfig.fromJson(JSONObject(it)) }.getOrNull() }

    private val _ui = MutableStateFlow(
        prefs.getString("remote", null).let { addr ->
            UiState(
                remoteAddress = addr,
                config = (addr?.let { loadConfig("config:$it") } ?: loadConfig("config")) ?: RemoteConfig(),
                applied = addr?.let { loadConfig("applied:$it") },
                inputKeyId = prefs.getInt(addr?.let { "inputKeyId:$it" } ?: "inputKeyId", prefs.getInt("inputKeyId", AUTO_INPUT_ID)),
                codeFormat = runCatching { G20Payload.Format.valueOf(prefs.getString("codeFormat", null)!!) }
                    .getOrDefault(G20Payload.Format.SIMPLE),
            )
        },
    )
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private var remote: G20Remote? = null
    private var eventsJob: Job? = null
    private val time = SimpleDateFormat("HH:mm:ss", Locale.US)

    // ---------------------------------------------------------------- log / navigation

    fun log(msg: String) {
        android.util.Log.i("G20IR", msg)
        _ui.update { it.copy(log = (it.log + "${time.format(Date())}  $msg").takeLast(300)) }
    }

    fun go(s: Screen) = _ui.update { it.copy(screen = s, toast = null) }
    fun toast(msg: String?) = _ui.update { it.copy(toast = msg) }

    fun back() {
        val s = _ui.value.screen
        when (s) {
            is Screen.PickBrand -> go(Screen.PickCategory(s.role, s.forKey))
            is Screen.PickCategory -> if (s.forKey != null) go(Screen.EditKey(s.forKey)) else go(Screen.PickRole)
            is Screen.PickCode -> go(Screen.PickBrand(null, s.category, s.key))
            is Screen.Test -> cancelTest()
            is Screen.EditKey -> leaveEditor()
            is Screen.Transfer -> closeTransfer()
            is Screen.PickAction -> go(Screen.Buttons)
            is Screen.CheckRemote -> { _ui.update { it.copy(check = null) }; go(Screen.Home) }
            else -> go(Screen.Home)
        }
    }

    // ---------------------------------------------------------------- remote connection

    fun bondedDevices(): List<BluetoothDevice> {
        val bm = getApplication<Application>().getSystemService(BluetoothManager::class.java)
        val list = try { bm?.adapter?.bondedDevices?.toList().orEmpty() } catch (e: SecurityException) {
            log("no Bluetooth permission: ${e.message}"); emptyList()
        }
        // Likely remotes first.
        return list.sortedByDescending { d ->
            val n = (try { d.name } catch (_: SecurityException) { null } ?: "").lowercase()
            listOf("g20", "g10", "remote", "rcu", "atv", "google", "shield").count { n.contains(it) }
        }
    }

    fun autoConnect() {
        if (remote != null) return
        val addr = prefs.getString("remote", null)
        val dev = bondedDevices().firstOrNull { it.address == addr }
        if (dev != null) connect(dev) else go(Screen.PickRemote)
    }

    fun connect(dev: BluetoothDevice) {
        viewModelScope.launch {
            remote?.close()
            eventsJob?.cancel()
            val r = G20Remote(getApplication(), dev, ::log)
            remote = r
            _ui.update { it.copy(remoteName = safeName(dev), busy = "Connecting to ${safeName(dev)}…", screen = Screen.Home) }
            launch { r.state.collect { st -> _ui.update { it.copy(remoteState = st, mtu = r.mtu) } } }
            val ok = try { r.connect() } catch (e: SecurityException) { log("permission: ${e.message}"); false }
            _ui.update { it.copy(busy = null, mtu = r.mtu) }
            if (ok) {
                selectRemote(dev.address)
                eventsJob = launch { r.keyEvents.collect { onKeyEvent(it) } }
                toast("Connected. Up to ${r.maxWrite} bytes per code.")
            } else {
                toast("Could not use ${safeName(dev)} – see Diagnostics. Is it the G20 remote?")
            }
        }
    }

    /**
     * Switches to the settings stored for this remote's Bluetooth address. The first time, the
     * settings from before per-remote storage existed are moved over to it.
     */
    private fun selectRemote(addr: String) {
        val e = prefs.edit().putString("remote", addr)
        var cfg = loadConfig("config:$addr")
        if (cfg == null) {
            cfg = loadConfig("config") ?: RemoteConfig()
            e.putString("config:$addr", cfg.toJson().toString()).remove("config")
            if (prefs.contains("inputKeyId")) e.putInt("inputKeyId:$addr", prefs.getInt("inputKeyId", AUTO_INPUT_ID)).remove("inputKeyId")
        }
        e.apply()
        _ui.update {
            it.copy(
                remoteAddress = addr,
                config = cfg,
                applied = loadConfig("applied:$addr"),
                inputKeyId = prefs.getInt("inputKeyId:$addr", AUTO_INPUT_ID),
            )
        }
    }

    private fun safeName(d: BluetoothDevice) = try { d.name ?: d.address } catch (_: SecurityException) { d.address }

    private fun ready(): G20Remote? {
        val r = remote
        if (r == null || r.state.value != G20Remote.State.READY) {
            toast("The remote is not connected.")
            return null
        }
        return r
    }

    // ---------------------------------------------------------------- programming

    /**
     * Key IDs a key is written under. Input goes under both 0x00B2 and 0x0139 unless one is
     * forced in Diagnostics: the firmware ignores the ID that isn't in its active key map
     * (normal vs "magic" input key) without using a slot, so one of them always lands.
     */
    private fun keyIdsOf(k: G20Key): List<Int> = when {
        k != G20Key.INPUT -> listOf(k.keyId)
        _ui.value.inputKeyId == AUTO_INPUT_ID -> listOf(G20Key.INPUT.keyId, G20Key.MAGIC_KEY_ID)
        else -> listOf(_ui.value.inputKeyId)
    }

    private fun keyIdOf(k: G20Key) = keyIdsOf(k).first()

    private fun maxBytes(): Int = (remote?.maxWrite ?: G20Payload.DEFAULT_MAX_BYTES)
        .coerceAtMost(G20Payload.ABSOLUTE_MAX_BYTES)

    /** Encodes a signal for the remote, or returns an explanation of why it can't. */
    fun tryBuild(s: IrSignal): Result<G20Payload.Built> =
        try { Result.success(G20Payload.build(s, maxBytes(), _ui.value.codeFormat)) } catch (e: EncodeException) { Result.failure(e) }

    /** Switches between the simple (type 2) and the reference (type 3/4) code layout. */
    fun toggleCodeFormat() {
        val next = if (_ui.value.codeFormat == G20Payload.Format.SIMPLE) G20Payload.Format.REFERENCE else G20Payload.Format.SIMPLE
        prefs.edit().putString("codeFormat", next.name).apply()
        _ui.update { it.copy(codeFormat = next) }
        toast("Code format: ${next.name.lowercase()}. Use \"Write saved codes\" to apply it.")
    }

    /** Programs the remote with [signals]; returns the keys that could not be encoded. */
    private suspend fun programSignals(signals: Map<G20Key, IrSignal>): List<G20Key> {
        val r = remote ?: throw IllegalStateException("not connected")
        val failed = mutableListOf<G20Key>()
        val entries = G20Key.entries.flatMap { k ->
            val s = signals[k] ?: return@flatMap emptyList()
            tryBuild(s).fold(
                { b -> keyIdsOf(k).map { it to b.bytes } },
                { log("${k.label}: ${it.message}"); failed += k; emptyList() },
            )
        }
        val built = signals.filterKeys { it !in failed }
        setApplied(null) // unknown while writing; a failure part-way leaves the remote unknown
        r.program(entries)
        val cfg = _ui.value.config
        setApplied(RemoteConfig(built.mapValues { (k, sig) ->
            Assignment(sig, cfg.keys[k]?.takeIf { it.signal == sig }?.source ?: "Test, not saved")
        }))
        return failed
    }

    private fun setApplied(c: RemoteConfig?) {
        val addr = _ui.value.remoteAddress
        if (addr != null) {
            val e = prefs.edit()
            if (c == null) e.remove("applied:$addr") else e.putString("applied:$addr", c.toJson().toString())
            e.apply()
        }
        _ui.update { it.copy(applied = c) }
    }

    private fun savedSignals(): Map<G20Key, IrSignal> = _ui.value.config.keys.mapValues { it.value.signal }

    private fun saveConfig(c: RemoteConfig) {
        val addr = _ui.value.remoteAddress
        prefs.edit().putString(if (addr != null) "config:$addr" else "config", c.toJson().toString()).apply()
        _ui.update { it.copy(config = c) }
    }

    /** Writes the saved configuration to the remote. */
    fun writeSaved() {
        val r = ready() ?: return
        viewModelScope.launch {
            _ui.update { it.copy(busy = "Writing to the remote…") }
            try {
                val failed = programSignals(savedSignals())
                r.setBleKeys(emptyList())
                toast(if (failed.isEmpty()) "Remote updated." else "Updated, but skipped: ${failed.joinToString { it.label }}")
            } catch (e: Exception) {
                log("error: ${e.message}"); toast("Failed: ${e.message}")
            }
            _ui.update { it.copy(busy = null) }
        }
    }

    fun clearRemote() {
        val r = ready() ?: return
        viewModelScope.launch {
            _ui.update { it.copy(busy = "Clearing IR codes…") }
            try {
                r.clearAll()
                setApplied(RemoteConfig())
                toast("All keys send Bluetooth again (codes kept in this app).")
            } catch (e: Exception) {
                log("error: ${e.message}"); toast("Failed: ${e.message}")
            }
            _ui.update { it.copy(busy = null) }
        }
    }

    fun setInputKeyId(id: Int) {
        val addr = _ui.value.remoteAddress
        prefs.edit().putInt(if (addr != null) "inputKeyId:$addr" else "inputKeyId", id).apply()
        _ui.update { it.copy(inputKeyId = id) }
    }

    fun setNotifications(on: Boolean) {
        val r = ready() ?: return
        viewModelScope.launch {
            try { r.setKeyNotifications(on); toast("Key reports ${if (on) "on" else "off"}.") } catch (e: Exception) {
                log("error: ${e.message}"); toast("Failed: ${e.message}")
            }
        }
    }

    // ---------------------------------------------------------------- guided test

    private var role: Role = Role.TV
    private var sets: List<CodeSet> = emptyList()
    private var setIndex = 0
    private var verifyQueue: ArrayDeque<G20Key> = ArrayDeque()
    private var alternatives: List<IrSignal> = emptyList()
    private var altIndex = 0
    private var current: MutableMap<G20Key, IrSignal> = mutableMapOf()

    private val testOrder = listOf(G20Key.VOL_UP, G20Key.MUTE, G20Key.INPUT, G20Key.POWER, G20Key.VOL_DOWN)
    private val verifyOrder = listOf(G20Key.VOL_DOWN, G20Key.MUTE, G20Key.VOL_UP, G20Key.INPUT, G20Key.POWER)

    fun startTest(role: Role, category: String, brand: String) {
        val r = ready() ?: return
        this.role = role
        sets = db.codeSets(category, brand).filter { s -> role.keys.any { it in s.keys } }
        setIndex = 0
        _ui.update {
            it.copy(
                screen = Screen.Test,
                test = TestState("$brand $category", Phase.PROGRAMMING, role.keys.first()),
            )
        }
        viewModelScope.launch {
            try { r.setKeyNotifications(true) } catch (e: Exception) {
                log("could not enable key reports: ${e.message} – use the \"I pressed it\" button")
            }
            trySet()
        }
    }

    private fun updateTest(f: (TestState) -> TestState) = _ui.update { s -> s.copy(test = s.test?.let(f)) }

    /** Programs code set [setIndex] and asks the user to press its test key. */
    private suspend fun trySet() {
        while (setIndex < sets.size) {
            val set = sets[setIndex]
            val testKey = testOrder.firstOrNull { it in role.keys && set.keys[it]?.firstOrNull()?.let { s -> tryBuild(s).isSuccess } == true }
            if (testKey == null) {
                log("skipping ${set.label(setIndex, sets.size)}: nothing this remote can send")
                setIndex++
                continue
            }
            current = savedSignals().filterKeys { it !in role.keys }.toMutableMap()
            for (k in role.keys) set.keys[k]?.firstOrNull { tryBuild(it).isSuccess }?.let { current[k] = it }
            updateTest {
                it.copy(phase = Phase.PROGRAMMING, key = testKey, candidateLabel = set.label(setIndex, sets.size),
                    verifying = false, message = "", confirmed = emptyMap(), skipped = emptyList())
            }
            try {
                programSignals(current)
            } catch (e: Exception) {
                log("error: ${e.message}")
                updateTest { it.copy(phase = Phase.ERROR, message = e.message ?: "programming failed") }
                return
            }
            updateTest { it.copy(phase = Phase.WAIT_PRESS) }
            return
        }
        updateTest {
            it.copy(phase = Phase.NO_MATCH, message = if (sets.isEmpty()) "There are no codes for this brand." else
                "None of the ${sets.size} code sets worked. Try another brand, or set a key by hand.")
        }
    }

    private fun keyForId(id: Int): G20Key? =
        if (id == G20Key.MAGIC_KEY_ID) G20Key.INPUT else G20Key.entries.firstOrNull { keyIdOf(it) == id }

    private fun onKeyEvent(ev: G20Remote.KeyEvent) {
        if (_ui.value.screen == Screen.CheckRemote) {
            val k = keyForId(ev.keyId)
            addCheckEvent("IR report from remote: key 0x%04X%s %s".format(ev.keyId, k?.let { " (${it.label})" } ?: "", if (ev.down) "down" else "up"))
            if (ev.down) k?.let { markCheck(it, "Sends IR (reported by the remote)") }
            return
        }
        val t = _ui.value.test ?: return
        if (!ev.down || t.phase != Phase.WAIT_PRESS || _ui.value.screen != Screen.Test) return
        if (ev.keyId == keyIdOf(t.key) || (t.key == G20Key.INPUT && ev.keyId == G20Key.MAGIC_KEY_ID)) {
            updateTest { it.copy(phase = Phase.ASK) }
        } else {
            updateTest { it.copy(message = "That was another key. Please press ${t.key.label}.") }
        }
    }

    /** Fallback when key reports don't arrive. */
    fun pressedManually() = updateTest { if (it.phase == Phase.WAIT_PRESS) it.copy(phase = Phase.ASK) else it }

    fun pressAgain() = updateTest { it.copy(phase = Phase.WAIT_PRESS, message = "") }

    fun answer(worked: Boolean) {
        val t = _ui.value.test ?: return
        viewModelScope.launch {
            if (!t.verifying) {
                if (!worked) { setIndex++; trySet(); return@launch }
                // Found a code set. Now check the other keys of this role one by one.
                val confirmed = mapOf(t.key to current.getValue(t.key))
                updateTest { it.copy(confirmed = confirmed, verifying = true) }
                verifyQueue = ArrayDeque(verifyOrder.filter { it in role.keys && it != t.key })
                nextVerify()
            } else {
                if (worked) {
                    updateTest { it.copy(confirmed = it.confirmed + (t.key to current.getValue(t.key))) }
                    nextVerify()
                } else {
                    tryAlternative(t.key)
                }
            }
        }
    }

    fun skipKey() {
        val t = _ui.value.test ?: return
        if (!t.verifying) return
        viewModelScope.launch {
            current.remove(t.key)
            updateTest { it.copy(skipped = it.skipped + t.key) }
            nextVerify()
        }
    }

    private suspend fun nextVerify() {
        val key = verifyQueue.removeFirstOrNull()
        if (key == null) { finishTest(); return }
        // Candidates for this key: the code set's own signals first, then what other code sets
        // of the same brand use for it (most common first).
        val own = sets[setIndex].keys[key].orEmpty()
        val others = sets.flatMap { it.keys[key].orEmpty() }
        alternatives = (own + others).distinct().filter { tryBuild(it).isSuccess }
        altIndex = 0
        if (alternatives.isEmpty()) {
            log("no usable code for ${key.label}")
            current.remove(key)
            updateTest { it.copy(skipped = it.skipped + key) }
            nextVerify()
            return
        }
        updateTest { it.copy(key = key, message = "") }
        if (current[key] != alternatives[0]) {
            current[key] = alternatives[0]
            reprogram()
        } else {
            updateTest { it.copy(phase = Phase.WAIT_PRESS) }
        }
    }

    private suspend fun tryAlternative(key: G20Key) {
        altIndex++
        if (altIndex >= alternatives.size) {
            current.remove(key)
            updateTest { it.copy(skipped = it.skipped + key, message = "No working code found for ${key.label}.") }
            nextVerify()
            return
        }
        current[key] = alternatives[altIndex]
        updateTest { it.copy(message = "Trying another code for ${key.label} (${altIndex + 1}/${alternatives.size}).") }
        reprogram()
    }

    private suspend fun reprogram() {
        updateTest { it.copy(phase = Phase.PROGRAMMING) }
        try {
            programSignals(current)
            updateTest { it.copy(phase = Phase.WAIT_PRESS) }
        } catch (e: Exception) {
            log("error: ${e.message}")
            updateTest { it.copy(phase = Phase.ERROR, message = e.message ?: "programming failed") }
        }
    }

    private suspend fun finishTest() {
        val t = _ui.value.test ?: return
        val set = sets[setIndex]
        var cfg = _ui.value.config
        for (k in role.keys) {
            val s = t.confirmed[k]
            cfg = cfg.with(k, s?.let { Assignment(it, "${set.brand} (${set.models.first()}) – tested") })
        }
        saveConfig(cfg)
        try {
            programSignals(savedSignals())
            remote?.setBleKeys(emptyList())
        } catch (e: Exception) { log("error: ${e.message}") }
        val skipped = role.keys.filter { it !in t.confirmed }
        updateTest {
            it.copy(phase = Phase.DONE, message = if (skipped.isEmpty()) "All keys work." else
                "Not set (they keep controlling the Shield): ${skipped.joinToString { k -> k.label }}.")
        }
    }

    fun retryProgramming() = viewModelScope.launch { reprogram() }

    fun cancelTest() {
        viewModelScope.launch {
            _ui.update { it.copy(test = null, screen = Screen.Home) }
            // Put back what was saved before the test.
            if (remote?.state?.value == G20Remote.State.READY) {
                try { programSignals(savedSignals()) } catch (e: Exception) { log("error: ${e.message}") }
            }
        }
    }

    fun closeTest() = _ui.update { it.copy(test = null, screen = Screen.Home) }

    // ---------------------------------------------------------------- manual editor

    /** Programs [signal] on [key] (everything else as saved) so the user can try it. */
    fun trial(key: G20Key, signal: IrSignal, source: String) {
        val r = ready() ?: return
        tryBuild(signal).onFailure { toast(it.message); return }
        viewModelScope.launch {
            _ui.update { it.copy(busy = "Sending to the remote…") }
            try {
                r.setKeyNotifications(true)
            } catch (_: Exception) {}
            try {
                programSignals(savedSignals() + (key to signal))
                _ui.update { it.copy(trial = key to Assignment(signal, source), toast = "Now press ${key.label} on the remote.") }
            } catch (e: Exception) {
                log("error: ${e.message}"); toast("Failed: ${e.message}")
            }
            _ui.update { it.copy(busy = null) }
        }
    }

    fun keepTrial() {
        val (k, a) = _ui.value.trial ?: return
        saveConfig(_ui.value.config.with(k, a))
        _ui.update { it.copy(trial = null, toast = "${k.label} saved.") }
    }

    fun clearKey(key: G20Key) {
        saveConfig(_ui.value.config.with(key, null))
        _ui.update { it.copy(trial = null) }
        writeSaved()
    }

    private fun leaveEditor() {
        val hadTrial = _ui.value.trial != null
        _ui.update { it.copy(trial = null, screen = Screen.Home) }
        if (hadTrial) writeSaved() // undo the unsaved trial on the remote
    }

    fun useCode(key: G20Key, set: CodeSet, signal: IrSignal) {
        go(Screen.EditKey(key))
        trial(key, signal, "${set.brand} (${set.models.first()})")
    }

    /** For screenshot tests: set the UI state directly. */
    @androidx.annotation.VisibleForTesting
    fun setStateForPreview(f: (UiState) -> UiState) = _ui.update(f)

    // ---------------------------------------------------------------- check remote

    fun openCheck() {
        val r = ready() ?: return
        _ui.update { it.copy(screen = Screen.CheckRemote, check = CheckState()) }
        viewModelScope.launch {
            try { r.setKeyNotifications(true) } catch (e: Exception) { log("key reports: ${e.message}") }
            val info = try { r.deviceInfo() } catch (e: Exception) { log("info: ${e.message}"); emptyList() }
            _ui.update { it.copy(check = (it.check ?: CheckState()).copy(info = info, loading = false)) }
        }
    }

    private fun addCheckEvent(line: String) {
        log(line)
        _ui.update { s -> s.copy(check = s.check?.let { it.copy(events = (listOf(line) + it.events).take(12)) }) }
    }

    private fun markCheck(k: G20Key, how: String) =
        _ui.update { s -> s.copy(check = s.check?.let { it.copy(results = it.results + (k to how)) }) }

    /**
     * Android key events reaching the app while the check screen is open: a key without an IR
     * code arrives here as a normal Bluetooth key. Returns true to swallow it (so the volume
     * doesn't change while checking).
     */
    fun onAndroidKey(e: KeyEvent): Boolean {
        if (_ui.value.screen != Screen.CheckRemote) return false
        val k = when (e.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> G20Key.VOL_UP
            KeyEvent.KEYCODE_VOLUME_DOWN -> G20Key.VOL_DOWN
            KeyEvent.KEYCODE_VOLUME_MUTE, KeyEvent.KEYCODE_MUTE -> G20Key.MUTE
            KeyEvent.KEYCODE_TV_INPUT -> G20Key.INPUT
            KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_TV_POWER -> G20Key.POWER
            else -> null
        }
        if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) {
            addCheckEvent("Bluetooth key: ${KeyEvent.keyCodeToString(e.keyCode)} (${e.keyCode}), scan code ${e.scanCode}")
        }
        if (k == null) return false
        if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) markCheck(k, "Sends Bluetooth (no IR code)")
        return true
    }

    // ---------------------------------------------------------------- remote buttons

    fun openButtons() {
        io.github.g20ir.buttons.ButtonStore.init(getApplication())
        go(Screen.Buttons)
    }

    /** Apps that can be opened from a button, TV apps first. */
    fun launchableApps(): List<io.github.g20ir.buttons.ButtonAction.App> {
        val pm = getApplication<Application>().packageManager
        val out = LinkedHashMap<String, io.github.g20ir.buttons.ButtonAction.App>()
        for (cat in listOf(android.content.Intent.CATEGORY_LEANBACK_LAUNCHER, android.content.Intent.CATEGORY_LAUNCHER)) {
            val i = android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(cat)
            for (ri in pm.queryIntentActivities(i, 0)) {
                val pkg = ri.activityInfo.packageName
                if (pkg == getApplication<Application>().packageName || pkg in out) continue
                out[pkg] = io.github.g20ir.buttons.ButtonAction.App(pkg, ri.loadLabel(pm).toString())
            }
        }
        return out.values.sortedBy { it.label.lowercase() }
    }

    fun pickAction(b: io.github.g20ir.buttons.RemoteButton?, hold: Boolean, a: io.github.g20ir.buttons.ButtonAction) {
        when {
            b == null -> io.github.g20ir.buttons.ButtonStore.setSharedAppAction(a)
            hold -> io.github.g20ir.buttons.ButtonStore.setHoldAction(b, a)
            else -> io.github.g20ir.buttons.ButtonStore.setAction(b, a)
        }
        go(Screen.Buttons)
    }

    /** Same repair as after boot: re-apply the accessibility setting so Android binds again. */
    fun repairButtonService() {
        val app = getApplication<Application>()
        if (!io.github.g20ir.buttons.ServiceRepair.canRepair(app)) {
            toast("Needs permission: turn the helper on once, or run adb shell pm grant io.github.g20ir android.permission.WRITE_SECURE_SETTINGS")
            return
        }
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val ok = io.github.g20ir.buttons.ServiceRepair.rebind(app)
            toast(if (ok) "Button service restarted." else "Could not restart the button service.")
        }
    }

    fun canRepairButtonService(): Boolean = io.github.g20ir.buttons.ServiceRepair.canRepair(getApplication())

    fun openAccessibilitySettings() {
        val app = getApplication<Application>()
        for (action in listOf(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS, android.provider.Settings.ACTION_SETTINGS)) {
            try {
                app.startActivity(android.content.Intent(action).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: Exception) {}
        }
        toast("Could not open the settings.")
    }

    // ---------------------------------------------------------------- export / import

    private var web: WebTransfer? = null

    fun exportText(): String = ConfigIo.export(_ui.value.config, _ui.value.inputKeyId)

    private fun exportFile(): File? = getApplication<Application>().getExternalFilesDir(null)?.let { File(it, ConfigIo.FILE_NAME) }

    fun openTransfer() {
        val f = exportFile()
        val path = try { f?.writeText(exportText()); f?.absolutePath } catch (e: Exception) { log("export file: ${e.message}"); null }
        val w = web ?: WebTransfer(::exportText, ::receiveImport, ::log).also { web = it }
        w.start()
        _ui.update { it.copy(screen = Screen.Transfer, transferUrls = w.urls(), exportPath = path, pendingImport = null) }
    }

    private fun closeTransfer() {
        web?.stop()
        _ui.update { it.copy(screen = Screen.Home, pendingImport = null, transferUrls = emptyList()) }
    }

    /** Called from the web server thread. */
    private fun receiveImport(text: String): String = try {
        val imp = ConfigIo.import(text)
        _ui.update { it.copy(pendingImport = imp) }
        "Received ${imp.config.keys.size} key(s). Confirm on the TV." +
            if (imp.warnings.isEmpty()) "" else " Skipped: ${imp.warnings.joinToString("; ")}"
    } catch (e: IllegalArgumentException) {
        "Not imported: ${e.message}"
    }

    /** Reads g20-ir-config.json from the app's folder (copy it there with adb). */
    fun importFromFile() {
        val f = exportFile()
        if (f == null || !f.exists()) { toast("No file at ${f?.absolutePath}"); return }
        val msg = receiveImport(f.readText())
        if (_ui.value.pendingImport == null) toast(msg)
    }

    fun applyImport(write: Boolean) {
        val imp = _ui.value.pendingImport ?: return
        saveConfig(imp.config)
        imp.inputKeyId?.let { setInputKeyId(it) }
        _ui.update { it.copy(pendingImport = null) }
        exportFile()?.let { runCatching { it.writeText(exportText()) } }
        if (write) writeSaved() else toast("Imported. Use \"Write saved codes\" to send them to the remote.")
    }

    fun discardImport() = _ui.update { it.copy(pendingImport = null) }

    override fun onCleared() {
        web?.stop()
        remote?.close()
    }
}
