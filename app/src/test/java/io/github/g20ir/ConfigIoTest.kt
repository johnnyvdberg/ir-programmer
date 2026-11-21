package io.github.g20ir

import io.github.g20ir.ir.G20Payload
import io.github.g20ir.ir.IrSignal
import io.github.g20ir.ir.Protocol
import io.github.g20ir.model.Assignment
import io.github.g20ir.model.ConfigIo
import io.github.g20ir.model.G20Key
import io.github.g20ir.model.RemoteConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigIoTest {
    private val config = RemoteConfig(
        mapOf(
            G20Key.POWER to Assignment(IrSignal.Parsed(Protocol.SIRC, 1, 21), "Sony"),
            G20Key.VOL_UP to Assignment(IrSignal.Parsed(Protocol.NEC, 4, 2), "LG"),
            G20Key.MUTE to Assignment(IrSignal.Raw(38000, 33, listOf(9000, 4500, 560, 40000)), "Raw"),
        ),
    )

    @Test
    fun roundTrip() {
        val text = ConfigIo.export(config, G20Key.MAGIC_KEY_ID)
        val back = ConfigIo.import(text)
        assertEquals(config, back.config)
        assertEquals(G20Key.MAGIC_KEY_ID, back.inputKeyId)
        assertTrue(back.warnings.isEmpty())
    }

    @Test
    fun prontoOnlyFile() {
        val pronto = "0000 006D 0022 0002 0157 00AB 0016 0016 0016 0016 0016 0041 0016 0016 0016 0016 0016 0016 0016 0016 " +
            "0016 0016 0016 0041 0016 0041 0016 0016 0016 0041 0016 0041 0016 0041 0016 0041 0016 0041 0016 0016 " +
            "0016 0016 0016 0016 0016 0041 0016 0016 0016 0016 0016 0016 0016 0016 0016 0041 0016 0041 0016 0041 " +
            "0016 0016 0016 0041 0016 0041 0016 0041 0016 0041 0016 05F5 0157 0056 0016 0E60"
        val r = ConfigIo.import("""{"keys": {"power": {"pronto": "$pronto"}, "bogus": {}}}""")
        assertEquals(setOf(G20Key.POWER), r.config.keys.keys)
        assertEquals(3, G20Payload.build(r.config.keys.getValue(G20Key.POWER).signal).type)
        assertEquals(listOf("ignored \"bogus\""), r.warnings)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsGarbage() {
        ConfigIo.import("hello")
    }
}
