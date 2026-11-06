package io.github.brucezhang1993.ohealthdevicebridge.hook.oppo

import de.robv.android.xposed.XposedHelpers
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

    /**
     * Generic device-list entry (UserDeviceInfo, un-obfuscated in 6.10.14).
     *
     * OPPO Health decides how to render a device from `deviceType` + `model`:
     *   - deviceType in its wearable set  -> wearable card; a third-party model with no version
     *     config falls through to DeviceDisableItem ("app version too low").
     *   - deviceType == 100 and model in BODY_FAT_MODELS_BOOHEE -> native body-fat scale page,
     *     the same UI OPPO uses for its Boohee (薄荷健康) scales.
     *
     * So we present the bound scale as a Boohee-class body-fat scale to get that native page.
     * This is display-only: the real Xiaomi model stays in BridgeBindingStore and is what the
     * BLE scan, matching and record-writing code uses.
     */
    fun createUserDevice(mac: String, connected: Boolean, model: String = BridgeConstants.AFU_MODEL): Any {
        val item = classLoader.loadClass(USER_DEVICE_INFO).getConstructor().newInstance()
        val displayName = XiaomiModels.names[model] ?: BridgeConstants.AFU_DISPLAY_NAME
        OppoReflect.set(item, "deviceName", displayName)
        OppoReflect.set(item, "deviceUniqueId", mac)
        OppoReflect.set(item, "mac", mac)
        OppoReflect.set(item, "model", BOOHEE_SCALE_MODEL)
        OppoReflect.set(item, "deviceType", DEVICE_TYPE_BODY_FAT_SCALE)
        OppoReflect.set(item, "manufacturer", if (model in XiaomiModels.names) "小米" else "薄荷健康")
        runCatching { OppoReflect.set(item, "connectionState", if (connected) 1 else 0) }
        runCatching { OppoReflect.set(item, "clickable", true) }
        runCatching { OppoReflect.set(item, "deviceMarketName", displayName) }
        runCatching { OppoReflect.set(item, "deviceOsVersion", "1.0.0") }
        runCatching { OppoReflect.set(item, "firmwareVersion", "1.0.0") }
        runCatching { OppoReflect.set(item, "appTerminalId", mac) }
        runCatching { OppoReflect.set(item, "deviceSn", mac) }
        runCatching { OppoReflect.set(item, "sku", BOOHEE_SCALE_MODEL) }
        return item
    }

    /**
     * Native body-fat-scale bean (obfuscated `tt/a`). The device tab renders this as a real
     * body-fat scale (weight/BMI cards, measure entry) when it is wrapped in `wq.d(tt/a)`,
     * whereas a UserDeviceInfo wrapped in `wq.c` is rendered as a watch. Field/setter mapping is
     * reverse-engineered from DeviceTabAdapterController.updateViewBean (6.10.14):
     *   k(String)=name  m(String)=deviceUniqueId  o(String)=mac  l(int)=deviceType
     *   n(String)=deviceUniqueId(alt)  q(String)=model  p(String)=manufacturer
     *   j(String)=deviceIconPath  i(Z)=connect-type flag  m(public Z)=broadcast-type flag
     */
    fun createBodyFatScaleBean(mac: String, model: String = BridgeConstants.AFU_MODEL): Any {
        val tt = classLoader.loadClass(BODY_FAT_BEAN).getConstructor().newInstance()
        val displayName = XiaomiModels.names[model] ?: BridgeConstants.AFU_DISPLAY_NAME
        val manufacturer = if (model in XiaomiModels.names) "小米" else "薄荷健康"
        OppoReflect.call(tt, "k", displayName)                // name
        OppoReflect.call(tt, "m", mac)                        // deviceUniqueId (mac-normalized)
        OppoReflect.call(tt, "o", mac)                        // mac
        OppoReflect.call(tt, "l", DEVICE_TYPE_BODY_FAT_SCALE) // deviceType = 100
        OppoReflect.call(tt, "n", mac)                        // deviceUniqueId (alt)
        OppoReflect.call(tt, "q", BOOHEE_SCALE_MODEL)         // model = 3501 (connect type)
        OppoReflect.call(tt, "p", manufacturer)               // manufacturer
        runCatching { OppoReflect.call(tt, "j", "") }         // deviceIconPath (optional)
        OppoReflect.call(tt, "i", true)                       // connect-type flag (J9(model))
        runCatching { XposedHelpers.setBooleanField(tt, "m", false) } // broadcast-type flag = !J9
        return tt
    }

    companion object {
        const val BH_DEVICE_MODEL = "com.boohee.scale_sdk.device.BHDeviceModel"
        const val BH_SCALE_MODEL = "com.boohee.scale_sdk.data.BHScaleModel"
        const val BH_USER_MODEL = "com.boohee.scale_sdk.user.BHUserModel"
        const val BH_SCALE_MANAGER = "com.boohee.scale_sdk.BHScaleManager"
        const val BINDABLE_SCALE_DEVICE = "com.heytap.device.ui.weight.scale.BindableScaleDevice"
        const val SCALE_VENDOR = "com.heytap.device.ui.weight.scale.ScaleVendor"
        const val WEIGHT_DEVICE_INFO = "com.heytap.health.devicemanager.third_device.weightscale.WeightScaleDeviceInfo"
        const val USER_DEVICE_INFO = "com.heytap.health.devicemanager.processor.bean.UserDeviceInfo"

        /** Obfuscated body-fat-scale bean; wrapped by `wq.d` in the device switcher. */
        const val BODY_FAT_BEAN = "tt.a"

        /** Obfuscated body-fat-scale wrapper (wq.a subclass, ctor wq.d(tt/a)). */
        const val BODY_FAT_WRAPPER = "wq.d"

        /** Native body-fat scale branch in DeviceTabAdapterController. */
        private const val DEVICE_TYPE_BODY_FAT_SCALE = 100

        /**
         * One of st.a.BODY_FAT_MODELS_BOOHEE_CONNECT (3501 / 3504) — the GATT "connect" scales.
         * The 3102 / 3105 / 3107 models are "broadcast" scales and OPPO renders them as unusable
         * in the device page; the Mi scale is a GATT-connect scale, so it must use a connect model.
         */
        private const val BOOHEE_SCALE_MODEL = "3501"
    }
}
