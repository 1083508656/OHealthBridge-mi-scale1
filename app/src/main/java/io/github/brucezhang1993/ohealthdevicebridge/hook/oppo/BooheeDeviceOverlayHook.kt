package io.github.brucezhang1993.ohealthdevicebridge.hook.oppo

import de.robv.android.xposed.*
import io.github.brucezhang1993.ohealthdevicebridge.device.DeviceRegistry
import io.github.brucezhang1993.ohealthdevicebridge.store.BridgeBindingStore

object BooheeDeviceOverlayHook {
    fun install(cl: ClassLoader) {
        val clazz = XposedHelpers.findClass("com.heytap.device.ui.weight.scale.boohee.BooheeDeviceLoader", cl)
        val hooks = XposedBridge.hookAllMethods(clazz, "convertBoohee", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (param.hasThrowable()) return
                val context = OppoReflect.context() ?: return
                val account = try { OppoAccount.key(cl) } catch (_: Exception) { return }
                val original = param.result as? List<*> ?: return
                for (item in original.filterNotNull()) {
                    val model = OppoReflect.readString(item, "getModel", "model") ?: continue
                    if (DeviceRegistry.byModel(model) != null) {
                        OppoReflect.readString(item, "getMac", "mac")?.let { BridgeBindingStore.add(context, account, it, model) }
                    }
                }
                val existing = original.filterNotNull().mapNotNull { OppoReflect.readString(it, "getMac", "mac") }
                    .map(io.github.brucezhang1993.ohealthdevicebridge.store.AfuBindingStore::normalize).toSet()
                val items = ArrayList<Any>(original.filterNotNull())
                val factory = OppoObjectFactory(cl)
                for ((mac, model) in BridgeBindingStore.devices(context, account).filterKeys { it !in existing }) {
                    items.add(factory.createWeightDevice(mac, DeviceRegistry.byModel(model)?.isConnected(mac) == true, model))
                }
                param.result = items
            }
        })
        check(hooks.isNotEmpty())
    }
}
