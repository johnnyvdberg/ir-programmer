package io.github.g20ir.model

import io.github.g20ir.ir.IrSignal
import org.json.JSONArray
import org.json.JSONObject

/** The five keys of the G20 that can send IR, with the key IDs the firmware expects. */
enum class G20Key(val id: String, val label: String, val keyId: Int) {
    POWER("power", "Power", 0x001A),
    INPUT("input", "Input", 0x00B2),
    VOL_UP("volup", "Volume +", 0x0018),
    VOL_DOWN("voldn", "Volume −", 0x0019),
    MUTE("mute", "Mute", 0x00A4);

    companion object {
        fun byId(id: String) = entries.firstOrNull { it.id == id }
        /** Remotes whose input key is set up as the "magic"/star key report this ID instead. */
        const val MAGIC_KEY_ID = 0x0139
    }
}

/** What a key is set to send, and where it came from (for display). */
data class Assignment(val signal: IrSignal, val source: String)

data class RemoteConfig(val keys: Map<G20Key, Assignment> = emptyMap()) {
    fun with(key: G20Key, a: Assignment?) =
        RemoteConfig(if (a == null) keys - key else keys + (key to a))

    fun toJson(): JSONObject = JSONObject().also { o ->
        keys.forEach { (k, a) ->
            o.put(k.id, JSONObject().put("signal", a.signal.toJson()).put("source", a.source))
        }
    }

    companion object {
        fun fromJson(o: JSONObject): RemoteConfig {
            val m = mutableMapOf<G20Key, Assignment>()
            for (k in G20Key.entries) {
                val e = o.optJSONObject(k.id) ?: continue
                val sig = IrSignal.fromJson(e.getJSONArray("signal")) ?: continue
                m[k] = Assignment(sig, e.optString("source", ""))
            }
            return RemoteConfig(m)
        }
    }
}

/** One code set from the database: the signals one family of devices uses for our keys. */
data class CodeSet(
    val brand: String,
    val category: String,
    val models: List<String>,
    /** Per key: preferred signal first, then alternatives found in the same model files. */
    val keys: Map<G20Key, List<IrSignal>>,
    /** Optional names for signals, e.g. which input an Input code selects. */
    val names: Map<IrSignal, String> = emptyMap(),
) {
    fun label(index: Int, total: Int): String {
        val m = models.take(3).joinToString(", ") + if (models.size > 3) " +${models.size - 3}" else ""
        return "$brand code ${index + 1}/$total ($m)"
    }

    companion object {
        fun fromJson(brand: String, category: String, o: JSONObject): CodeSet {
            val models = o.getJSONArray("m").let { a -> List(a.length()) { a.getString(it) } }
            val k = o.getJSONObject("k")
            val keys = mutableMapOf<G20Key, List<IrSignal>>()
            for (key in G20Key.entries) {
                val arr: JSONArray = k.optJSONArray(key.id) ?: continue
                val sigs = List(arr.length()) { IrSignal.fromJson(arr.getJSONArray(it)) }.filterNotNull()
                if (sigs.isNotEmpty()) keys[key] = sigs
            }
            return CodeSet(brand, category, models, keys)
        }
    }
}
