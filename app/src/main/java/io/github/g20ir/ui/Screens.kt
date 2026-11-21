package io.github.g20ir.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import io.github.g20ir.ble.G20Remote
import io.github.g20ir.ir.EncodeException
import io.github.g20ir.ir.IrSignal
import io.github.g20ir.ir.Protocol
import io.github.g20ir.ir.TextFormats
import io.github.g20ir.model.G20Key
import kotlinx.coroutines.delay

// ------------------------------------------------------------------ building blocks

@Composable
internal fun Title(text: String, sub: String? = null) {
    Text(text, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onBackground)
    if (sub != null) {
        Text(sub, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f))
    }
    Spacer(Modifier.height(16.dp))
}

@Composable
internal fun Item(
    title: String,
    sub: String? = null,
    focus: FocusRequester? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    ListItem(
        selected = false,
        onClick = onClick,
        enabled = enabled,
        headlineContent = { Text(title) },
        supportingContent = sub?.let { { Text(it, maxLines = 2) } },
        modifier = if (focus != null) Modifier.focusRequester(focus) else Modifier,
    )
}

@Composable
internal fun rememberInitialFocus(key: Any? = Unit): FocusRequester {
    val fr = remember { FocusRequester() }
    LaunchedEffect(key) {
        delay(50)
        runCatching { fr.requestFocus() }
    }
    return fr
}

@Composable
internal fun Section(text: String) {
    Spacer(Modifier.height(12.dp))
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
    Spacer(Modifier.height(4.dp))
}

@Composable
internal fun Body(text: String, color: Color = MaterialTheme.colorScheme.onBackground) {
    Text(text, style = MaterialTheme.typography.bodyLarge, color = color)
}

