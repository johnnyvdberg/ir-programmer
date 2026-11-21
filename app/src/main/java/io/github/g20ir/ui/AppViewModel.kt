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
}

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
    val inputKeyId: Int = G20Key.INPUT.keyId,
    val log: List<String> = emptyList(),
    /** Manual editor: signal currently on the remote for trial, not yet saved. */
    val trial: Pair<G20Key, Assignment>? = null,
)

@SuppressLint("MissingPermission")
class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("g20", Context.MODE_PRIVATE)
    val db: CodeDatabase by lazy { CodeDatabase(app.assets.open("irdb.json").bufferedReader().readText()) }

    private val _ui = MutableStateFlow(
        UiState(
            config = prefs.getString("config", null)?.let { runCatching { RemoteConfig.fromJson(JSONObject(it)) }.getOrNull() }
                ?: RemoteConfig(),
            inputKeyId = prefs.getInt("inputKeyId", G20Key.INPUT.keyId),
        ),
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
                prefs.edit().putString("remote", dev.address).apply()
                eventsJob = launch { r.keyEvents.collect { onKeyEvent(it) } }
                toast("Connected. Up to ${r.maxWrite} bytes per code.")
            } else {
                toast("Could not use ${safeName(dev)} – see Diagnostics. Is it the G20 remote?")
            }
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

    private fun keyIdOf(k: G20Key) = if (k == G20Key.INPUT) _ui.value.inputKeyId else k.keyId

    private fun maxBytes(): Int = (remote?.maxWrite ?: G20Payload.DEFAULT_MAX_BYTES)
        .coerceAtMost(G20Payload.ABSOLUTE_MAX_BYTES)

    /** Encodes a signal for the remote, or returns an explanation of why it can't. */
    fun tryBuild(s: IrSignal): Result<G20Payload.Built> =
        try { Result.success(G20Payload.build(s, maxBytes())) } catch (e: EncodeException) { Result.failure(e) }

    /** Programs the remote with [signals]; returns the keys that could not be encoded. */
    private suspend fun programSignals(signals: Map<G20Key, IrSignal>): List<G20Key> {
        val r = remote ?: throw IllegalStateException("not connected")
        val failed = mutableListOf<G20Key>()
        val entries = G20Key.entries.mapNotNull { k ->
            val s = signals[k] ?: return@mapNotNull null
            tryBuild(s).fold(
                { keyIdOf(k) to it.bytes },
                { log("${k.label}: ${it.message}"); failed += k; null },
            )
        }
        r.program(entries)
        return failed
    }

    private fun savedSignals(): Map<G20Key, IrSignal> = _ui.value.config.keys.mapValues { it.value.signal }

    private fun saveConfig(c: RemoteConfig) {
        prefs.edit().putString("config", c.toJson().toString()).apply()
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
                r.clearAll(); toast("All keys send Bluetooth again (codes kept in this app).")
            } catch (e: Exception) {
                log("error: ${e.message}"); toast("Failed: ${e.message}")
            }
            _ui.update { it.copy(busy = null) }
        }
    }

    fun setInputKeyId(id: Int) {
        prefs.edit().putInt("inputKeyId", id).apply()
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

    private fun onKeyEvent(ev: G20Remote.KeyEvent) {
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

    override fun onCleared() {
        remote?.close()
    }
}
