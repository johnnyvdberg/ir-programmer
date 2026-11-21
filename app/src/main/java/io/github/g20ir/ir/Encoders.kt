package io.github.g20ir.ir

/**
 * A protocol-level description of what to transmit, in microseconds.
 * Every timing list alternates mark/space, starts with a mark and has even length
 * (the last entry is the gap that follows the frame).
 */
data class Waveform(
    val carrierHz: Int,
    val dutyPercent: Int = 33,
    /** One complete frame including its trailing gap. */
    val frame: List<Int>,
    /** Sent repeatedly while the key is held, if the protocol has a dedicated repeat code (NEC, Samsung). */
    val repeatUnit: List<Int>? = null,
    /** How many frames a receiver expects on a short press (Sony SIRC needs 3). */
    val minFrames: Int = 1,
    /** Second frame with the toggle bit flipped, for RC5/RC6. Sent on alternate presses. */
    val toggleFrame: List<Int>? = null,
)

/**
 * Protocol encoders. Timings, bit orders and address/command packing are ported from the
 * Flipper Zero firmware (lib/infrared/encoder_decoder), because the bundled code database
 * (Flipper-IRDB) stores its signals in exactly that representation.
 */
object Encoders {

    private class Pdwm(
        val preMark: Int, val preSpace: Int,
        val bit1Mark: Int, val bit1Space: Int,
        val bit0Mark: Int, val bit0Space: Int,
    )

    private val NEC_T = Pdwm(9000, 4500, 560, 1690, 560, 560)
    private val SAMSUNG_T = Pdwm(4500, 4500, 550, 1650, 550, 550)
    private val SIRC_T = Pdwm(2400, 600, 1200, 600, 600, 600)
    private val KASEIKYO_T = Pdwm(3456, 1728, 432, 1296, 432, 432)
    private val RCA_T = Pdwm(4000, 4000, 500, 2000, 500, 1000)
    private val PIONEER_T = Pdwm(8500, 4225, 500, 1500, 500, 500)

    private const val NEC_PERIOD = 110_000
    private const val SIRC_PERIOD = 45_000
    private const val RC5_HALF = 888
    private const val RC6_HALF = 444
    private const val MANCHESTER_SILENCE = 27_000

