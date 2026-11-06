package io.github.brucezhang1993.ohealthdevicebridge.hook.oppo

import de.robv.android.xposed.*
import io.github.brucezhang1993.ohealthdevicebridge.BridgeLog
import io.github.brucezhang1993.ohealthdevicebridge.device.DeviceRegistry
import io.github.brucezhang1993.ohealthdevicebridge.store.BridgeBindingStore

object BooheeMeasureHook {
    fun install(cl: ClassLoader) {
        val clazz = XposedHelpers.findClass("com.heytap.device.ui.weight.scale.boohee.BHDeviceManager", cl)
        val hooks = XposedBridge.hookAllMethods(clazz, "startMeasureReceive", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val mac = param.args.getOrNull(1) as? String ?: return
                val context = OppoReflect.context() ?: return
                val account = try { OppoAccount.key(cl) } catch (_: Exception) { return }
                val model = BridgeBindingStore.model(context, account, mac) ?: return
                val driver = DeviceRegistry.byModel(model) ?: return
                param.result = null
                OppoReflect.postMain {
                    val listener = param.args[0]
                    try {
                        OppoAfuRuntime.stopAll()
                        BooheeBindHook.pauseNative(cl, param.thisObject)
                        val body = OppoBodyComposition(cl, OppoObjectFactory(cl))
                        body.prepare(param.thisObject, context, param.args.getOrNull(2) as? String)
                        val ticket = OppoAfuRuntime.Ticket(account, mac, model)
                        OppoAfuRuntime.measure = ticket
                        driver.measure(context, mac, body.currentUserProfile(param.thisObject),
                            { kg -> if (ticket.valid(cl)) OppoReflect.call(listener, "onProcessWeight", kg) },
                            { record -> if (ticket.valid(cl)) {
                                val scale = body.buildScaleModel(param.thisObject, mac, record, deviceModel = model)
                                OppoReflect.call(listener, "onLockWeight", record.weightKg, scale)
                            } },
                            { record -> if (ticket.valid(cl)) body.importHistory(param.thisObject, mac, record, model) },
                            { error -> if (ticket.valid(cl)) {
                                ticket.active = false
                                if (OppoAfuRuntime.measure === ticket) OppoAfuRuntime.measure = null
                                OppoReflect.call(listener, "onFail", error.message ?: "AFU measure failed")
                            } })
                    } catch (error: Exception) {
                        OppoAfuRuntime.stopMeasure()
                        BridgeLog.e("AFU measure preparation failed", error)
                        OppoReflect.callFirst(listener, listOf("onFail"), error.message ?: "AFU measure failed")
                    }
                }
            }
        })
        check(hooks.isNotEmpty())
        HookInstallState.measure = true
    }
}
