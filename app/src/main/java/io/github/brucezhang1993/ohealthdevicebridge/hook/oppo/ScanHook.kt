package io.github.brucezhang1993.ohealthdevicebridge.hook.oppo

import android.app.Activity
import android.os.Handler
import android.os.Looper
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import io.github.brucezhang1993.ohealthdevicebridge.BridgeLog
import io.github.brucezhang1993.ohealthdevicebridge.device.DeviceRegistry
import io.github.brucezhang1993.ohealthdevicebridge.device.xiaomi.MiScaleDriver
import io.github.brucezhang1993.ohealthdevicebridge.device.xiaomi.XiaomiModels
import io.github.brucezhang1993.ohealthdevicebridge.store.ScalePrefs
import java.lang.reflect.Field

/**
 * Route B, stages 2+3: make OPPO Health's native "add body-fat scale" flow actually find and bind
 * a Xiaomi scale.
 *
 * Why this is needed (6.10.13):
 *  - `st/a.b(model)` maps every model except five Boohee ones to "lifesense". Our injected model
 *    therefore selects the LIFESENSE binder (`je/t.c`), whose Rx scan errors out immediately ->
 *    "配对失败" with no visible search.
 *  - The BOOHEE binder (`boohee/y.c`) filters results by the injected model in `boohee/x.a`, so
 *    even a real device would be discarded.
 *
 * What we do: claim the pairing page for our models, force the BOOHEE vendor, replace both
 * vendor binders' search/bind methods with our own BLE scan, and feed OPPO's own callbacks so the
 * native UI (searching -> found -> pairing -> success) is preserved end to end.
 * No MAC is hardcoded: the address always comes from the BLE scan result.
 */
object ScanHook {
    private const val ACTIVITY = "com.heytap.device.ui.weight.BodyFatScaleBindActivity"
    private const val VIEW_MODEL = "com.heytap.device.ui.weight.BodyFatScaleBindViewModel"
    private const val VENDOR_RESOLVER = "st.a"
    private const val HUB = "com.heytap.device.ui.weight.scale.i"
    private const val BINDER_BOOHEE = "com.heytap.device.ui.weight.scale.boohee.y"
    private const val BINDER_LIFESENSE = "je.t"
    private const val NATIVE_CALLBACK = "com.heytap.device.ui.weight.scale.boohee.x"
    private const val DEVICE = "com.heytap.device.ui.weight.scale.BindableScaleDevice"
    private const val VENDOR_ENUM = "com.heytap.device.ui.weight.scale.ScaleVendor"

    private const val SEARCH_TIMEOUT_MS = 25_000L
    private const val BIND_DELAY_MS = 1_200L

    private val main = Handler(Looper.getMainLooper())

    @Volatile private var bridgeActive = false
    @Volatile private var model = XiaomiModels.V1_BODY
    @Volatile private var displayName = "小米体脂秤（一代）"
    @Volatile private var cancelScan: (() -> Unit)? = null
    @Volatile private var searchSeq = 0