    fun encode(s: IrSignal.Parsed): Waveform {
        val a = s.address
        val c = s.command
        return when (s.protocol) {
            Protocol.NEC -> nec(
                (a and 0xFF) or ((a.inv() and 0xFF) shl 8) or ((c and 0xFF) shl 16) or ((c.inv() and 0xFF) shl 24), 32,
            )
            Protocol.NECext -> nec((a and 0xFFFF) or ((c and 0xFFFF) shl 16), 32)
            Protocol.NEC42 -> {
                val d1 = (a and 0x1FFF) or ((a.inv() and 0x1FFF) shl 13) or ((c and 0x3F) shl 26)
                val d2 = ((c and 0xC0) shr 6) or ((c.inv() and 0xFF) shl 2)
                nec((d1 and 0xFFFFFFFFL) or (d2 shl 32), 42)
            }
            Protocol.NEC42ext -> {
                val d1 = (a and 0x3FFFFFF) or ((c and 0x3F) shl 26)
                val d2 = (c and 0xFFC0) shr 6
                nec((d1 and 0xFFFFFFFFL) or (d2 shl 32), 42)
            }
            Protocol.Samsung32 -> {
                val bits = (a and 0xFF) or ((a and 0xFF) shl 8) or ((c and 0xFF) shl 16) or ((c.inv() and 0xFF) shl 24)
                val f = pdwm(SAMSUNG_T, bits, 32)
                Waveform(
                    carrierHz = 38000,
                    frame = f + 46_000,
                    repeatUnit = listOf(4500, 4500, 550, 1650, 550, 97_000),
                )
            }
            Protocol.SIRC -> sirc((c and 0x7F) or ((a and 0x1F) shl 7), 12)
            Protocol.SIRC15 -> sirc((c and 0x7F) or ((a and 0xFF) shl 7), 15)
            Protocol.SIRC20 -> sirc((c and 0x7F) or ((a and 0x1FFF) shl 7), 20)
            Protocol.Kaseikyo -> {
                val id = ((a shr 24) and 3).toInt()
                val vendor = ((a shr 8) and 0xFFFF).toInt()
                val genre1 = ((a shr 4) and 0xF).toInt()
                val genre2 = (a and 0xF).toInt()
                val cmd = (c and 0x3FF).toInt()
                val d = IntArray(6)
                d[0] = vendor and 0xFF
                d[1] = vendor shr 8
                var parity = d[0] xor d[1]
                parity = (parity and 0xF) xor (parity shr 4)
                d[2] = (parity and 0xF) or (genre1 shl 4)
                d[3] = (genre2 and 0xF) or ((cmd and 0xF) shl 4)
                d[4] = (id shl 6) or (cmd shr 4)
                d[5] = d[2] xor d[3] xor d[4]
                var bits = 0L
                for (i in 5 downTo 0) bits = (bits shl 8) or (d[i].toLong() and 0xFF)
                Waveform(38000, frame = pdwm(KASEIKYO_T, bits, 48) + 130_000)
            }
            Protocol.RCA -> {
                val bits = (a and 0xF) or ((c and 0xFF) shl 4) or ((a.inv() and 0xF) shl 12) or ((c.inv() and 0xFF) shl 16)
                Waveform(38000, frame = pdwm(RCA_T, bits, 24) + 8_000)
            }
            Protocol.Pioneer -> {
                // 32 data bits plus a 33rd zero bit; Flipper ends the frame on that bit's space,
                // followed by 26 ms of silence.
                val bits = (a and 0xFF) or ((a.inv() and 0xFF) shl 8) or ((c and 0xFF) shl 16) or ((c.inv() and 0xFF) shl 24)
                val f = pdwm(PIONEER_T, bits, 32) // ends with the stop (33rd bit) mark
                Waveform(40000, frame = f + (500 + 26_000), minFrames = 2)
            }
            Protocol.RC5, Protocol.RC5X -> {
                fun frame(toggle: Int): List<Int> {
                    val bits = ArrayList<Int>(14)
                    bits += 1
                    bits += if (s.protocol == Protocol.RC5) 1 else 0
                    bits += toggle
                    for (i in 4 downTo 0) bits += ((a shr i) and 1).toInt()
                    for (i in 5 downTo 0) bits += ((c shr i) and 1).toInt()
                    // RC5: logical 1 = space then mark, 0 = mark then space.
                    val halves = ArrayList<Pair<Boolean, Int>>()
                    for (b in bits) {
                        halves += (b == 0) to RC5_HALF
                        halves += (b == 1) to RC5_HALF
                    }
                    return finishManchester(halves)
                }
                Waveform(36000, frame = frame(0), toggleFrame = frame(1))
            }
            Protocol.RC6 -> {
                fun frame(toggle: Int): List<Int> {
                    val bits = ArrayList<Int>(21)
                    bits += 1
                    bits += listOf(0, 0, 0) // mode 0
                    bits += toggle
                    for (i in 7 downTo 0) bits += ((a shr i) and 1).toInt()
                    for (i in 7 downTo 0) bits += ((c shr i) and 1).toInt()
                    val halves = ArrayList<Pair<Boolean, Int>>()
                    halves += true to 2666
                    halves += false to 889
                    bits.forEachIndexed { idx, b ->
                        val t = if (idx == 4) RC6_HALF * 2 else RC6_HALF // toggle bit is double length
                        // RC6: logical 1 = mark then space, 0 = space then mark.
                        halves += (b == 1) to t
                        halves += (b == 0) to t
                    }
                    return finishManchester(halves)
                }
                Waveform(36000, frame = frame(0), toggleFrame = frame(1))
            }
        }
    }

    private fun nec(bits: Long, n: Int): Waveform {
        val f = pdwm(NEC_T, bits, n)
        val gap = (NEC_PERIOD - f.sum()).coerceAtLeast(10_000)
        return Waveform(
            carrierHz = 38000,
            frame = f + gap,
            repeatUnit = listOf(9000, 2250, 560, NEC_PERIOD - 9000 - 2250 - 560),
        )
    }

    private fun sirc(bits: Long, n: Int): Waveform {
        val f = pdwm(SIRC_T, bits, n)
        return Waveform(40000, frame = f + (SIRC_PERIOD - f.sum()), minFrames = 3)
    }

    /**
     * Pulse-distance / pulse-width modulation, LSB first. Returns timings ending with a mark
     * (no trailing gap): PWM protocols end on the last data mark, PDM protocols on a stop mark.
     */
    private fun pdwm(t: Pdwm, bits: Long, n: Int): List<Int> {
        val out = ArrayList<Int>(2 * n + 4)
        out += t.preMark
        out += t.preSpace
        for (i in 0 until n) {
            val one = (bits shr i) and 1L == 1L
            out += if (one) t.bit1Mark else t.bit0Mark
            out += if (one) t.bit1Space else t.bit0Space
        }
        val pwm = t.bit1Space == t.bit0Space
        if (pwm) out.removeAt(out.lastIndex) else out += t.bit0Mark
        return out
    }

    /** Merge equal-level halves, drop a leading space and the final space, then add the silence. */
    private fun finishManchester(halves: List<Pair<Boolean, Int>>): List<Int> {
        val merged = ArrayList<Pair<Boolean, Int>>()
        for (h in halves) {
            if (merged.isNotEmpty() && merged.last().first == h.first) {
                merged[merged.lastIndex] = h.first to (merged.last().second + h.second)
            } else merged += h
        }
        while (merged.isNotEmpty() && !merged.first().first) merged.removeAt(0)
        while (merged.isNotEmpty() && !merged.last().first) merged.removeAt(merged.lastIndex)
        return merged.map { it.second } + MANCHESTER_SILENCE
    }
}
