package io.github.brucezhang1993.ohealthdevicebridge.hook.oppo

import io.github.brucezhang1993.ohealthdevicebridge.BridgeConstants
import io.github.brucezhang1993.ohealthdevicebridge.device.xiaomi.XiaomiModels

class OppoObjectFactory(private val classLoader: ClassLoader) {
    fun createBhDevice(mac: String, model: String = BridgeConstants.AFU_MODEL): Any {
        val clazz = classLoader.loadClass(BH_DEVICE_MODEL)
        val raw = clazz.getConstructor(String::class.java, String::class.java).newInstance(mac, model)
        OppoReflect.call(raw, "setDeviceId", model)
        OppoReflect.call(raw, "setDeviceMac", mac)
        OppoReflect.call(raw, "setDeviceModel", model)
        OppoReflect.call(raw, "setDeviceName", model)
        // The SDK algorithm's connect flag also sends B3 to the global Boohee transport.
        // The OPPO route uses BindableScaleDevice.isConnectScale, independently of this flag.
        OppoReflect.set(raw, "isConnectScale", false)
        return raw
    }
    fun createBindable(mac: String, model: String = BridgeConstants.AFU_MODEL): Any {
        val vendorClass = classLoader.loadClass(SCALE_VENDOR)
        val boohee = vendorClass.getField("BOOHEE").get(null)
        return classLoader.loadClass(BINDABLE_SCALE_DEVICE).getConstructor(vendorClass, String::class.java,
            String::class.java, String::class.java, Boolean::class.javaPrimitiveType, Any::class.java)
            .newInstance(boohee, mac, (XiaomiModels.names[model] ?: BridgeConstants.AFU_DISPLAY_NAME), model, true, createBhDevice(mac, model))
    }
    fun createWeightDevice(mac: String, connected: Boolean, model: String = BridgeConstants.AFU_MODEL): Any {
        val item = classLoader.loadClass(WEIGHT_DEVICE_INFO).getConstructor().newInstance()
        OppoReflect.set(item, "deviceName", (XiaomiModels.names[model] ?: BridgeConstants.AFU_DISPLAY_NAME))
        OppoReflect.set(item, "deviceUniqueId", mac)
        OppoReflect.set(item, "id", mac)
        OppoReflect.set(item, "mac", mac)
        OppoReflect.set(item, "connectScale", true)
        OppoReflect.set(item, "connected", connected)
        OppoReflect.set(item, "model", model)
        OppoReflect.set(item, "deviceType", 100)
        OppoReflect.set(item, "manufacturer", if (model in XiaomiModels.names) "小米" else "薄荷健康")
        return item
    }
    companion object {
        const val BH_DEVICE_MODEL = "com.boohee.scale_sdk.device.BHDeviceModel"
        const val BH_SCALE_MODEL = "com.boohee.scale_sdk.data.BHScaleModel"
        const val BH_USER_MODEL = "com.boohee.scale_sdk.user.BHUserModel"
        const val BH_SCALE_MANAGER = "com.boohee.scale_sdk.BHScaleManager"
        const val BINDABLE_SCALE_DEVICE = "com.heytap.device.ui.weight.scale.BindableScaleDevice"
        const val SCALE_VENDOR = "com.heytap.device.ui.weight.scale.ScaleVendor"
        const val WEIGHT_DEVICE_INFO = "com.heytap.health.devicemanager.third_device.weightscale.WeightScaleDeviceInfo"
    }
}
