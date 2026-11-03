package com.nexuslights.panel

import com.nexuslights.panel.protocol.ColorMath
import com.nexuslights.panel.protocol.LightProtocol
import com.nexuslights.panel.protocol.LightProtocol.ElkBledom
import com.nexuslights.panel.protocol.LightProtocol.Triones
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Same vectors as tests/test_protocols.py, so the two ports can't drift. */
class ProtocolsTest {
    private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test fun trionesPowerFrames() {
        assertArrayEquals(b(0xCC, 0x23, 0x33), Triones.powerOn())
        assertArrayEquals(b(0xCC, 0x24, 0x33), Triones.powerOff())
    }

    @Test fun trionesRgbClamps() {
        assertArrayEquals(b(0x56, 255, 0, 128, 0x00, 0xF0, 0xAA), Triones.setRgb(300, -10, 128))
    }

    @Test fun trionesWhiteScales() {
        assertArrayEquals(b(0x56, 0, 0, 0, 255, 0x0F, 0xAA), Triones.setWhite(100))
        assertArrayEquals(b(0x56, 0, 0, 0, 0, 0x0F, 0xAA), Triones.setWhite(0))
    }

    @Test fun elkPowerFrames() {
        assertArrayEquals(b(0x7E, 0x00, 0x04, 0xF0, 0x00, 0x01, 0xFF, 0x00, 0xEF), ElkBledom.powerOn())
        assertArrayEquals(b(0x7E, 0x00, 0x04, 0x00, 0x00, 0x00, 0xFF, 0x00, 0xEF), ElkBledom.powerOff())
    }

    @Test fun elkRgb() {
        assertArrayEquals(b(0x7E, 0x00, 0x05, 0x03, 1, 2, 3, 0x00, 0xEF), ElkBledom.setRgb(1, 2, 3))
    }

    @Test fun elkBrightnessClamped() {
        assertArrayEquals(b(0x7E, 0x00, 0x01, 100, 0xFF, 0xFF, 0xFF, 0x00, 0xEF), ElkBledom.setBrightness(150))
    }

    @Test fun registry() {
        assertEquals(Triones, LightProtocol.byId("triones"))
        assertEquals(ElkBledom, LightProtocol.byId("elkbledom"))
        val e = runCatching { LightProtocol.byId("nope") }.exceptionOrNull()
        assertTrue(e?.message!!.contains("Unknown protocol"))
    }

    @Test fun guess() {
        assertEquals(Triones, LightProtocol.guess("Triones-A1B2"))
        assertEquals(ElkBledom, LightProtocol.guess("ELK-BLEDOM"))
        assertNull(LightProtocol.guess("Some Random Device"))
        assertNull(LightProtocol.guess(null))
    }

    @Test fun uuids() {
        assertEquals("0000ffd9-0000-1000-8000-00805f9b34fb", Triones.writeCharacteristic.toString())
        assertEquals("0000fff3-0000-1000-8000-00805f9b34fb", ElkBledom.writeCharacteristic.toString())
    }

    @Test fun hsv() {
        assertEquals(Triple(255, 0, 0), ColorMath.hsvToRgb(0f, 1f, 1f))
        assertEquals(Triple(0, 255, 0), ColorMath.hsvToRgb(120f, 1f, 1f))
        assertEquals(Triple(128, 128, 128), ColorMath.hsvToRgb(200f, 0f, 0.5f))
    }
}