@Composable
fun StatusOverlay(ui: UiState, vm: AppViewModel) {
    val msg = ui.busy ?: ui.toast ?: return
    if (ui.busy == null) {
        LaunchedEffect(ui.toast) { delay(5000); vm.toast(null) }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Text(
            msg,
            modifier = Modifier
                .background(Color(0xEE2B3138), RoundedCornerShape(8.dp))
                .padding(horizontal = 20.dp, vertical = 10.dp),
            color = Color.White,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

// ------------------------------------------------------------------ home

@Composable
fun HomeScreen(vm: AppViewModel, ui: UiState) {
    val status = when (ui.remoteState) {
        G20Remote.State.READY -> "Connected to ${ui.remoteName} (max ${ui.mtu - 3} bytes per code)"
        G20Remote.State.CONNECTING -> "Connecting to ${ui.remoteName}…"
        G20Remote.State.FAILED -> "Could not use ${ui.remoteName}"
        G20Remote.State.DISCONNECTED -> if (ui.remoteName == null) "No remote selected" else "${ui.remoteName} disconnected"
    }
    val sync = when {
        ui.remoteAddress == null -> null
        ui.inSync == true -> "On the remote: the saved codes"
        ui.inSync == false -> "On the remote: different from the saved codes – use \"Write saved codes\""
        else -> "On the remote: unknown – use \"Write saved codes\" to be sure"
    }
    Title("G20 remote IR setup", listOfNotNull(status, sync).joinToString("\n"))
    val first = rememberInitialFocus()
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
        LazyColumn(Modifier.weight(1f)) {
            item {
                Item("Guided setup", "Find working codes by testing them on your TV or soundbar", first) {
                    if (ui.remoteState == G20Remote.State.READY) vm.go(Screen.PickRole) else vm.toast("Connect the remote first.")
                }
            }
            item { Item("Write saved codes to the remote", "Use after changing remotes or clearing it") { vm.writeSaved() } }
            if (ui.remoteState != G20Remote.State.READY && ui.remoteName != null) {
                item { Item("Reconnect", ui.remoteName) { vm.autoConnect() } }
            }
            item { Item("Choose remote", "Pick the paired G20 remote") { vm.go(Screen.PickRemote) } }
            item { Item("Check remote", "Firmware, battery, and which keys send IR") { vm.openCheck() } }
            item { Item("Export / import", "Save or load settings from a phone or PC browser") { vm.openTransfer() } }
            item { Item("Remote buttons", "Colour, YouTube, Netflix, Prime Video, rocket and gear buttons") { vm.openButtons() } }
            item { Item("Clear IR codes on the remote", "All keys control the Shield again") { vm.clearRemote() } }
            item { Item("Diagnostics", "Log, payloads, input key ID") { vm.go(Screen.Diagnostics) } }
        }
        LazyColumn(Modifier.weight(1f)) {
            item { Section("Keys (select one to set it by hand)") }
            items(G20Key.entries) { k ->
                val a = ui.config.keys[k]
                Item(
                    k.label,
                    if (a == null) "Not set: controls the Shield over Bluetooth" else "${a.source}\n${a.signal.describe()}",
                ) { vm.go(Screen.EditKey(k)) }
            }
        }
    }
}

// ------------------------------------------------------------------ pickers

@Composable
fun PickRemoteScreen(vm: AppViewModel) {
    val devices = remember { vm.bondedDevices() }
    Title("Choose the remote", "Paired Bluetooth devices. The G20 usually shows up as a remote or by its model name.")
    val first = rememberInitialFocus()
    LazyColumn {
        if (devices.isEmpty()) item { Body("No paired devices found.") }
        items(devices.size) { i ->
            val d = devices[i]
            val name = try { d.name } catch (_: SecurityException) { null } ?: "(no name)"
            Item(name, d.address, if (i == 0) first else null) { vm.connect(d) }
        }
    }
}

@Composable
fun PickRoleScreen(vm: AppViewModel) {
    Title("What do you want to set up?", "You can run the guided setup twice, e.g. TV first and then a soundbar.")
    val first = rememberInitialFocus()
    LazyColumn {
        items(Role.entries.size) { i ->
            val r = Role.entries[i]
            Item(r.title, r.subtitle, if (i == 0) first else null) { vm.go(Screen.PickCategory(r)) }
        }
    }
}

@Composable
fun PickCategoryScreen(vm: AppViewModel, s: Screen.PickCategory) {
    val cats = s.role?.categories ?: vm.db.categories
    Title("What kind of device?", s.forKey?.let { "Code for ${it.label}" })
    val first = rememberInitialFocus()
    LazyColumn {
        items(cats.size) { i ->
            val c = cats[i]
            Item(c, "${vm.db.brands(c).size} brands", if (i == 0) first else null) {
                vm.go(Screen.PickBrand(s.role, c, s.forKey))
            }
        }
    }
}

@Composable
fun PickBrandScreen(vm: AppViewModel, s: Screen.PickBrand) {
    val brands = remember(s.category) { vm.db.brands(s.category) }
    val popular = brands.take(10)
    val all = remember(brands) { brands.sortedBy { it.name.lowercase() } }
    Title("Brand", "${s.category}: most common brands first, then A to Z")
    val first = rememberInitialFocus()
    fun pick(name: String) {
        if (s.role != null) vm.startTest(s.role, s.category, name)
        else if (s.forKey != null) vm.go(Screen.PickCode(s.forKey, s.category, name))
    }
    LazyColumn {
        item { Section("Popular") }
        items(popular.size) { i ->
            val b = popular[i]
            Item(b.name, "${b.codeSets} code set(s), ${b.models} models", if (i == 0) first else null) { pick(b.name) }
        }
        item { Section("All brands") }
        items(all.size) { i ->
            val b = all[i]
            Item(b.name, "${b.codeSets} code set(s), ${b.models} models") { pick(b.name) }
        }
    }
}

@Composable
fun PickCodeScreen(vm: AppViewModel, s: Screen.PickCode) {
    val sets = remember(s) { vm.db.codeSets(s.category, s.brand).filter { s.key in it.keys } }
    Title("${s.brand}: codes for ${s.key.label}", "Select one to send it to the remote, then press ${s.key.label} to try it.")
    val first = rememberInitialFocus()
    LazyColumn {
        if (sets.isEmpty()) item { Body("This brand has no ${s.key.label} code in the database.") }
        var n = 0
        sets.forEachIndexed { si, set ->
            set.keys[s.key].orEmpty().forEach { sig ->
                val idx = n++
                val fits = vm.tryBuild(sig)
                item {
                    Item(
                        "Code ${si + 1}: ${sig.describe()}",
                        set.models.take(4).joinToString(", ") + (fits.exceptionOrNull()?.let { "\nNot usable: ${it.message}" } ?: ""),
                        if (idx == 0) first else null,
                        enabled = fits.isSuccess,
                    ) { vm.useCode(s.key, set, sig) }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ guided test

@Composable
fun TestScreen(vm: AppViewModel, ui: UiState) {
    val t = ui.test ?: return
    val device = t.title
    Title("Testing codes: ${t.title}", t.candidateLabel)
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
        Column(Modifier.weight(2f)) {
            when (t.phase) {
                Phase.PROGRAMMING -> Body("Sending the code to the remote…")
                Phase.WAIT_PRESS -> {
                    val fr = rememberInitialFocus(t)
                    Text(
                        "Point the remote at your $device and press ${t.key.label.uppercase()}",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    if (t.key == G20Key.POWER) Body("If it switches off, wait a few seconds and press POWER again to switch it back on.")
                    Spacer(Modifier.height(8.dp))
                    Body("This screen continues by itself when the remote reports the press.")
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = { vm.pressedManually() }, modifier = Modifier.focusRequester(fr)) { Text("I pressed it") }
                        if (t.verifying) OutlinedButton(onClick = { vm.skipKey() }) { Text("Skip this key") }
                        OutlinedButton(onClick = { vm.cancelTest() }) { Text("Cancel") }
                    }
                }
                Phase.ASK -> {
                    val fr = rememberInitialFocus(t)
                    Text(
                        "Did the $device respond to ${t.key.label.uppercase()}?",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = { vm.answer(true) }, modifier = Modifier.focusRequester(fr)) { Text("Yes") }
                        Button(onClick = { vm.answer(false) }) { Text(if (t.verifying) "No, try another code" else "No, next code set") }
                        OutlinedButton(onClick = { vm.pressAgain() }) { Text("Let me press again") }
                    }
                }
                Phase.NO_MATCH -> {
                    val fr = rememberInitialFocus(t)
                    Body(t.message)
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = { vm.cancelTest() }, modifier = Modifier.focusRequester(fr)) { Text("Back") }
                    }
                }
                Phase.DONE -> {
                    val fr = rememberInitialFocus(t)
                    Text("Done", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onBackground)
                    Body(t.message)
                    Body("The codes are saved on the remote and in this app.")
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { vm.closeTest() }, modifier = Modifier.focusRequester(fr)) { Text("Finish") }
                }
                Phase.ERROR -> {
                    val fr = rememberInitialFocus(t)
                    Body("Could not program the remote: ${t.message}", Color(0xFFFF8A80))
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = { vm.retryProgramming() }, modifier = Modifier.focusRequester(fr)) { Text("Retry") }
                        OutlinedButton(onClick = { vm.cancelTest() }) { Text("Cancel") }
                    }
                }
            }
            if (t.message.isNotEmpty() && t.phase !in listOf(Phase.NO_MATCH, Phase.DONE, Phase.ERROR)) {
                Spacer(Modifier.height(12.dp))
                Body(t.message, MaterialTheme.colorScheme.primary)
            }
        }
        Column(Modifier.weight(1f)) {
            Section("Keys")
            for (k in G20Key.entries) {
                val mark = when {
                    k in t.confirmed -> "✓"
                    k in t.skipped -> "–"
                    k == t.key && t.phase != Phase.DONE -> "▶"
                    else -> " "
                }
                Text("$mark  ${k.label}", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground)
            }
        }
    }
}

