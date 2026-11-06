package io.github.brucezhang1993.ohealthdevicebridge.hook.oppo

import de.robv.android.xposed.*
import io.github.brucezhang1993.ohealthdevicebridge.BridgeLog
import io.github.brucezhang1993.ohealthdevicebridge.device.DeviceRegistry
import io.github.brucezhang1993.ohealthdevicebridge.store.BridgeBindingStore

object BooheeDeviceOverlayHook {
    fun install(cl: ClassLoader) {
        // 6.10.13+ obfuscated BooheeDeviceLoader away: its convertBoohee body survives as the
        // RxJava mapper com.heytap.device.ui.weight.scale.boohee.k#apply(List)->List, which
        // BodyFatScaleServiceImpl feeds into Observable.map(...). Hooking it keeps the native
        // device list but appends our scale.
        val hooked = runCatching {
            val clazz = XposedHelpers.findClass("com.heytap.device.ui.weight.scale.boohee.k", cl)
            val hooks = XposedBridge.hookAllMethods(clazz, "apply", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) = overlay(cl, param)
            })
            check(hooks.isNotEmpty())
            BridgeLog.i("overlay hook installed on boohee.k#apply (6.10.14)")
        }.isSuccess
        if (hooked) return

        // Legacy 6.9.37 entry.
        runCatching {
            val clazz = XposedHelpers.findClass("com.heytap.device.ui.weight.scale.boohee.BooheeDeviceLoader", cl)
            val hooks = XposedBridge.hookAllMethods(clazz, "convertBoohee", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) = overlay(cl, param)
            })
            check(hooks.isNotEmpty())
            BridgeLog.i("overlay hook installed on BooheeDeviceLoader#convertBoohee")
        }.onFailure { BridgeLog.e("overlay legacy hook failed", it as? Exception) }
    }

    /**
     * Device-tab overlay: DeviceTabAdapterController.g() hands the whole device list to the UI.
     * It is not obfuscated, so appending our scale here makes it appear in the device switcher.
     */
    fun installDeviceTab(cl: ClassLoader) {
        runCatching {
            val clazz = XposedHelpers.findClass("com.heytap.health.device.tab.controller.DeviceTabAdapterController", cl)
            val hooks = XposedBridge.hookAllMethods(clazz, "g", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.hasThrowable()) return
                    val original = param.result as? List<*> ?: return
                    val macs = original.filterNotNull().mapNotNull { OppoReflect.readString(it, "getMac", "mac", "deviceUniqueId", "getDeviceUniqueId") }
                        .map { it.uppercase() }.toSet()
                    val context = OppoReflect.context() ?: return
                    val account = runCatching { OppoAccount.key(cl) }.getOrElse { "local" }
                    val bound = BridgeBindingStore.devices(context, account).filterKeys { it !in macs }
                    if (bound.isEmpty()) return
                    val items = ArrayList<Any>(original.filterNotNull())
                    val factory = OppoObjectFactory(cl)
                    for ((mac, model) in bound) {
                        items.add(factory.createUserDevice(mac, DeviceRegistry.byModel(model)?.isConnected(mac) == true, model))
                    }
                    param.result = items
                    BridgeLog.i("deviceTab appended ${bound.size} device(s), total=${items.size}")
                }
            })
            check(hooks.isNotEmpty())
            BridgeLog.i("deviceTab hook installed on DeviceTabAdapterController#g")
        }.onFailure { BridgeLog.e("deviceTab hook failed", it as? Exception) }
    }

    /**
     * Device switcher dialog (showSelectDeviceDialogBottom -> obfuscated method `p(Context)`):
     * it snapshots controller field `y` (a LockSortTreeSet of UserDeviceInfo) into the
     * SelectDeviceAdapter. Injecting our scale into that set right before makes it show up as a
     * switchable device, i.e. "paired -> appears in the device list".
     */
    fun installSelectDevice(cl: ClassLoader) {
        runCatching {
            val clazz = XposedHelpers.findClass("com.heytap.health.device.tab.controller.DeviceTabAdapterController", cl)
            XposedBridge.hookAllMethods(clazz, "p", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val controller = param.thisObject ?: return
                    val set = runCatching { XposedHelpers.getObjectField(controller, "y") }.getOrNull()
                        ?.let { it as? MutableCollection<Any> } ?: return
                    val context = OppoReflect.context() ?: return
                    val account = runCatching { OppoAccount.key(cl) }.getOrElse { "local" }
                    val bound = BridgeBindingStore.devices(context, account)
                    if (bound.isEmpty()) return
                    val existing = set.mapNotNull { macOfElement(it) }.map { normalizeMac(it) }.toSet()
                    BridgeLog.i("selectDevice existing=${existing.size} bound=${bound.size} setSize=${set.size}")
                    val factory = OppoObjectFactory(cl)
                    val beanCls = cl.loadClass(OppoObjectFactory.BODY_FAT_BEAN)
                    val wrapperCls = cl.loadClass(OppoObjectFactory.BODY_FAT_WRAPPER)
                    var added = 0
                    for ((mac, model) in bound) {
                        if (normalizeMac(mac) in existing) continue
                        runCatching {
                            // Wrap as wq.d(tt/a) — the body-fat-scale wrapper. wq.c(UserDeviceInfo)
                            // is the watch wrapper and routes the detail page to the watch module.
                            val bean = factory.createBodyFatScaleBean(mac, model)
                            val wrapped = wrapperCls.getConstructor(beanCls).newInstance(bean)
                            set.add(wrapped)
                            added++
                        }.onFailure { BridgeLog.e("selectDevice add failed", it as? Exception) }
                    }
                    if (added > 0) BridgeLog.i("selectDevice injected $added scale(s)")
                }
            })
            BridgeLog.i("selectDevice hook installed on DeviceTabAdapterController#p")
        }.onFailure { BridgeLog.e("selectDevice hook failed", it as? Exception) }
    }

    /**
     * Reads the MAC out of whatever the device switcher stores: a plain UserDeviceInfo, the
     * watch wrapper wq.c (inner UserDeviceInfo), or the body-fat-scale wrapper wq.d (inner tt/a,
     * whose `d()` returns the mac). Field `a` = the inner bean, field `b` = the mac string.
     */
    private fun macOfElement(el: Any): String? {
        OppoReflect.readString(el, "getMac", "mac", "deviceUniqueId", "getDeviceUniqueId")?.let { return it }
        val inner = runCatching { XposedHelpers.getObjectField(el, "a") }.getOrNull()
        if (inner != null && inner !== el) {
            OppoReflect.readString(inner, "getMac", "mac", "deviceUniqueId", "getDeviceUniqueId")?.let { return it }
            // tt/a body-fat-scale bean: d() is the mac getter.
            OppoReflect.readString(inner, "d")?.let { return it }
        }
        return runCatching { XposedHelpers.getObjectField(el, "b") as? String }.getOrNull()
    }

    /** MAC comparison key: drop colons so formatted (AA:BB:..) and raw (AABB..) forms match. */
    private fun normalizeMac(mac: String) = mac.replace(":", "").uppercase()

    private fun overlay(cl: ClassLoader, param: XC_MethodHook.MethodHookParam) {
        if (param.hasThrowable()) return
        val context = OppoReflect.context()
        if (context == null) { BridgeLog.i("overlay: no context"); return }
        // AccountHelper is obfuscated away in 6.10.13; fall back to a local key so the overlay
        // still works for the single signed-in user.
        val account = runCatching { OppoAccount.key(cl) }.getOrElse { "local" }
        val original = param.result as? List<*>
        if (original == null) { BridgeLog.i("overlay: result=${param.result?.javaClass?.simpleName}"); return }
        for (item in original.filterNotNull()) {
            val model = OppoReflect.readString(item, "getModel", "model") ?: continue
            if (DeviceRegistry.byModel(model) != null) {
                OppoReflect.readString(item, "getMac", "mac")?.let { BridgeBindingStore.add(context, account, it, model) }
            }
        }
        val existing = original.filterNotNull().mapNotNull { OppoReflect.readString(it, "getMac", "mac") }
            .map(io.github.brucezhang1993.ohealthdevicebridge.store.AfuBindingStore::normalize).toSet()
        val bound = BridgeBindingStore.devices(context, account).filterKeys { it !in existing }
        BridgeLog.i("overlay: original=${original.size} existing=${existing.size} bound=${bound.size}")
        if (bound.isEmpty()) return
        val items = ArrayList<Any>(original.filterNotNull())
        val factory = OppoObjectFactory(cl)
        for ((mac, model) in bound) {
            items.add(factory.createWeightDevice(mac, DeviceRegistry.byModel(model)?.isConnected(mac) == true, model))
        }
        param.result = items
        BridgeLog.i("overlay appended ${bound.size} device(s), total=${items.size}")
    }
}
