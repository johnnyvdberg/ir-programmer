package io.github.g20ir

import io.github.g20ir.buttons.ButtonAction
import io.github.g20ir.buttons.GeteventParser
import io.github.g20ir.buttons.RemoteButton
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ButtonsTest {
    /** Verbatim `getevent -l` output from the owner's Shield + G20 (rocket, Prime, red, gear). */
    private val captured = """
/dev/input/event8: EV_MSC       MSC_SCAN             000c007a            
/dev/input/event8: EV_KEY       KEY_UNKNOWN          DOWN                
/dev/input/event8: EV_SYN       SYN_REPORT           00000000            
/dev/input/event8: EV_MSC       MSC_SCAN             000c007a            
/dev/input/event8: EV_KEY       KEY_UNKNOWN          UP                  
/dev/input/event8: EV_SYN       SYN_REPORT           00000000            
/dev/input/event8: EV_MSC       MSC_SCAN             000c0079            
/dev/input/event8: EV_KEY       KEY_UNKNOWN          DOWN                
/dev/input/event8: EV_SYN       SYN_REPORT           00000000            
/dev/input/event8: EV_MSC       MSC_SCAN             000c0079            
/dev/input/event8: EV_KEY       KEY_UNKNOWN          UP                  
/dev/input/event8: EV_SYN       SYN_REPORT           00000000            
/dev/input/event8: EV_MSC       MSC_SCAN             000c0069            
/dev/input/event8: EV_KEY       KEY_RED              DOWN                
/dev/input/event8: EV_SYN       SYN_REPORT           00000000            
/dev/input/event8: EV_MSC       MSC_SCAN             000c0096            
/dev/input/event8: EV_KEY       KEY_TAPE             DOWN                
/dev/input/event8: EV_SYN       SYN_REPORT           00000000            
"""

    @Test
    fun parserReportsOnlyAppButtonDowns() {
        val p = GeteventParser()
        val hits = captured.lines().mapNotNull { p.feed(it) }
        assertEquals(listOf(RemoteButton.ROCKET, RemoteButton.PRIME), hits)
    }

    @Test
    fun parserHandlesCrlfAndAllFourAppButtons() {
        val p = GeteventParser()
        val hits = listOf(0x77, 0x78, 0x79, 0x7A).mapNotNull { u ->
            p.feed("/dev/input/event8: EV_MSC       MSC_SCAN             000c%04x\r".format(u))
            p.feed("/dev/input/event8: EV_KEY       KEY_UNKNOWN          DOWN\r")
        }
        assertEquals(listOf(RemoteButton.YOUTUBE, RemoteButton.NETFLIX, RemoteButton.PRIME, RemoteButton.ROCKET), hits)
        assertNull(p.feed("could not open /dev/input/event3, Permission denied"))
    }

    @Test
    fun scanCodesFromAndroidKeyEvents() {
        assertEquals(RemoteButton.RED, RemoteButton.fromScanCode(398))
        assertEquals(RemoteButton.SETTINGS, RemoteButton.fromScanCode(384))
        assertEquals(RemoteButton.GUIDE, RemoteButton.fromScanCode(362))
        assertNull(RemoteButton.fromScanCode(240)) // app buttons are ambiguous
        assertNull(RemoteButton.fromScanCode(115)) // volume up: not ours
    }

    @Test
    fun actionsRoundTrip() {
        val all = ButtonAction.fixed + ButtonAction.App("com.netflix.ninja", "Netflix | TV")
        for (a in all) assertEquals(a, ButtonAction.decode(a.encode()))
        assertNull(ButtonAction.decode("bogus"))
    }
}
