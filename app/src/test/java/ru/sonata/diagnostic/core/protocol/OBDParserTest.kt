package ru.sonata.diagnostic.core.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OBDParserTest {

    @Test
    fun parsesEngineRpm() {
        val result = OBDParser.parseMode01("41 0C 1B 00", "010C")

        assertEquals(1728.0, result.first().value, 0.0)
        assertEquals("об/мин", result.first().unit)
    }

    @Test
    fun parsesVehicleSpeed() {
        val result = OBDParser.parseMode01("41 0D 50", "010D")

        assertEquals(80.0, result.first().value, 0.0)
    }

    @Test
    fun parsesCoolantTemperature() {
        val result = OBDParser.parseMode01("41 05 82", "0105")

        assertEquals(90.0, result.first().value, 0.0)
    }

    @Test
    fun parsesIntakeTemperature() {
        val result = OBDParser.parseMode01("41 0F 41", "010F")

        assertEquals(25.0, result.first().value, 0.0)
    }

    @Test
    fun parsesThrottlePosition() {
        val result = OBDParser.parseMode01("41 11 59", "0111")

        assertEquals(34.9, result.first().value, 0.1)
    }

    @Test
    fun returnsEmptyForUnsupportedPid() {
        val result = OBDParser.parseMode01("41 20 FF FF FF FF", "0120")

        assertTrue(result.isEmpty())
    }

    @Test
    fun parsesCanHeaderAndDlc() {
        val result = OBDParser.parseMode01("7E8 04 41 0C 1B 00", "010C")

        assertEquals(1728.0, result.first().value, 0.0)
        assertEquals("7E8", result.first().ecuAddress)
    }

    @Test
    fun separatesResponsesFromDifferentEcus() {
        val result = OBDParser.parseMode01(
            """
            7E8 04 41 0D 50
            7E9 04 41 0D 32
            """.trimIndent(),
            "010D"
        )

        assertEquals(2, result.size)
        assertEquals(80.0, result[0].value, 0.0)
        assertEquals(50.0, result[1].value, 0.0)
        assertEquals("7E8", result[0].ecuAddress)
        assertEquals("7E9", result[1].ecuAddress)
    }

    @Test
    fun detectsNegativeResponse() {
        val result = OBDParser.parseNegativeResponses("7E8 03 7F 01 11")

        assertEquals(1, result.size)
        assertEquals(0x01, result[0].requestedService)
        assertEquals(0x11, result[0].errorCode)
        assertEquals("7E8", result[0].ecuAddress)
    }

    @Test
    fun ignoresIncompleteRpmResponse() {
        val result = OBDParser.parseMode01("41 0C 1B", "010C")

        assertTrue(result.isEmpty())
    }

}