// ------------------------------------------------------------------ manual editor

/** Decimal by default ("37"); hex with a 0x prefix ("0x25") or an h suffix ("25h"). */
internal fun parseNumber(text: String): Long? {
    val t = text.trim().lowercase()
    return when {
        t.startsWith("0x") -> t.drop(2).toLongOrNull(16)
        t.endsWith("h") -> t.dropLast(1).toLongOrNull(16)
        else -> t.toLongOrNull()
    }
}

@Composable
fun EditKeyScreen(vm: AppViewModel, ui: UiState, key: G20Key) {
    val saved = ui.config.keys[key]
    val parsed = saved?.signal as? IrSignal.Parsed
    var protocol by remember(key) { mutableStateOf(parsed?.protocol ?: Protocol.NEC) }
    var address by remember(key) { mutableStateOf(parsed?.address?.toString() ?: "0") }
    var command by remember(key) { mutableStateOf(parsed?.command?.toString() ?: "0") }
    var pasted by remember(key) { mutableStateOf("") }
    val trial = ui.trial?.takeIf { it.first == key }?.second

    Title(
        "Set ${key.label}",
        if (saved == null) "Not set: this key controls the Shield over Bluetooth" else "Saved: ${saved.source}, ${saved.signal.describe()}",
    )
    val first = rememberInitialFocus()

    fun sendProtocol(cmd: Long) {
        val a = parseNumber(address)
        if (a == null || a < 0 || a > protocol.maxAddress) { vm.toast("Address must be 0..${protocol.maxAddress} (0x${protocol.maxAddress.toString(16).uppercase()})"); return }
        if (cmd < 0 || cmd > protocol.maxCommand) { vm.toast("Command must be 0..${protocol.maxCommand} (0x${protocol.maxCommand.toString(16).uppercase()})"); return }
        command = cmd.toString()
        vm.trial(key, IrSignal.Parsed(protocol, a, cmd), "Entered by hand")
    }

    Column(Modifier.verticalScroll(rememberScrollState())) {
        if (trial != null) {
            Body("On the remote now (not saved yet): ${trial.signal.describe()}", MaterialTheme.colorScheme.primary)
            Body("Press ${key.label} on the remote. If it works, keep it.")
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { vm.keepTrial() }) { Text("Keep this code") }
            }
        }

        Section("From the code database")
        Item("Pick a brand and code", "Every code for ${key.label} in the database, per brand", first) {
            vm.go(Screen.PickCategory(null, key))
        }

        Section("Protocol, address and command (decimal, or 0x… for hex)")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = {
                protocol = Protocol.entries[(protocol.ordinal + 1) % Protocol.entries.size]
            }) { Text("Protocol: ${protocol.id}") }
            OutlinedTextField(address, { address = it }, label = { androidx.compose.material3.Text("Address") },
                singleLine = true, modifier = Modifier.width(160.dp))
            OutlinedTextField(command, { command = it }, label = { androidx.compose.material3.Text("Command") },
                singleLine = true, modifier = Modifier.width(160.dp))
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { parseNumber(command)?.let { sendProtocol(it) } ?: vm.toast("Command is not a number") }) {
                Text("Send to remote")
            }
            OutlinedButton(onClick = { parseNumber(command)?.let { sendProtocol(it - 1) } }) { Text("◀ Command −1") }
            OutlinedButton(onClick = { parseNumber(command)?.let { sendProtocol(it + 1) } }) { Text("Command +1 ▶") }
        }
        Body("Tip: to find a missing code, keep the address of a key that works and step through commands.")

        Section("Paste a code")
        OutlinedTextField(
            pasted, { pasted = it },
            label = { androidx.compose.material3.Text("Pronto hex, G20 hex payload, or raw µs timings (\"38000: 9000 4500 …\")") },
            modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 4,
        )
        Spacer(Modifier.height(8.dp))
        Button(onClick = {
            try { vm.trial(key, TextFormats.parse(pasted), "Pasted code") } catch (e: EncodeException) { vm.toast(e.message) }
        }) { Text("Send to remote") }

        Section("Other")
        Item("Clear ${key.label}", "The key goes back to controlling the Shield over Bluetooth") { vm.clearKey(key) }
        Spacer(Modifier.height(24.dp))
    }
}

