package io.github.g20ir.buttons

import dadb.AdbKeyPair
import dadb.AdbShellPacket
import dadb.AdbShellStream
import dadb.Dadb
import java.io.File
import kotlin.concurrent.thread

/**
 * Tells the four app buttons apart, which Android itself can't (they all reach apps as
 * KEY_UNKNOWN). It connects to the Shield's own adb daemon over the network
 * ("Network debugging" must be on), runs `getevent -l` with adb-shell rights and reads the
 * HID usage of each press. The first connection makes the TV ask "Allow network debugging?";
 * with "Always allow" it reconnects silently after every reboot.
 *
 * getevent never flushes stdout, so it runs on a pseudo-terminal (shell v2 + pty), which
 * makes its output line-buffered.
 */
class AdbKeyHelper(
    private val filesDir: File,
    private val onPress: (RemoteButton) -> Unit,
    private val log: (String) -> Unit,
) {
    @Volatile private var running = false
    @Volatile private var current: Dadb? = null
    private var worker: Thread? = null

    fun start() {
        if (running) return
        running = true
        worker = thread(name = "g20-adb-helper", isDaemon = true) { loop() }
    }

    fun stop() {
        running = false
        try { current?.close() } catch (_: Exception) {}
        worker?.interrupt()
        ButtonStore.helperStatus.value = ButtonStore.HelperStatus.Off
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
                    current = d
                    val usePty = d.supportsFeature("shell_v2")
                    val parser = GeteventParser()
                    log("adb helper connected (pty: $usePty)")
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
            } catch (e: Exception) {
                if (!running) break
                val msg = when {
                    e is java.net.ConnectException -> "Can't reach adb on this Shield. Is Network debugging on?"
                    else -> e.message ?: e.javaClass.simpleName
                }
                log("adb helper: $msg")
                ButtonStore.helperStatus.value = ButtonStore.HelperStatus.Failed(msg)
            } finally {
                current = null
            }
            if (!running) break
            try { Thread.sleep(backoff) } catch (_: InterruptedException) { break }
            backoff = (backoff * 2).coerceAtMost(60_000L)
        }
    }
}
