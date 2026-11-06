package io.github.brucezhang1993.ohealthdevicebridge.device.xiaomi

object XiaomiModels {
    const val V1 = "XIAOMI-MI-SCALE-V1"
    const val V1_BODY = "XIAOMI-MI-SCALE-V1-BODY"
    const val V2 = "XIAOMI-MI-SCALE-V2"

    val names = linkedMapOf(
        V1 to "小米体重秤（一代）",
        V1_BODY to "小米体脂秤（一代）",
        V2 to "小米体脂秤（二代）",
    )

    /**
     * GATT transport of the legacy Mi scale family.
     * 0x181D / 10 bytes = weight-only 1st gen; 0x181B / 13 bytes = body composition (1st and 2nd gen).
     */
    enum class Transport(
        val serviceShort: Int,
        val payloadSize: Int,
        val bodyComposition: Boolean,
        val impedanceWaitMs: Long,
        val history: Boolean,
    ) {
        WEIGHT(0x181d, 10, false, 0, true),
        BODY_V1(0x181b, 13, true, 8_000, false),
        BODY_V2(0x181b, 13, true, 3_000, true),
    }

    fun transport(model: String): Transport = when (model) {
        V1_BODY -> Transport.BODY_V1
        V2 -> Transport.BODY_V2
        else -> Transport.WEIGHT
    }
}