// ------------------------------------------------------------------ diagnostics

@Composable
fun DiagnosticsScreen(vm: AppViewModel, ui: UiState) {
    Title("Diagnostics", "Remote: ${ui.remoteName ?: "none"}, state ${ui.remoteState}, MTU ${ui.mtu}")
    val first = rememberInitialFocus()
    LazyColumn {
        item {
            Item(
                "Input key ID: " + if (ui.inputKeyId == AUTO_INPUT_ID) "both 0x00B2 and 0x0139 (automatic)" else "0x%04X only".format(ui.inputKeyId),
                "Select to cycle. Automatic writes Input under both IDs; the remote keeps the one its key map uses.",
                first,
            ) {
                vm.setInputKeyId(
                    when (ui.inputKeyId) {
                        AUTO_INPUT_ID -> G20Key.INPUT.keyId
                        G20Key.INPUT.keyId -> G20Key.MAGIC_KEY_ID
                        else -> AUTO_INPUT_ID
                    },
                )
            }
        }
        item {
            Item(
                "Code format: " + if (ui.codeFormat == io.github.g20ir.ir.G20Payload.Format.SIMPLE) "simple (type 2)" else "reference (type 3/4)",
                "Simple works on all remotes tested. Reference uses dedicated repeat codes and RC5/RC6 toggling, " +
                    "which only Google's reference firmware handles. Write the saved codes again after switching.",
            ) { vm.toggleCodeFormat() }
        }
        item { Item("Turn key reports off", "Stops the remote reporting IR key presses to the Shield") { vm.setNotifications(false) } }
        item { Item("Turn key reports on") { vm.setNotifications(true) } }
        item { Section("Saved payloads") }
        items(G20Key.entries) { k ->
            val a = ui.config.keys[k]
            val text = if (a == null) "not set" else vm.tryBuild(a.signal).fold(
                { "${it.summary}, ${it.bytes.size} bytes\n${it.hex}\nPronto: ${runCatching { TextFormats.toPronto(a.signal) }.getOrDefault("-")}" },
                { "cannot encode: ${it.message}" },
            )
            Column(Modifier.padding(vertical = 4.dp).focusable()) {
                Text(k.label, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
                Text(text, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = MaterialTheme.colorScheme.onBackground)
            }
        }
        item { Section("Log (newest first)") }
        items(ui.log.reversed()) { line ->
            Text(
                line,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.focusable().padding(vertical = 1.dp),
            )
        }
        item { Section("About") }
        item { Body("Code database: ${vm.db.source}") }
    }
}

// ------------------------------------------------------------------ check remote

@Composable
fun CheckRemoteScreen(vm: AppViewModel, ui: UiState) {
    val c = ui.check ?: return
    Title(
        "Check remote",
        "The remote can't report its stored codes, so press each key: the app sees whether it sends IR or Bluetooth.",
    )
    val first = rememberInitialFocus()
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
        Column(Modifier.weight(1f)) {
            Section("Remote")
            if (c.loading) Body("Reading…")
            for ((k, v) in c.info) Body("$k: $v")
            Section("Saved for this remote")
            for (k in G20Key.entries) {
                val a = ui.config.keys[k]
                Body("${k.label}: ${a?.signal?.describe() ?: "not set (Bluetooth)"}")
            }
            Spacer(Modifier.height(12.dp))
            Button(onClick = { vm.back() }, modifier = Modifier.focusRequester(first)) { Text("Done") }
        }
        Column(Modifier.weight(1f)) {
            Section("Press each key on the remote")
            for (k in G20Key.entries) {
                val r = c.results[k]
                val expected = if (ui.config.keys.containsKey(k)) "IR" else "Bluetooth"
                val ok = r == null || (r.startsWith("Sends IR") == (expected == "IR"))
                Text(
                    "${k.label}: ${r ?: "not pressed yet"}",
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (ok) MaterialTheme.colorScheme.onBackground else Color(0xFFFF8A80),
                )
                if (!ok) Body("  Expected $expected from the saved settings – try \"Write saved codes\".", Color(0xFFFF8A80))
            }
            Spacer(Modifier.height(8.dp))
            Body("Power never reaches apps when it sends Bluetooth, so it may stay \"not pressed\".")
            Section("What arrived (newest first)")
            if (c.events.isEmpty()) Body("Nothing yet.")
            for (e in c.events) {
                Text(e, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = MaterialTheme.colorScheme.onBackground)
            }
        }
    }
}

