package io.github.brucezhang1993.ohealthdevicebridge.hook.oppo

import de.robv.android.xposed.*
import io.github.brucezhang1993.ohealthdevicebridge.store.BridgeBindingStore
import io.github.brucezhang1993.ohealthdevicebridge.device.xiaomi.XiaomiModels

internal object OppoAfuLifecycleHook {
    fun install(cl: ClassLoader) {
        val binder = XposedHelpers.findClass("com.heytap.device.ui.weight.scale.boohee.BooheeScaleBinder", cl)
        for (name in listOf("stopSearch", "stopSearchForPair", "release")) hook(binder, name) { param ->
            OppoReflect.postMain { OppoAfuRuntime.scans.remove(param.thisObject)?.cancel() }
        }
        hook(binder, "interruptPair") { OppoReflect.postMain { OppoAfuRuntime.stopBind() } }
        hook(binder, "release") { OppoReflect.postMain { OppoAfuRuntime.stopBind() } }
        val manager = XposedHelpers.findClass("com.heytap.device.ui.weight.scale.boohee.BHDeviceManager", cl)
        hook(manager, "stopMeasureReceive") { OppoReflect.postMain { OppoAfuRuntime.stopMeasure() } }
        hook(manager, "interruptBind") { OppoReflect.postMain { OppoAfuRuntime.stopBind() } }
        val coordinator = XposedHelpers.findClass("com.heytap.device.ui.weight.scale.WeightScaleKeepAliveCoordinator", cl)
        hook(coordinator, "releaseForUnbind") { param ->
            val mac = param.args.firstOrNull() as? String ?: return@hook
            OppoReflect.postMain { remove(cl, mac) }
        }
        val capability = XposedHelpers.findClass(OppoBodyComposition.CAPABILITY, cl)
        hook(capability, "remove") { param ->
            val mac = param.args.firstOrNull() as? String ?: return@hook
            OppoReflect.postMain { remove(cl, mac) }
        }
        val accountHelper = XposedHelpers.findClass("com.heytap.health.account.AccountHelper", cl)
        val observed = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>())
        check(XposedBridge.hookAllMethods(accountHelper, "getAccountManager", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val account = param.result ?: return
                synchronized(observed) {
                    if (!observed.add(account)) return
                    val listenerType = cl.loadClass("com.heytap.health.account.listener.ILoginListener")
                    val listener = java.lang.reflect.Proxy.newProxyInstance(cl, arrayOf(listenerType)) { proxy, method, args ->
                        when (method.name) {
                            "onLoginSuccess", "onLogout" -> { OppoReflect.postMain { OppoAfuRuntime.stopAll() }; null }
                            "hashCode" -> System.identityHashCode(proxy)
                            "equals" -> proxy === args?.firstOrNull()
                            "toString" -> "AFU account lifecycle listener"
                            else -> null
                        }
                    }
                    OppoReflect.call(account, "addLoginListener", listener)
                }
            }
        }).isNotEmpty())
        HookInstallState.lifecycle = true
    }
    private fun remove(cl: ClassLoader, mac: String) {
        val context = OppoReflect.context() ?: return
        val account = try { OppoAccount.key(cl) } catch (_: Exception) { return }
        val model = BridgeBindingStore.model(context, account, mac) ?: return
        OppoAfuRuntime.stopAll()
        BridgeBindingStore.remove(context, account, mac)
    }
    internal fun hook(clazz: Class<*>, name: String, before: (XC_MethodHook.MethodHookParam) -> Unit) {
        check(XposedBridge.hookAllMethods(clazz, name, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) { before(param) }
        }).isNotEmpty()) { "Missing lifecycle method ${clazz.name}.$name" }
    }
}