    fun install(cl: ClassLoader) {
        // 1. Pairing page: identify our product from the intent and force the BOOHEE route.
        runCatching {
            val act = cl.loadClass(ACTIVITY)
            XposedBridge.hookAllMethods(act, "onCreate", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val a = param.thisObject as? Activity ?: return
                    // The pairing page is the first fully-initialised Context we can rely on.
                    ContextHolder.set(a)
                    val intent = a.intent
                    val type = intent?.getStringExtra("body_fat_device_type")
                    val title = intent?.getStringExtra("body_fat_title")
                    val name = intent?.getStringExtra("body_fat_device_name")
                    val vendor = intent?.getStringExtra("body_fat_scale_vendor")
                    BridgeLog.i("PAIR page type=$type title=$title name=$name vendor=$vendor")
                    if (ScaleCatalog.isOurs(type) || ScaleCatalog.isOurs(title) || ScaleCatalog.isOurs(name)) {
                        bridgeActive = true
                        val spec = ScaleCatalog.specFor(type ?: title ?: name)
                        model = spec.model
                        displayName = spec.name
                        intent?.putExtra("body_fat_scale_vendor", "boohee")
                        BridgeLog.i("PAIR bridge ON model=$model name=$displayName (vendor forced boohee)")
                    } else {
                        bridgeActive = false
                        BridgeLog.i("PAIR native flow (not our product)")
                    }
                }
            })
            XposedBridge.hookAllMethods(act, "onDestroy", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    cancelScan?.invoke(); cancelScan = null
                    bridgeActive = false
                }
            })
        }.onFailure { BridgeLog.e("SCAN activity hook failed", it as? Exception) }

        // 2. setTargetModel / setProductDisplayName: catch entry points that skip the activity hook.
        runCatching {
            val vm = cl.loadClass(VIEW_MODEL)
            XposedBridge.hookAllMethods(vm, "t", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val target = param.args.firstOrNull() as? String
                    BridgeLog.i("PAIR targetModel=$target")
                    if (ScaleCatalog.isOurs(target)) {
                        bridgeActive = true
                        val spec = ScaleCatalog.specFor(target)
                        model = spec.model; displayName = spec.name
                        BridgeLog.i("PAIR bridge ON via ViewModel model=$model")
                    }
                }
            })
        }.onFailure { BridgeLog.e("SCAN viewmodel hook failed", it as? Exception) }

        // 3. Global vendor resolution: our model must never be classified as LIFESENSE.
        runCatching {
            val resolver = cl.loadClass(VENDOR_RESOLVER)
            XposedBridge.hookAllMethods(resolver, "b", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val value = param.args.firstOrNull() as? String
                    if (ScaleCatalog.isOurs(value)) {
                        param.result = "boohee"
                        BridgeLog.i("PAIR vendor override boohee for $value")
                    }
                }
            })
        }.onFailure { BridgeLog.e("SCAN vendor resolver hook failed", it as? Exception) }

        // 4. Search hub: final funnel before any vendor binder runs.
        runCatching {
            val hub = cl.loadClass(HUB)
            XposedBridge.hookAllMethods(hub, "b", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val target = field(param.thisObject, "s")?.get(param.thisObject) as? String
                    val product = field(param.thisObject, "t")?.get(param.thisObject) as? String
                    BridgeLog.i("SCAN hub start target=$target product=$product active=$bridgeActive")
                    if (ScaleCatalog.isOurs(target) || ScaleCatalog.isOurs(product)) {
                        bridgeActive = true
                        val spec = ScaleCatalog.specFor(target ?: product)
                        model = spec.model; displayName = spec.name
                    }
                    if (bridgeActive) {
                        runCatching {
                            val boohee = cl.loadClass(VENDOR_ENUM).getField("BOOHEE").get(null)
                            field(param.thisObject, "r")?.set(param.thisObject, boohee)
                            BridgeLog.i("SCAN hub vendor forced BOOHEE")
                        }.onFailure { BridgeLog.e("SCAN vendor force failed", it as? Exception) }
                    }
                }
            })
            XposedBridge.hookAllMethods(hub, "d", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    BridgeLog.i("SCAN hub fail reason=${param.args.firstOrNull()} active=$bridgeActive")
                }
            })
        }.onFailure { BridgeLog.e("SCAN hub hook failed", it as? Exception) }

        // 5. Take over search + bind for BOTH vendor binders, with OPPO's own callbacks.
        for (binder in listOf(BINDER_BOOHEE, BINDER_LIFESENSE)) {
            runCatching {
                val cls = cl.loadClass(binder)
                XposedBridge.hookAllMethods(cls, "c", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!bridgeActive || param.args.size < 3) return
                        param.result = null // never let the vendor SDK scan (it cannot see a Mi scale)
                        BridgeLog.i("SCAN native $binder.c() bypassed")
                        startSearch(cl, param.args[0], param.args[1], param.args[2])
                    }
                })
                XposedBridge.hookAllMethods(cls, "d", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!bridgeActive || param.args.size < 2) return
                        param.result = null // never let the vendor SDK GATT-connect to a Mi scale
                        BridgeLog.i("BIND native $binder.d() bypassed")
                        doBind(param.args[0], param.args[1])
                    }
                })
                BridgeLog.i("SCAN binder hooks installed on $binder")
            }.onFailure { BridgeLog.e("SCAN binder hook failed: $binder", it as? Exception) }
        }

        // 6. Safety: the Boohee SDK timeout must not fire while our scan drives the UI.
        runCatching {
            val cb = cl.loadClass(NATIVE_CALLBACK)
            XposedBridge.hookAllMethods(cb, "onScanTimeout", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (bridgeActive) {
                        BridgeLog.i("SCAN suppressed native onScanTimeout")
                        param.result = null
                    }
                }
            })
            XposedBridge.hookAllMethods(cb, "a", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    BridgeLog.i("SCAN native callback a() arg=${param.args.firstOrNull()?.javaClass?.simpleName}")
                }
            })
        }.onFailure { BridgeLog.e("SCAN callback hook failed", it as? Exception) }

        BridgeLog.i("SCAN hook installed (models=${ScaleCatalog.all().joinToString { it.model }})")
    }

    // -----------------------------------------------------------------------------------------

    private fun startSearch(cl: ClassLoader, onDevice: Any?, onTimeout: Any?, onError: Any?) {
        val ctx = ContextHolder.get()
        if (ctx == null) {
            callFn1(onError, IllegalStateException("OPPO context unavailable"))
            return
        }
        cancelScan?.invoke()
        val seq = ++searchSeq
        val usedModel = model
        val usedName = displayName
        main.post {
            val driver = DeviceRegistry.byModel(usedModel) ?: MiScaleDriver(XiaomiModels.V1_BODY)
            BridgeLog.i("SCAN BLE start model=$usedModel timeout=${SEARCH_TIMEOUT_MS}ms (no MAC filter)")
            cancelScan = driver.scan(ctx, SEARCH_TIMEOUT_MS, { found ->
                if (seq != searchSeq) return@scan
                BridgeLog.i("SCAN discovered mac=${BridgeLog.maskedMac(found.mac)} model=${found.model}")
                val bindable = buildDevice(cl, found.mac, usedName, usedModel)
                if (bindable == null) {
                    callFn1(onError, IllegalStateException("BindableScaleDevice unavailable"))
                } else {
                    ScalePrefs.save(ctx, found.mac, usedModel)
                    callFn1(onDevice, bindable)
                    BridgeLog.i("SCAN reported to OPPO: name=$usedName mac=${BridgeLog.maskedMac(found.mac)}")
                }
                cancelScan = null
            }, {
                if (seq != searchSeq) return@scan
                BridgeLog.i("SCAN BLE timeout after ${SEARCH_TIMEOUT_MS}ms")
                cancelScan = null
                callFn0(onTimeout)
            }, { error ->
                if (seq != searchSeq) return@scan
                BridgeLog.e("SCAN BLE error: ${error.message}", error)
                cancelScan = null
                callFn1(onError, error)
            })
        }
    }

    private fun doBind(device: Any?, onSuccess: Any?) {
        val mac = field(device, "mac")?.get(device) as? String
        BridgeLog.i("BIND start mac=${mac?.let(BridgeLog::maskedMac) ?: "-"}")
        main.postDelayed({
            val ctx = ContextHolder.get()
            if (ctx != null && !mac.isNullOrBlank()) {
                ScalePrefs.save(ctx, mac, model)
                BridgeLog.i("BIND persisted mac=${BridgeLog.maskedMac(mac)} (measurement loop now targets it)")
            }
            callFn1(onSuccess, device)
            BridgeLog.i("BIND success reported to OPPO")
        }, BIND_DELAY_MS)
    }

    /** (vendor, mac, displayName, model, isConnectScale, rawRef) - order verified from smali. */
    private fun buildDevice(cl: ClassLoader, mac: String, name: String, model: String): Any? = runCatching {
        val devCls = cl.loadClass(DEVICE)
        val vendorCls = cl.loadClass(VENDOR_ENUM)
        val boohee = vendorCls.getField("BOOHEE").get(null)
        devCls.getConstructor(
            vendorCls, String::class.java, String::class.java, String::class.java,
            Boolean::class.javaPrimitiveType, Any::class.java
        ).newInstance(boohee, mac, name, model, false, null)
    }.onFailure { BridgeLog.e("SCAN buildDevice failed", it as? Exception) }.getOrNull()

    // Kotlin's Function0/Function1 objects are synthetic classes; invoke through reflection.
    private fun callFn1(fn: Any?, arg: Any?) {
        if (fn == null) return
        runCatching {
            val m = fn.javaClass.methods.firstOrNull {
                it.name == "invoke" && it.parameterCount == 1 && it.parameterTypes[0] == Any::class.java
            } ?: fn.javaClass.methods.firstOrNull { it.name == "invoke" && it.parameterCount == 1 }
            ?: throw NoSuchMethodException("invoke(Object)")
            m.isAccessible = true
            m.invoke(fn, arg)
        }.onFailure { BridgeLog.e("SCAN callback invoke(1) failed", it as? Exception) }
    }

    private fun callFn0(fn: Any?) {
        if (fn == null) return
        runCatching {
            val m = fn.javaClass.methods.firstOrNull { it.name == "invoke" && it.parameterCount == 0 }
                ?: throw NoSuchMethodException("invoke()")
            m.isAccessible = true
            m.invoke(fn)
        }.onFailure { BridgeLog.e("SCAN callback invoke(0) failed", it as? Exception) }
    }

    private fun field(target: Any?, name: String): Field? {
        if (target == null) return null
        var cls: Class<*>? = target.javaClass
        while (cls != null) {
            val f = runCatching { cls!!.getDeclaredField(name) }.getOrNull()
            if (f != null) { f.isAccessible = true; return f }
            cls = cls.superclass
        }
        return null
    }
}
