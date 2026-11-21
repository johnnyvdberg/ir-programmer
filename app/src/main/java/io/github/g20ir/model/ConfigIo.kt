package io.github.g20ir.model

import io.github.g20ir.ir.EncodeException
import io.github.g20ir.ir.G20Payload
import io.github.g20ir.ir.IrSignal
import io.github.g20ir.ir.TextFormats
import org.json.JSONException
import org.json.JSONObject

/**
 * Export/import format:
 *
 * {
 *   "format": "g20-ir-setup", "version": 1,
 *   "inputKeyId": "0x00B2",
 *   "keys": {
 *     "power": { "signal": ["SIRC", 1, 21], "source": "...",
 *                "description": "...", "g20": "0321...", "pronto": "0000 ..." },
 *     ...
 *   }
 * }
 *
 * On import each key needs one of "signal", "g20" or "pronto" (tried in that order), so a
 * hand-written file can contain just Pronto codes. "description" is informational only.
 */
object ConfigIo {
    const val FORMAT = "g20-ir-setup"
    const val FILE_NAME = "g20-ir-config.json"

    data class Imported(val config: RemoteConfig, val inputKeyId: Int?, val warnings: List<String>)

    fun export(config: RemoteConfig, inputKeyId: Int): String {
        val keys = JSONObject()
        for (k in G20Key.entries) {
            val a = config.keys[k] ?: continue
            val o = JSONObject()
                .put("signal", a.signal.toJson())
                .put("source", a.source)
                .put("description", a.signal.describe())
            runCatching { G20Payload.build(a.signal, G20Payload.ABSOLUTE_MAX_BYTES) }.getOrNull()?.let { o.put("g20", it.hex) }
            runCatching { TextFormats.toPronto(a.signal) }.getOrNull()?.let { o.put("pronto", it) }
            keys.put(k.id, o)
        }
        return JSONObject()
            .put("format", FORMAT)
            .put("version", 1)
            .put("inputKeyId", if (inputKeyId == 0) "auto" else "0x%04X".format(inputKeyId))
            .put("keys", keys)
            .toString(2)
    }

    /** Throws [IllegalArgumentException] with a readable message when nothing usable is found. */
    fun import(text: String): Imported {
        val root = try { JSONObject(text.trim()) } catch (e: JSONException) {
            throw IllegalArgumentException("not valid JSON: ${e.message}")
        }
        val fmt = root.optString("format", FORMAT)
        if (fmt != FORMAT) throw IllegalArgumentException("unknown format \"$fmt\"")
        val keysObj = root.optJSONObject("keys") ?: root // also accept a bare {"power": {...}} map
        val warnings = mutableListOf<String>()
        val map = mutableMapOf<G20Key, Assignment>()
        for (name in keysObj.keys()) {
            val k = G20Key.byId(name)
            val e = keysObj.optJSONObject(name)
            if (k == null || e == null) {
                if (name !in setOf("format", "version", "inputKeyId", "keys")) warnings += "ignored \"$name\""
                continue
            }
            val signal = parseKey(e)
            if (signal == null) { warnings += "${k.label}: no usable code"; continue }
            try {
                G20Payload.build(signal, G20Payload.ABSOLUTE_MAX_BYTES)
            } catch (ex: EncodeException) {
                warnings += "${k.label}: ${ex.message}"
                continue
            }
            map[k] = Assignment(signal, e.optString("source").ifBlank { "Imported" })
        }
        val inputId = root.optString("inputKeyId").takeIf { it.isNotBlank() }?.let {
            if (it.equals("auto", ignoreCase = true)) 0 else it.removePrefix("0x").removePrefix("0X").toIntOrNull(16)
        }?.takeIf { it == 0 || it == G20Key.INPUT.keyId || it == G20Key.MAGIC_KEY_ID }
        if (map.isEmpty()) throw IllegalArgumentException(
            "no usable keys" + if (warnings.isEmpty()) "" else " (${warnings.joinToString("; ")})",
        )
        return Imported(RemoteConfig(map), inputId, warnings)
    }

    private fun parseKey(e: JSONObject): IrSignal? {
        e.optJSONArray("signal")?.let { arr -> runCatching { IrSignal.fromJson(arr) }.getOrNull()?.let { return it } }
        for (field in listOf("g20", "pronto")) {
            val t = e.optString(field)
            if (t.isNotBlank()) runCatching { TextFormats.parse(t) }.getOrNull()?.let { return it }
        }
        return null
    }
}
