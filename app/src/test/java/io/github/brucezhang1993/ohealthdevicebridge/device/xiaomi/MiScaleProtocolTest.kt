package io.github.brucezhang1993.ohealthdevicebridge.device.xiaomi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MiScaleProtocolTest {
    private fun hex(value: String): ByteArray {
        val clean = value.filter { !it.isWhitespace() }
        return ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    @Test
    fun bodyCompositionPacketCarriesWeightImpedanceAndClock() {
        // Stable 2026-10-09 12:30:00, 70.00 kg, 500 ohm (13-byte body composition payload).
        val reading = MiScaleProtocol.parse(hex("0022ea070a090c1e00f401b036"), true, 0)!!
        assertTrue(reading.stable)
        assertFalse(reading.removed)
        assertTrue(reading.dated)
        assertEquals(70.0, reading.record.weightKg, 0.0001)
        assertEquals(500, reading.record.resistanceOhm)
    }

    @Test
    fun firstGenerationBodyScaleCaptureParsesAsWeightOnlyUntilImpedanceArrives() {
        // Public 1st-gen Mi Body Composition Scale capture: 80.16 kg, impedance still 0xFFFF.
        val reading = MiScaleProtocol.parse(hex("02a4e9070101022c38ffffa03e"), true, 0)!!
        assertEquals(80.16, reading.record.weightKg, 0.0001)
        assertNull(reading.record.resistanceOhm)
        assertTrue(reading.dated)
    }

    @Test
    fun truncatedAndGarbagePayloadsAreRejected() {
        val bytes = hex("0022ea070a090c1e00f401b036")
        assertNull(MiScaleProtocol.parse(bytes.copyOf(12), true, 0))
        // An all-zero frame is a structural match with no weight; the session layer drops weight <= 0.
        assertEquals(0.0, MiScaleProtocol.parse(ByteArray(13), true, 0)!!.record.weightKg, 0.0)
        for (n in 0 until 13) assertNull(MiScaleProtocol.parse(bytes.copyOf(n), true, 0))
    }

    @Test
    fun poundAndCattyUnitsAreConverted() {
        val pounds = hex("0122ea070a090c1e00f401b036")
        assertEquals(70.0 * 0.45359237 * 2, MiScaleProtocol.parse(pounds, true, 0)!!.record.weightKg, 0.01)
        val catty = hex("0042ea070a090c1e00f401b036")
        assertEquals(70.0, MiScaleProtocol.parse(catty, true, 0)!!.record.weightKg, 0.0001)
    }

    @Test
    fun weightOnlyGenerationUsesTenByteLayout() {
        val reading = MiScaleProtocol.parse(hex("20b036ea070a090c1e00"), false, 0)!!
        assertTrue(reading.stable)
        assertEquals(70.0, reading.record.weightKg, 0.0001)
        assertNull(reading.record.resistanceOhm)
        assertTrue(MiScaleProtocol.parse(hex("a0b036ea070a090c1e00"), false, 0)!!.removed)
    }

    @Test
    fun historyStreamIsReassembledAcrossNotificationBoundaries() {
        val old = hex("20b036ea070a090c1e00")
        val history = MiScaleProtocol.History(false)
        assertTrue(history.feed(old.copyOf(4), 0).isEmpty())
        assertEquals(70.0, history.feed(old.copyOfRange(4, 10), 0).single().record.weightKg, 0.0001)
        history.feed(byteArrayOf(3), 0)
        assertFalse(history.transferring)
    }

    @Test
    fun transportsMatchEachGeneration() {
        assertEquals(XiaomiModels.Transport.BODY_V1, XiaomiModels.transport(XiaomiModels.V1_BODY))
        assertEquals(0x181b, XiaomiModels.transport(XiaomiModels.V1_BODY).serviceShort)
        assertEquals(13, XiaomiModels.transport(XiaomiModels.V1_BODY).payloadSize)
        assertEquals(XiaomiModels.Transport.BODY_V2, XiaomiModels.transport(XiaomiModels.V2))
        assertEquals(XiaomiModels.Transport.WEIGHT, XiaomiModels.transport(XiaomiModels.V1))
        assertEquals(0x181d, XiaomiModels.transport(XiaomiModels.V1).serviceShort)
        assertEquals(10, XiaomiModels.transport(XiaomiModels.V1).payloadSize)
        assertEquals(listOf(XiaomiModels.V1, XiaomiModels.V1_BODY, XiaomiModels.V2), XiaomiModels.names.keys.toList())
    }
}
