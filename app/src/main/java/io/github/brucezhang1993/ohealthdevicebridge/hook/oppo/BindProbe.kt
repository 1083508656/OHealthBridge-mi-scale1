package io.github.brucezhang1993.ohealthdevicebridge.hook.oppo

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import io.github.brucezhang1993.ohealthdevicebridge.BridgeLog

/**
 * Route B step 3 probe: log which manager method OPPO uses for connect/bind so the real bind
 * entry point can be identified from a single device test.
 */
object BindProbe {
    fun install(cl: ClassLoader) {
        runCatching {
            val mgr = cl.loadClass("com.heytap.device.ui.weight.scale.boohee.h")
            var n = 0
            for (name in listOf("l", "p", "r", "m", "d", "j", "k")) {
                XposedBridge.hookAllMethods(mgr, name, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val args = param.args.joinToString(",") { it?.javaClass?.simpleName ?: "null" }
                        BridgeLog.i("BIND probe h.$name($args)")
                    }
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val err = param.throwable
                        if (err != null) BridgeLog.e("BIND probe h.${param.method.name} threw ${err.javaClass.simpleName}: ${err.message}")
                    }
                })
                n++
            }
            BridgeLog.i("BIND probe installed on $n manager methods")
        }.onFailure { BridgeLog.e("BIND probe failed", it as? Exception) }
    }
}
