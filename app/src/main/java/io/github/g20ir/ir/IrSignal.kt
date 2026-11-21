package io.github.g20ir.ir

import org.json.JSONArray

/** IR protocols as named in Flipper .ir files (and the bundled database). */
enum class Protocol(val id: String, val carrierHz: Int, val addressBits: Int, val commandBits: Int) {
    NEC("NEC", 38000, 8, 8),
    NECext("NECext", 38000, 16, 16),
    NEC42("NEC42", 38000, 13, 8),
    NEC42ext("NEC42ext", 38000, 26, 16),
    Samsung32("Samsung32", 38000, 8, 8),
    RC5("RC5", 36000, 5, 6),
    RC5X("RC5X", 36000, 5, 7),
    RC6("RC6", 36000, 8, 8),
    SIRC("SIRC", 40000, 5, 7),
    SIRC15("SIRC15", 40000, 8, 7),
    SIRC20("SIRC20", 40000, 13, 7),
    Kaseikyo("Kaseikyo", 38000, 26, 10),
    RCA("RCA", 38000, 4, 8),
    Pioneer("Pioneer", 40000, 8, 8),

    /** Denon's own 15-bit protocol (Sharp-style): frame + inverted frame. Address 2 = amplifier. */
    Denon("Denon", 38000, 5, 8);

    val maxAddress: Long get() = (1L shl addressBits) - 1
    val maxCommand: Long get() = (1L shl commandBits) - 1

    companion object {
        fun byId(id: String): Protocol? = entries.firstOrNull { it.id.equals(id, ignoreCase = true) }
    }
}

/** Something that can be turned into a G20 IR payload. */
sealed interface IrSignal {
    /** Protocol + address + command, as in the Flipper IRDB "parsed" signals. */
    data class Parsed(val protocol: Protocol, val address: Long, val command: Long) : IrSignal

    /**
     * Raw timings in microseconds, alternating mark/space and starting with a mark.
     * [repeat] is sent repeatedly while the key is held (may be empty).
     */
    data class Raw(
        val carrierHz: Int,
        val dutyPercent: Int,
        val once: List<Int>,
        val repeat: List<Int> = emptyList(),
    ) : IrSignal

    /** A payload already in the remote's own format (hex), sent unchanged. */
    data class G20Hex(val hex: String) : IrSignal

    fun describe(): String = when (this) {
        is Parsed -> "${protocol.id} address $address (0x${address.toString(16).uppercase()}), " +
            "command $command (0x${command.toString(16).uppercase()})"
        is Raw -> "raw ${carrierHz / 1000.0} kHz, ${once.size + repeat.size} timings"
        is G20Hex -> "G20 payload, ${hex.length / 2} bytes"
    }

    fun toJson(): JSONArray = when (this) {
        is Parsed -> JSONArray().put(protocol.id).put(address).put(command)
        is Raw -> JSONArray().put("RAW").put(carrierHz).put(dutyPercent)
            .put(JSONArray(once)).also { if (repeat.isNotEmpty()) it.put(JSONArray(repeat)) }
        is G20Hex -> JSONArray().put("G20").put(hex)
    }

    companion object {
        /** Parses ["SIRC",1,21], ["RAW",freq,duty,[..],[..]?] or ["G20","hex"]. Returns null if unsupported. */
        fun fromJson(a: JSONArray): IrSignal? {
            val kind = a.optString(0)
            return when (kind) {
                "RAW" -> {
                    val once = a.getJSONArray(3).toIntList()
                    val rep = if (a.length() > 4) a.getJSONArray(4).toIntList() else emptyList()
                    Raw(a.getInt(1), a.getInt(2).coerceIn(10, 60), once, rep)
                }
                "G20" -> G20Hex(a.getString(1))
                else -> Protocol.byId(kind)?.let { Parsed(it, a.getLong(1), a.getLong(2)) }
            }
        }

        private fun JSONArray.toIntList(): List<Int> = List(length()) { getInt(it) }
    }
}
