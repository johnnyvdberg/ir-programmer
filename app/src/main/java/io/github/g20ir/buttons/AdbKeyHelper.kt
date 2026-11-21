package io.github.g20ir.buttons

import dadb.AdbKeyPair
import dadb.AdbShellPacket
import dadb.AdbShellStream
import dadb.AdbStream
import dadb.Dadb
import java.io.File
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * adb-shell rights for the button service, without root. It connects to the Shield's own adb
 * daemon over the network ("Network debugging" must be on). The first connection makes the TV
 * ask "Allow network debugging?"; with "Always allow" it reconnects silently after a reboot.
 *
 * - Reads `getevent -l` to tell the four app buttons apart: Android hands them all to apps as
 *   KEY_UNKNOWN, only the raw HID usage identifies them. getevent never flushes stdout, so it
 *   runs on a pseudo-terminal (shell v2 + pty), which makes its output line-buffered.
 * - Sends Android key codes (`input keyevent`) through a second, persistent shell, e.g.
 *   KEYCODE_PROG_RED for the red button, which the Shield's key layout doesn't produce.
 */
class AdbKeyHelper(
    private val filesDir: File,
    /** Package to grant WRITE_SECURE_SETTINGS to (for [ServiceRepair]), or null. */
    private val grantTo: String?,
    private val onPress: (KeyPress) -> Unit,
    private val log: (String) -> Unit,
) {
    @Volatile private var running = false
    @Volatile private var current: Dadb? = null
    @Volatile private var inputShell: AdbStream? = null
    private var worker: Thread? = null
    private val sender = Executors.newSingleThreadExecutor { r -> Thread(r, "g20-adb-keys").apply { isDaemon = true } }

    val connected: Boolean get() = current != null

    fun start() {
        if (running) return
        running = true
        worker = thread(name = "g20-adb-helper", isDaemon = true) { loop() }
    }

    fun stop() {
        running = false
        try { inputShell?.close() } catch (_: Exception) {}
        try { current?.close() } catch (_: Exception) {}
        worker?.interrupt()
        ButtonStore.helperStatus.value = ButtonStore.HelperStatus.Off
    }

    /** Injects a key press (down + up) as adb shell would. Runs off the calling thread. */
    fun sendKey(code: Int) {
        sender.execute {
            val d = current ?: return@execute
            try {
                val sh = inputShell ?: d.open("shell:sh").also { inputShell = it }
                sh.sink.writeUtf8("input keyevent $code\n").flush()
            } catch (e: Exception) {
                log("send key $code failed: ${e.message}")
                try { inputShell?.close() } catch (_: Exception) {}
                inputShell = null
                try { d.shell("input keyevent $code") } catch (_: Exception) {}
            }
        }
    }

    private fun keyPair(): AdbKeyPair {
        val priv = File(filesDir, "adbkey")
        val pub = File(filesDir, "adbkey.pub")
        if (!priv.exists() || !pub.exists()) AdbKeyPair.generate(priv, pub)
        return AdbKeyPair.read(priv, pub)
    }

    private fun loop() {
        var backoff = 3_000L
        while (running) {
            ButtonStore.helperStatus.value = ButtonStore.HelperStatus.Connecting
            try {
                Dadb.create("127.0.0.1", 5555, keyPair(), 10_000, 0).use { d ->
                    val usePty = d.supportsFeature("shell_v2") // also completes the connection/auth
                    current = d
                    val parser = GeteventParser()
                    log("adb helper connected (pty: $usePty)")
                    grantTo?.let { pkg ->
                        try {
                            d.shell("pm grant $pkg android.permission.WRITE_SECURE_SETTINGS")
                            log("granted WRITE_SECURE_SETTINGS for the boot repair")
                        } catch (e: Exception) {
                            log("could not grant WRITE_SECURE_SETTINGS: ${e.message}")
                        }
                    }
                    ButtonStore.helperStatus.value = ButtonStore.HelperStatus.Running
                    backoff = 3_000L
                    if (usePty) {
                        AdbShellStream(d.open("shell,v2,pty:getevent -l")).use { sh ->
                            val buf = StringBuilder()
                            while (running) {
                                when (val p = sh.read()) {
                                    is AdbShellPacket.StdOut -> {
                                        buf.append(String(p.payload, Charsets.UTF_8))
                                        while (true) {
                                            val nl = buf.indexOf("\n")
                                            if (nl < 0) break
                                            val line = buf.substring(0, nl).trimEnd('\r')
                                            buf.delete(0, nl + 1)
                                            parser.feed(line)?.let(onPress)
                                        }
                                    }
                                    is AdbShellPacket.Exit -> break
                                    else -> {}
                                }
                            }
                        }
                    } else {
                        // Without a pty getevent's output arrives in 4 KB bursts; still better than nothing.
                        d.open("shell:getevent -l").use { s ->
                            while (running) {
                                val line = s.source.readUtf8Line() ?: break
                                parser.feed(line)?.let(onPress)
                            }
                        }
                    }
                }
            } catch (e: Throwable) {
                if (!running) break
                val msg = when (e) {
                    is java.net.ConnectException -> "Can't reach adb on this Shield. Is Network debugging on?"
                    else -> e.message ?: e.javaClass.simpleName
                }
                log("adb helper: $msg")
                ButtonStore.helperStatus.value = ButtonStore.HelperStatus.Failed(msg)
            } finally {
                current = null
                try { inputShell?.close() } catch (_: Exception) {}
                inputShell = null
            }
            if (!running) break
            try { Thread.sleep(backoff) } catch (_: InterruptedException) { break }
            backoff = (backoff * 2).coerceAtMost(60_000L)
        }
    }
}
