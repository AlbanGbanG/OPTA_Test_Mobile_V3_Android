package fr.opta.testmobile.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OptaBleProtocolTest {
    @Test
    fun decodePressureLittleEndian() {
        assertEquals(500, OptaBleProtocol.uint16LittleEndian(byteArrayOf(0xF4.toByte(), 0x01)))
    }

    @Test
    fun limitGemsSensorToTenBar() {
        assertEquals(null, OptaConfig(safetyPressureMaxRaw = 1000).validationError())
        assertTrue(OptaConfig(safetyPressureMaxRaw = 1001)
            .validationError()?.contains("10,00 bar") == true)
    }

    @Test
    fun parseAndBuildConfiguration() {
        val payload = "CFG;R54=41;R40=500;R41=50;R42=100;R43=300;R44=10;R45=10;R46=5;R47=3;R48=20;R49=800;R60=500;R61=1"
        val config = OptaBleProtocol.parseConfig(payload)
        assertNotNull(config)
        assertEquals(500, config?.pressureTargetRaw)
        assertTrue(config?.zebraEnabled == true)
        assertTrue(OptaBleProtocol.buildSetConfigCommand(requireNotNull(config)).startsWith("SETCFG;R54=41"))
    }
}
