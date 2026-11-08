package io.github.brucezhang1993.ohealthdevicebridge.device.yoda

/**
 * Broadcast protocol of the JD Health LEN-1001 body-fat scale (BLE name "Yoda0").
 *
 * Recovered from HCI snoop, the frame is a 15-byte manufacturer-specific AD payload. It needs
 * no GATT connection at all: the scale advertises the settled measurement directly.
 *
 * ```
 *  0     1     2..3       4..5          6..7       8        9..14
 * +-----+-----+----------+-------------+----------+--------+---------------+
 * | C0  | seq | weight   | resistance  | reserved | status | MAC (6 bytes) |
 * +-----+-----+----------+-------------+----------+--------+---------------+
 * ```
 *
 * - `seq` increments once per weighing session (observed A6 -> A7 -> A8 -> A9), so it doubles
 *   as the dedup key: the scale keeps re-advertising the last result for tens of seconds.
 * - `weight` is big-endian centi-kg (0x201C = 8220 = 82.20 kg).
 * - `resistance` is big-endian deci-ohm (0x1388 = 5000 = 500.0 ohm). Zero means the scale did
 *   not get a valid impedance reading (socks / poor electrode contact / stepped off too early).
 *   Verified against two weighings: 18:22 and 18:23 carried 5000, 18:31 carried 0 while the
 *   weight stayed identical.
 * - `status` 0x25 = locked (settled) frame, 0x24 = weight-not-locked frame (all data zero).
 * - `MAC` is the scale's own public address in normal (non-reversed) order.
 */
object YodaScaleProtocol {
    const val MANUFACTURER_DATA_LEN = 15
    private const val STATUS_LOCKED = 0x25
    private const val MIN_PLAUSIBLE_KG = 10.0
    private const val MAX_PLAUSIBLE_KG = 200.0

    data class Frame(
        val sequence: Int,
        val weightKg: Double,
        /** null when the scale did not measure a valid impedance. */
        val resistanceOhm: Int?,
        val locked: Boolean,
        val mac: String,
    )

    fun parse(data: ByteArray): Frame? {
        if (data.size < MANUFACTURER_DATA_LEN) return null
        if ((data[0].toInt() and 0xFF) != 0xC0) return null

        val sequence = data[1].toInt() and 0xFF
        val status = data[8].toInt() and 0xFF
        val locked = status == STATUS_LOCKED
        val weightRaw = u16(data, 2)
        val resistanceRaw = u16(data, 4)
        val mac = data.copyOfRange(9, 15).joinToString(":") { "%02X".format(it) }

        val weightKg = weightRaw / 100.0
        if (weightKg < MIN_PLAUSIBLE_KG || weightKg > MAX_PLAUSIBLE_KG) return null

        // An unlocked frame carries all-zero data; never treat it as a measurement.
        if (!locked && weightRaw == 0 && resistanceRaw == 0) return null

        // 5000 deci-ohm = 500 ohm. Anything above 1000 ohm after scaling is implausible for a
        // whole-body BIA reading, so it is discarded rather than fed into the body-fat algorithm.
        val resistanceOhm = if (resistanceRaw > 0) (resistanceRaw / 10.0).toInt() else null
        return Frame(
            sequence = sequence,
            weightKg = weightKg,
            resistanceOhm = if (resistanceOhm != null && resistanceOhm in 50..1500) resistanceOhm else null,
            locked = locked,
            mac = mac,
        )
    }

    private fun u16(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
}
