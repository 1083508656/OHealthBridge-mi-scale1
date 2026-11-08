package io.github.brucezhang1993.ohealthdevicebridge.device

import io.github.brucezhang1993.ohealthdevicebridge.BridgeConstants
import io.github.brucezhang1993.ohealthdevicebridge.device.afu.AfuB1Driver
import io.github.brucezhang1993.ohealthdevicebridge.device.xiaomi.*
import io.github.brucezhang1993.ohealthdevicebridge.device.yoda.YodaScaleDriver

object DeviceRegistry {
    private val drivers: Map<String, DeviceDriver> = listOf(
        AfuB1Driver,
        YodaScaleDriver,
        MiScaleDriver(XiaomiModels.V1), MiScaleDriver(XiaomiModels.V1_BODY), MiScaleDriver(XiaomiModels.V2),
    ).associateBy { it.model }

    fun byModel(model: String?): DeviceDriver? = model?.uppercase(java.util.Locale.ROOT)?.let(drivers::get)

    fun afu(): DeviceDriver = requireNotNull(drivers[BridgeConstants.AFU_MODEL])

    fun supportedModels(): Set<String> = drivers.keys
}
