package io.github.g20ir

import io.github.g20ir.db.CodeDatabase
import io.github.g20ir.ir.EncodeException
import io.github.g20ir.ir.Encoders
import io.github.g20ir.ir.G20Payload
import io.github.g20ir.ir.IrSignal
import io.github.g20ir.ir.Protocol
import io.github.g20ir.ir.TextFormats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

class EncoderTest {

    private fun hexBytes(h: String) = ByteArray(h.length / 2) { h.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    /** LG power, captured from Google's own "set up remote buttons" flow (NEC, type 3). */
    private val capturedLg =
        "0321017c00220002015700ab0016001600160016001600410016001600160016001600160016001600160016001600410016004100160016001600410016004100160041001600410016004100160016001600160016001600160041001600160016001600160016001600160016004100160041001600410016001600160041001600410016004100160041001605f50157005600160e60"

    /** Denon AVR volume up, captured from the same flow (type 2). */
    private val capturedDenon =
        "0221017c0020123c000c001a000c0040000c001a000c001a000c001a000c0040000c001a000c001a000c001a000c0040000c0040000c0040000c0040000c001a000c001a000c05f4000c001a000c0040000c001a000c001a000c001a000c001a000c0040000c0040000c0040000c001a000c001a000c001a000c001a000c0040000c0040000c05f4"

    @Test
    fun sonySircMatchesHandDerivedFrame() {
        // Same frame as tools/legacy/sony.py: 40 kHz, 25 µs per cycle, 45 ms frame period.
        val b = G20Payload.build(IrSignal.Parsed(Protocol.SIRC, 1, 18))
        val d = G20Payload.decode(b.bytes)!!
        assertEquals(3, d.type)
        assertEquals(40000, d.carrierHz)
        val cmd = 18
        val bits = (0 until 7).map { (cmd shr it) and 1 } + (0 until 5).map { (1 shr it) and 1 }
        val expected = mutableListOf(96, 24)
        bits.forEach { expected += if (it == 1) 48 else 24; expected += 24 }
        expected[expected.lastIndex] += 1800 - expected.sum()
        assertEquals(expected + expected, d.partA) // 2 frames once
        assertEquals(expected, d.partB) // + 1 repeated while held => 3 on a tap
        assertTrue(b.bytes.size <= G20Payload.DEFAULT_MAX_BYTES)
    }

    @Test
    fun necMatchesGoogleCapturedLgCode() {
        val cap = G20Payload.decode(hexBytes(capturedLg))!!
        assertEquals(3, cap.type)
        // Recover the 32 bits from the captured frame and re-encode them with our NEC encoder.
        val spaces = cap.partA.drop(2).chunked(2).take(32).map { it[1] }
        var bits = 0L
        spaces.forEachIndexed { i, s -> if (s > 40) bits = bits or (1L shl i) }
        val addr = bits and 0xFF
        val cmd = (bits shr 16) and 0xFF
        assertEquals("address inverse", addr xor 0xFF, (bits shr 8) and 0xFF)
        assertEquals("command inverse", cmd xor 0xFF, (bits shr 24) and 0xFF)
        val ours = G20Payload.decode(G20Payload.build(IrSignal.Parsed(Protocol.NEC, addr, cmd)).bytes)!!
        assertEquals(3, ours.type)
        assertEquals(cap.partA.size, ours.partA.size)
        assertEquals(cap.partB.size, ours.partB.size)
        // Same structure; marks and spaces within 6 % (gaps excluded: Google uses a shorter period).
        (cap.partA + cap.partB).zip(ours.partA + ours.partB).forEach { (g, o) ->
            if (g < 1000) assertTrue("google $g vs ours $o", abs(g - o) <= maxOf(2, g * 6 / 100))
        }
        println("LG captured = NEC addr 0x%02X cmd 0x%02X".format(addr, cmd))
    }

    @Test
    fun capturedPayloadsAreAcceptedAsIs() {
        for (h in listOf(capturedLg, capturedDenon)) {
            val s = TextFormats.parse(h)
            assertTrue(s is IrSignal.G20Hex)
            assertEquals(h, G20Payload.build(s).hex)
        }
    }

    @Test
    fun prontoRoundTrip() {
        val pronto = TextFormats.toPronto(IrSignal.G20Hex(capturedLg))
        val back = G20Payload.decode(G20Payload.build(TextFormats.parse(pronto)).bytes)!!
        val orig = G20Payload.decode(hexBytes(capturedLg))!!
        assertEquals(orig.type, back.type)
        (orig.partA + orig.partB).zip(back.partA + back.partB).forEach { (a, b) -> assertTrue(abs(a - b) <= 1) }
    }

    @Test
    fun rc5FrameLength() {
        val w = Encoders.encode(IrSignal.Parsed(Protocol.RC5, 0, 12))
        val body = w.frame.dropLast(1).sum()
        // 14 bits x 1.776 ms, minus the leading half-bit space (and maybe a trailing one)
        assertTrue("rc5 body $body", body in 23_000..24_900)
        assertTrue(w.frame.size % 2 == 0)
        assertEquals(4, G20Payload.build(IrSignal.Parsed(Protocol.RC5, 0, 12)).type)
    }

    @Test
    fun rc6FrameLength() {
        val w = Encoders.encode(IrSignal.Parsed(Protocol.RC6, 0, 0x0C))
        val body = w.frame.dropLast(1).sum()
        // leader 3.555 ms + 21 bits (toggle double) x 0.888 ms = 23.1 ms, minus a trailing space
        assertTrue("rc6 body $body", body in 22_000..23_200)
        assertTrue(w.frame.size % 2 == 0)
    }

    @Test
    fun wholeDatabaseEncodes() {
        val db = CodeDatabase(File("src/main/assets/irdb.json").readText())
        val ok = mutableMapOf<String, Int>()
        val tooBig = mutableMapOf<String, Int>()
        val bySize = sortedMapOf<String, Int>()
        for (cat in db.categories) for (brand in db.brands(cat)) for (set in db.codeSets(cat, brand.name)) {
            for ((_, sigs) in set.keys) for (s in sigs) {
                val name = when (s) { is IrSignal.Parsed -> s.protocol.id; is IrSignal.Raw -> "RAW"; else -> "G20" }
                try {
                    val b = G20Payload.build(s)
                    assertNotNull(G20Payload.decode(b.bytes))
                    ok.merge(name, 1, Int::plus)
                    bySize.merge("$name type${b.type}", 1, Int::plus)
                } catch (e: EncodeException) {
                    tooBig.merge(name, 1, Int::plus)
                }
            }
        }
        println("encoded: $ok")
        println("types:   $bySize")
        println("too big: $tooBig")
        // Every parsed protocol except the 48/42-bit ones must fit in one write.
        val allowedTooBig = setOf("Kaseikyo", "NEC42", "NEC42ext", "RAW")
        assertTrue("unexpected: $tooBig", tooBig.keys.all { it in allowedTooBig })
    }
}
