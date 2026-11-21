package io.github.g20ir.ir

import kotlin.math.abs
import kotlin.math.roundToInt

/** Parsers for codes typed or pasted by the user. */
object TextFormats {
    private const val PRONTO_UNIT_US = 0.241246

    /**
     * Accepts:
     *  - Pronto hex ("0000 006D 0022 0002 0157 00AC ...")
     *  - a G20 payload in hex ("0321017c0022...")
     *  - Flipper raw timings in µs, optionally prefixed with a carrier: "38000: 9000 4500 560 ..."
     */
    fun parse(text: String): IrSignal {
        val t = text.trim()
        if (t.isEmpty()) throw EncodeException("empty")
        val words = t.split(Regex("[\\s,]+")).filter { it.isNotEmpty() }
        if (words.size >= 6 && words.all { it.length == 4 && it.all(::isHex) } && words[0] == "0000") {
            return pronto(words)
        }
        val compact = t.filter { !it.isWhitespace() }
        if (compact.length >= 12 && compact.all(::isHex) && compact.length % 2 == 0) {
            val b = ByteArray(compact.length / 2) { compact.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
            if (G20Payload.decode(b) != null) return IrSignal.G20Hex(compact.lowercase())
        }
        val m = Regex("^(\\d{4,6})\\s*:\\s*(.*)$", RegexOption.DOT_MATCHES_ALL).find(t)
        val carrier = m?.groupValues?.get(1)?.toInt() ?: 38000
        val body = m?.groupValues?.get(2) ?: t
        val nums = body.split(Regex("[\\s,]+")).filter { it.isNotEmpty() }.map {
            it.removePrefix("+").removePrefix("-").toIntOrNull() ?: throw EncodeException("not a number: $it")
        }
        if (nums.size < 4) throw EncodeException("expected Pronto hex, a G20 hex payload or raw µs timings")
        return IrSignal.Raw(carrier, 33, nums)
    }

    private fun isHex(c: Char) = Character.digit(c, 16) >= 0

    private fun pronto(words: List<String>): IrSignal.Raw {
        val w = words.map { it.toInt(16) }
        if (w[1] == 0) throw EncodeException("Pronto carrier is zero")
        val periodUs = w[1] * PRONTO_UNIT_US
        val onceN = w[2]
        val repN = w[3]
        val durs = w.drop(4)
        if (durs.size < 2 * (onceN + repN)) throw EncodeException("Pronto pair count does not match the data")
        val hz = (1_000_000 / periodUs).roundToInt()
        // Snap to whole kHz like Google's database does (e.g. 38029 Hz -> 38000 Hz) and keep the
        // cycle counts unchanged, so a Pronto code round-trips exactly.
        val snapped = ((hz + 500) / 1000) * 1000
        val cycleUs = if (abs(snapped - hz) <= hz / 100) 1_000_000.0 / snapped else periodUs
        val us = durs.take(2 * (onceN + repN)).map { (it * cycleUs).roundToInt() }
        return IrSignal.Raw(snapped, 33, us.take(2 * onceN), us.drop(2 * onceN))
    }

    /** Renders any signal as Pronto hex, for exporting. */
    fun toPronto(signal: IrSignal, maxBytes: Int = G20Payload.ABSOLUTE_MAX_BYTES): String {
        val built = G20Payload.build(signal, maxBytes)
        val d = G20Payload.decode(built.bytes)!!
        val freqWord = (1_000_000.0 / (d.carrierHz * PRONTO_UNIT_US)).roundToInt()
        val scale = 1.0 // G20 and Pronto both count carrier cycles
        val (once, rep) = when (d.type) {
            1 -> d.partA to emptyList()
            2 -> emptyList<Int>() to d.partA
            3 -> d.partA to d.partB
            else -> d.partA to emptyList()
        }
        val all = listOf(0, freqWord, once.size / 2, rep.size / 2) + (once + rep).map { (it * scale).roundToInt() }
        return all.joinToString(" ") { "%04X".format(it) }
    }
}
