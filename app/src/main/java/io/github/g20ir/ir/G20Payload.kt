package io.github.g20ir.ir

import kotlin.math.roundToInt

class EncodeException(msg: String) : Exception(msg)

/**
 * The IR code format of the Google G10/G20 reference remote (Telink firmware, app_ir.c).
 * Big-endian 16-bit words:
 *
 *   byte0 type, byte1 duty cycle %, word1 carrier in 100 Hz
 *   type 1 (once):           word2 = pairs                       (6-byte header)
 *   type 2 (repeat):         word2 = pairs, word3 = repeat delay  (8-byte header)
 *   type 3 (once + repeat):  word2 = once pairs, word3 = repeat pairs
 *   type 4 (toggle):         word2 = pairs A, word3 = pairs B; odd presses send A, even presses B
 *   then mark/space pairs in carrier cycles.
 *
 * While a key is held the firmware keeps re-sending: the whole sequence for type 2 and 4,
 * the repeat part for type 3.
 */
object G20Payload {
    /** The firmware negotiates an ATT MTU of 180, so one write carries at most 177 bytes. */
    const val DEFAULT_MAX_BYTES = 177

    /** Hard limits from the firmware: 300 bytes in RAM, ~256 bytes per key in flash. */
    const val ABSOLUTE_MAX_BYTES = 250

    /** The PWM DMA FIFO stores pulse counts in 14 bits. */
    private const val MAX_CYCLES = 0x3FFF

    data class Built(val bytes: ByteArray, val type: Int, val summary: String) {
        val hex: String get() = bytes.joinToString("") { "%02x".format(it) }
    }

    fun build(signal: IrSignal, maxBytes: Int = DEFAULT_MAX_BYTES): Built = when (signal) {
        is IrSignal.Parsed -> fromWaveform(Encoders.encode(signal), maxBytes)
        is IrSignal.Raw -> fromRaw(signal, maxBytes)
        is IrSignal.G20Hex -> fromHex(signal.hex, maxBytes)
    }

    fun fromWaveform(w: Waveform, maxBytes: Int): Built {
        val f100 = (w.carrierHz + 50) / 100
        val frame = cycles(w.frame, w.carrierHz)

        w.toggleFrame?.let { tf ->
            val b = cycles(tf, w.carrierHz)
            val size = 8 + 4 * (frame.size + b.size) / 2
            if (size <= maxBytes) {
                return Built(pack(4, w.dutyPercent, f100, frame.size / 2, b.size / 2, frame + b), 4, "toggle")
            }
        }
        w.repeatUnit?.let { ru ->
            val r = cycles(ru, w.carrierHz)
            val size = 8 + 4 * (frame.size + r.size) / 2
            if (size <= maxBytes) {
                return Built(pack(3, w.dutyPercent, f100, frame.size / 2, r.size / 2, frame + r), 3, "frame + repeat code")
            }
        }
        // Send at least minFrames frames on a tap, then keep repeating the frame while held.
        if (w.minFrames > 1) {
            for (n in w.minFrames - 1 downTo 1) {
                val once = List(n) { frame }.flatten()
                val size = 8 + 4 * (once.size + frame.size) / 2
                if (size <= maxBytes) {
                    return Built(pack(3, w.dutyPercent, f100, once.size / 2, frame.size / 2, once + frame), 3, "${n + 1} frames, repeats")
                }
            }
        }
        val size = 8 + 4 * frame.size / 2
        if (size <= maxBytes) {
            return Built(pack(2, w.dutyPercent, f100, frame.size / 2, frame.sum(), frame), 2, "repeating frame")
        }
        throw EncodeException("code needs $size bytes, the remote accepts $maxBytes per key")
    }

    private fun fromRaw(r: IrSignal.Raw, maxBytes: Int): Built {
        val f100 = (r.carrierHz + 50) / 100
        var once = evenize(r.once)
        val rep = if (r.repeat.isEmpty()) emptyList() else evenize(r.repeat)
        if (rep.isNotEmpty()) {
            val o = cycles(once, r.carrierHz)
            val p = cycles(rep, r.carrierHz)
            val size = 8 + 4 * (o.size + p.size) / 2
            if (size <= maxBytes) return Built(pack(3, r.dutyPercent, f100, o.size / 2, p.size / 2, o + p), 3, "raw once + repeat")
            once = rep // fall back to the repeat part only
        }
        var c = cycles(once, r.carrierHz)
        if (8 + 2 * c.size > maxBytes) {
            // Captures often contain the same frame several times: keep only the first one.
            val first = firstFrame(once)
            c = cycles(first, r.carrierHz)
        }
        val size = 8 + 2 * c.size
        if (size > maxBytes) throw EncodeException("raw code needs $size bytes, the remote accepts $maxBytes per key")
        return Built(pack(2, r.dutyPercent, f100, c.size / 2, c.sum(), c), 2, "raw repeating")
    }

    private fun fromHex(hex: String, maxBytes: Int): Built {
        val clean = hex.filter { !it.isWhitespace() }
        if (clean.length % 2 != 0 || clean.any { Character.digit(it, 16) < 0 }) throw EncodeException("not a hex string")
        val bytes = ByteArray(clean.length / 2) { clean.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
        val d = decode(bytes) ?: throw EncodeException("not a valid G20 payload")
        if (bytes.size > maxBytes) throw EncodeException("payload is ${bytes.size} bytes, the remote accepts $maxBytes per key")
        return Built(bytes, d.type, "imported")
    }

    data class Decoded(val type: Int, val duty: Int, val carrierHz: Int, val partA: List<Int>, val partB: List<Int>)

    /** Parses and validates a payload. Returns null when the header does not match the length. */
    fun decode(b: ByteArray): Decoded? {
        if (b.size < 6) return null
        fun w(i: Int) = ((b[2 * i].toInt() and 0xFF) shl 8) or (b[2 * i + 1].toInt() and 0xFF)
        val type = b[0].toInt() and 0xFF
        val duty = b[1].toInt() and 0xFF
        val carrier = w(1) * 100
        val n1 = w(2)
        val (hdr, n2) = when (type) {
            1 -> 6 to 0
            2 -> { if (b.size < 8) return null; 8 to 0 }
            3, 4 -> { if (b.size < 8) return null; 8 to w(3) }
            else -> return null
        }
        if (b.size != hdr + 4 * (n1 + n2)) return null
        val words = (hdr / 2 until b.size / 2).map { w(it) }
        return Decoded(type, duty, carrier, words.subList(0, 2 * n1), words.subList(2 * n1, words.size))
    }

    private fun pack(type: Int, duty: Int, f100: Int, n1: Int, word3: Int, data: List<Int>): ByteArray {
        val words = ArrayList<Int>(data.size + 4)
        words += (type shl 8) or (duty and 0xFF)
        words += f100
        words += n1
        words += word3.coerceAtMost(0xFFFF)
        words += data
        val out = ByteArray(words.size * 2)
        words.forEachIndexed { i, v ->
            out[2 * i] = (v shr 8).toByte()
            out[2 * i + 1] = v.toByte()
        }
        return out
    }

    private fun cycles(us: List<Int>, hz: Int): List<Int> =
        us.map { (it.toLong() * hz / 1_000_000.0).roundToInt().coerceIn(1, MAX_CYCLES) }

    private fun evenize(t: List<Int>): List<Int> =
        if (t.size % 2 == 1) t + 40_000 else t

    private fun firstFrame(t: List<Int>): List<Int> {
        for (i in 1 until t.size step 2) {
            if (t[i] > 15_000 && i < t.size - 1) return t.subList(0, i + 1)
        }
        return t
    }
}
