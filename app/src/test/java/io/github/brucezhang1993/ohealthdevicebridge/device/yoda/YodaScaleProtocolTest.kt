package io.github.brucezhang1993.ohealthdevicebridge.device.yoda

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YodaScaleProtocolTest {
    @Test fun parsesLockedFrameWithImpedance() {
        val frame = YodaScaleProtocol.parse(hex("C0A6201C1388000025E0D38E6A9881"))
        requireNotNull(frame)
        assertEquals(82.20, frame.weightKg, 0.0001)
        assertEquals(500, frame.resistanceOhm)
        assertTrue(frame.locked)
        assertEquals("E0:D3:8E:6A:98:81", frame.mac)
    }

    @Test fun mapsZeroImpedanceToNullWithoutDroppingWeight() {
        val frame = YodaScaleProtocol.parse(hex("C0A8201C0000000025E0D38E6A9881"))
        requireNotNull(frame)
        assertEquals(82.20, frame.weightKg, 0.0001)
        assertNull(frame.resistanceOhm)
        assertTrue(frame.locked)
    }

    @Test fun rejectsUnlockedFrameEvenWhenProvisionalValuesAreNonZero() {
        assertNull(YodaScaleProtocol.parse(hex("C0A7201C1388000024E0D38E6A9881")))
    }

    private fun hex(value: String): ByteArray = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