// ------------------------------------------------------------------ export / import

@Composable
fun TransferScreen(vm: AppViewModel, ui: UiState) {
    Title("Export / import", "Settings for ${ui.remoteName ?: "this remote"}")
    val imp = ui.pendingImport
    if (imp != null) {
        val fr = rememberInitialFocus(imp)
        Section("Received settings")
        for (k in G20Key.entries) {
            val a = imp.config.keys[k]
            Body("${k.label}: ${a?.let { "${it.signal.describe()} (${it.source})" } ?: "not set (Bluetooth)"}")
        }
        if (imp.warnings.isNotEmpty()) Body("Skipped: ${imp.warnings.joinToString("; ")}", Color(0xFFFFB74D))
        Body("This replaces the saved settings for this remote.")
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { vm.applyImport(write = true) }, modifier = Modifier.focusRequester(fr)) { Text("Import and write to remote") }
            OutlinedButton(onClick = { vm.applyImport(write = false) }) { Text("Import only") }
            OutlinedButton(onClick = { vm.discardImport() }) { Text("Discard") }
        }
        return
    }
    val first = rememberInitialFocus()
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
        Column(Modifier.weight(1f)) {
            Section("From a phone or PC on the same network")
            if (ui.transferUrls.isEmpty()) Body("No network address found.")
            for (u in ui.transferUrls) {
                Text(u, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(8.dp))
            Body("Open this address in a browser to download the settings or upload a file. Uploaded settings show up here to confirm.")
            Body("The page is only available while this screen is open.")
            Spacer(Modifier.height(16.dp))
            Button(onClick = { vm.back() }, modifier = Modifier.focusRequester(first)) { Text("Done") }
        }
        Column(Modifier.weight(1f)) {
            Section("With adb")
            Body("The current settings are also saved as:")
            Text(ui.exportPath ?: "-", fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = MaterialTheme.colorScheme.onBackground)
            Spacer(Modifier.height(8.dp))
            Body("adb pull that file to export. To import, adb push a file to the same path, then:")
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { vm.importFromFile() }) { Text("Import from that file") }
        }
    }
}
