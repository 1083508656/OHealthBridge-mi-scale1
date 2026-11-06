package io.github.brucezhang1993.ohealthdevicebridge.hook.oppo

import de.robv.android.xposed.*
import io.github.brucezhang1993.ohealthdevicebridge.*
import io.github.brucezhang1993.ohealthdevicebridge.device.DeviceRegistry
import io.github.brucezhang1993.ohealthdevicebridge.store.BridgeBindingStore

object BooheeBindHook {
    fun install(cl: ClassLoader) {
        val clazz = XposedHelpers.findClass("com.heytap.device.ui.weight.scale.boohee.BHDeviceManager", cl)
        val hooks = XposedBridge.hookAllMethods(clazz, "bind", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val device = param.args[0]
                val model = OppoReflect.readString(device, "getModel", "model") ?: return
                val driver = DeviceRegistry.byModel(model) ?: return
                param.result = null
                OppoReflect.postMain {
                    val listener = param.args[1]
                    try {
                        val context = checkNotNull(OppoReflect.context()) { "OPPO context unavailable" }
                        val mac = checkNotNull(OppoReflect.readString(device, "getMac", "mac"))
                        OppoAfuRuntime.stopAll()
                        pauseNative(cl, param.thisObject)
                        val ticket = OppoAfuRuntime.Ticket(OppoAccount.key(cl), mac, model)
                        OppoAfuRuntime.bind = ticket
                        driver.verifyForBind(context, mac, {
                            if (ticket.valid(cl)) {
                                try {
                                    OppoReflect.call(param.thisObject, "onBindSucceededOnThisPhone", mac)
                                    BridgeBindingStore.add(context, ticket.account, mac, model)
                                    // Local transport binding is independent of asynchronous cloud persistence.
                                    OppoReflect.call(listener, "onSuccess", device)
                                } catch (error: Exception) {
                                    BridgeLog.e("AFU bind callback failed", error)
                                    OppoReflect.callFirst(listener, listOf("onFail"), error.message ?: "AFU bind callback failed")
                                } finally {
                                    ticket.active = false
                                    if (OppoAfuRuntime.bind === ticket) OppoAfuRuntime.bind = null
                                }
                            }
                        }, { error -> if (ticket.valid(cl)) {
                            ticket.active = false
                            if (OppoAfuRuntime.bind === ticket) OppoAfuRuntime.bind = null
                            OppoReflect.call(listener, "onFail", error.message ?: "AFU bind failed")
                        } })
                    } catch (error: Exception) {
                        BridgeLog.e("AFU bind failed", error)
                        OppoReflect.callFirst(listener, listOf("onFail"), error.message ?: "AFU bind failed")
                    }
                }
            }
        })
        check(hooks.isNotEmpty())
        HookInstallState.bind = true
    }
    internal fun pauseNative(cl: ClassLoader, manager: Any) {
        val coordinator = cl.loadClass("com.heytap.device.ui.weight.scale.WeightScaleKeepAliveCoordinator").getField("INSTANCE").get(null)!!
        OppoReflect.call(coordinator, "pause")
        OppoReflect.call(manager, "forceStopKeepAlive", "AFU transport handoff")
        OppoReflect.call(manager, "stopMeasureReceive")
        OppoReflect.call(manager, "interruptBind", true)
    }
}
