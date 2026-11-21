package io.github.g20ir

import io.github.g20ir.net.WebTransfer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

class WebTransferTest {
    @Test
    fun servesPageExportAndAcceptsUpload() {
        var received: String? = null
        val w = WebTransfer({ """{"format":"g20-ir-setup","keys":{}}""" }, { received = it; "ok <3" }, {})
        assertTrue(w.start())
        try {
            val base = "http://127.0.0.1:${w.port}"
            fun get(path: String) = (URL(base + path).openConnection() as HttpURLConnection).run {
                responseCode to inputStream.bufferedReader().readText()
            }
            val (c1, page) = get("/")
            assertEquals(200, c1)
            assertTrue(page.contains("G20 IR Setup") && page.contains("&quot;").not() && page.contains("g20-ir-setup"))
            val (c2, json) = get("/config.json")
            assertEquals(200, c2)
            assertTrue(json.startsWith("{"))

            val body = """{"keys":{"power":{"pronto":"0000 006D 0000 0001 0157 00AB"}}} ü"""
            val post = (URL("$base/import").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "text/plain")
                outputStream.use { it.write(body.toByteArray()) }
            }
            assertEquals(200, post.responseCode)
            assertEquals("ok <3", post.inputStream.bufferedReader().readText())
            assertEquals(body, received)
            assertEquals(404, (URL("$base/nope").openConnection() as HttpURLConnection).responseCode)
        } finally {
            w.stop()
        }
    }
}
