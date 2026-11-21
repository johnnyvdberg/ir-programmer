package io.github.g20ir.net

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * A tiny HTTP server so settings can be exported/imported from a phone or PC browser on the
 * same network; typing JSON with a TV remote is not an option and Android TV usually has no
 * file picker. Only runs while the Export/import screen is open.
 */
class WebTransfer(
    /** Current export JSON. */
    private val export: () -> String,
    /** Called with uploaded text; returns a message for the browser. */
    private val onImport: (String) -> String,
    private val log: (String) -> Unit,
) {
    private var server: ServerSocket? = null

    val port: Int get() = server?.localPort ?: 0

    fun start(): Boolean {
        if (server != null) return true
        val s = try {
            ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(8080)) }
        } catch (_: Exception) {
            try { ServerSocket(0) } catch (e: Exception) { log("web server failed: ${e.message}"); return false }
        }
        server = s
        log("web server on port ${s.localPort}")
        thread(name = "g20-web", isDaemon = true) {
            while (!s.isClosed) {
                val c = try { s.accept() } catch (_: Exception) { break }
                thread(isDaemon = true) { handle(c) }
            }
        }
        return true
    }

    fun stop() {
        server?.close()
        server = null
    }

    /** Addresses a browser on the LAN can use. */
    fun urls(): List<String> = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .map { "http://${it.hostAddress}:$port/" }
    } catch (_: Exception) { emptyList() }

    private fun handle(socket: Socket) = socket.use { s ->
        s.soTimeout = 10_000
        val input = BufferedInputStream(s.getInputStream())
        val requestLine = readLine(input) ?: return@use
        val headers = mutableMapOf<String, String>()
        while (true) {
            val l = readLine(input) ?: break
            if (l.isEmpty()) break
            val i = l.indexOf(':')
            if (i > 0) headers[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
        }
        val parts = requestLine.split(" ")
        val method = parts.getOrElse(0) { "" }
        val path = parts.getOrElse(1) { "/" }.substringBefore('?')
        val out = s.getOutputStream()
        fun respond(code: String, type: String, body: String, extra: String = "") {
            val b = body.toByteArray(Charsets.UTF_8)
            out.write(
                ("HTTP/1.1 $code\r\nContent-Type: $type; charset=utf-8\r\nContent-Length: ${b.size}\r\n" +
                    "Cache-Control: no-store\r\nConnection: close\r\n$extra\r\n").toByteArray(),
            )
            out.write(b)
            out.flush()
        }
        when {
            method == "GET" && path == "/" -> respond("200 OK", "text/html", page(export()))
            method == "GET" && path == "/config.json" -> respond(
                "200 OK", "application/json", export(),
                "Content-Disposition: attachment; filename=\"g20-ir-config.json\"\r\n",
            )
            method == "POST" && path == "/import" -> {
                val len = headers["content-length"]?.toIntOrNull() ?: 0
                if (len <= 0 || len > 512 * 1024) { respond("413 Payload Too Large", "text/plain", "file too large"); return@use }
                val body = readBytes(input, len).toString(Charsets.UTF_8)
                log("upload from ${s.inetAddress.hostAddress}, $len bytes")
                respond("200 OK", "text/plain", onImport(body))
            }
            else -> respond("404 Not Found", "text/plain", "not found")
        }
    }

    private fun readLine(i: InputStream): String? {
        val b = ByteArrayOutputStream()
        while (true) {
            val c = i.read()
            if (c < 0) return if (b.size() == 0) null else b.toString(Charsets.UTF_8.name())
            if (c == '\n'.code) return b.toString(Charsets.UTF_8.name()).trimEnd('\r')
            if (b.size() > 8192) return null
            b.write(c)
        }
    }

    private fun readBytes(i: InputStream, n: Int): ByteArray {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = i.read(buf, off, n - off)
            if (r < 0) break
            off += r
        }
        return buf.copyOf(off)
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun page(json: String) = """<!doctype html>
<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>G20 IR Setup</title>
<style>
 :root{color-scheme:light dark;--bg:#fff;--fg:#1b1f24;--mut:#5f6670;--acc:#1a73e8;--box:#f1f3f4}
 @media (prefers-color-scheme:dark){:root{--bg:#111418;--fg:#e6e8eb;--mut:#a0a7b0;--acc:#8ab4f8;--box:#1b1f24}}
 body{background:var(--bg);color:var(--fg);font:16px/1.5 system-ui,sans-serif;max-width:760px;margin:0 auto;padding:16px}
 h1{font-size:1.4em;margin:.2em 0} h2{font-size:1.1em;margin-top:1.6em} p{color:var(--mut)}
 textarea{width:100%;box-sizing:border-box;min-height:14em;font:13px ui-monospace,monospace;background:var(--box);color:var(--fg);border:1px solid #8884;border-radius:8px;padding:8px}
 a.b,button{display:inline-block;background:var(--acc);color:var(--bg);border:0;border-radius:8px;padding:10px 16px;font:inherit;text-decoration:none;cursor:pointer;margin:6px 8px 6px 0}
 #msg{font-weight:600;color:var(--acc)}
</style></head><body>
<h1>G20 IR Setup</h1>
<p>Settings of the remote currently connected to the TV.</p>
<h2>Export</h2>
<a class="b" href="/config.json">Download g20-ir-config.json</a>
<textarea readonly>${esc(json)}</textarea>
<h2>Import</h2>
<p>Choose a file or paste its contents. Nothing changes until you confirm on the TV.</p>
<input type="file" id="f" accept=".json,application/json,text/plain">
<textarea id="t" placeholder='{"keys": {"power": {"pronto": "0000 006D ..."}}}'></textarea>
<button id="go">Send to TV</button> <span id="msg"></span>
<script>
document.getElementById('f').onchange=e=>{const r=new FileReader();r.onload=()=>document.getElementById('t').value=r.result;r.readAsText(e.target.files[0]);};
document.getElementById('go').onclick=async()=>{const m=document.getElementById('msg');m.textContent='Sending…';
 try{const r=await fetch('/import',{method:'POST',headers:{'Content-Type':'text/plain'},body:document.getElementById('t').value});m.textContent=await r.text();}
 catch(e){m.textContent='Failed: '+e;}};
</script></body></html>"""
}
