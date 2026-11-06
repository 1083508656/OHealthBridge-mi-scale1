package io.github.brucezhang1993.ohealthdevicebridge.hook.oppo

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import io.github.brucezhang1993.ohealthdevicebridge.BridgeLog

/**
 * Feeds the module's live scale readings into OPPO's measure page.
 *
 * OPPO's body-fat measure page (BodyFatMeasureActivity + BodyFatMeasureViewModel) subscribes to
 * the Lifesense BLE stack. A Xiaomi scale never speaks that protocol, so the page sits at
 * "准备测量" forever and the user sees no reaction.
 *
 * BodyFatMeasureViewModel.f(WeightData) is the UI sink (verified on 6.10.14):
 *     q = true; disposes pending; liveData.postValue(new n(weightData.getWeight(), true))
 * so building a WeightData and calling f() renders the live weight exactly like a native scale.
 *
 * WeightData (com.lifesense.device.scale.device.dto.receive.WeightData) has a no-arg ctor plus
 * setWeight(double) / setResistance50k(double) / setResistance5k(double).
 */
object BodyFatMeasureHook {
    private const val ACTIVITY = "com.heytap.device.ui.weight.BodyFatMeasureActivity"
    private const val VIEW_MODEL = "com.heytap.device.ui.weight.BodyFatMeasureViewModel"
    private const val WEIGHT_DATA = "com.lifesense.device.scale.device.dto.receive.WeightData"

    /**
     * The BLE loop lives in :SportDaemonService while the measure page lives in the main process,
     * so the ViewModel reference and the readings cannot simply be shared through a static field.
     * Readings travel as a package-scoped broadcast and are replayed onto the ViewModel here.
     */
    private const val ACTION_LIVE = "io.github.brucezhang1993.ohealthdevicebridge.MEASURE_LIVE"
    private const val EXTRA_WEIGHT = "weight"
    private const val EXTRA_RESISTANCE = "resistance"

    /** ViewModel of the currently open measure page (main process only), or null. */
    @Volatile private var viewModel: Any? = null

    @Volatile private var receiverRegistered = false

    fun install(cl: ClassLoader, appContext: android.content.Context? = null) {
        runCatching {
            val act = XposedHelpers.findClass(ACTIVITY, cl)
            XposedBridge.hookAllMethods(act, "onCreate", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val vm = runCatching { XposedHelpers.getObjectField(param.thisObject, "k") }.getOrNull()
                    if (vm != null) {
                        viewModel = vm
                        BridgeLog.i("measure page opened; viewModel=$VIEW_MODEL captured")
                    }
                }
            })
            XposedBridge.hookAllMethods(act, "onDestroy", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (viewModel != null) BridgeLog.i("measure page closed")
                    viewModel = null
                }
            })
            registerReceiver(cl, appContext)
            BridgeLog.i("measure hook installed on BodyFatMeasureActivity")
        }.onFailure { BridgeLog.e("measure hook failed", it as? Exception) }
    }

    /** True while OPPO's measure page is in the foreground (main process only). */
    fun isMeasuring(): Boolean = viewModel != null

    /** Listens for readings broadcast by the daemon process and renders them on the page. */
    private fun registerReceiver(cl: ClassLoader, appContext: android.content.Context?) {
        if (receiverRegistered) return
        // AndroidAppHelper.currentApplication() is not bound yet while Application.attach runs,
        // so prefer the context handed over by RecordInjector.
        val ctx = appContext ?: OppoReflect.context()
        if (ctx == null) { BridgeLog.i("measure receiver: no context yet"); return }
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: android.content.Context?, intent: android.content.Intent?) {
                if (intent == null) return
                val w = intent.getDoubleExtra(EXTRA_WEIGHT, 0.0)
                val r = intent.getDoubleExtra(EXTRA_RESISTANCE, 0.0)
                if (w > 0) pushLiveLocal(cl, w, r)
            }
        }
        runCatching {
            val filter = android.content.IntentFilter(ACTION_LIVE)
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                ctx.registerReceiver(receiver, filter, android.content.Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                ctx.registerReceiver(receiver, filter)
            }
            receiverRegistered = true
            BridgeLog.i("measure receiver registered")
        }.onFailure { BridgeLog.e("measure receiver failed", it as? Exception) }
    }

    /**
     * Send a live reading from the BLE process to the UI process. Safe to call from either side;
     * harmless when no measure page is open (the receiver drops it because viewModel is null).
     */
    fun broadcastLive(ctx: android.content.Context, weightKg: Double, resistanceOhm: Double) {
        runCatching {
            val intent = android.content.Intent(ACTION_LIVE).apply {
                setPackage("com.heytap.health")
                putExtra(EXTRA_WEIGHT, weightKg)
                putExtra(EXTRA_RESISTANCE, resistanceOhm)
            }
            ctx.sendBroadcast(intent)
        }
    }

    /** Push one reading straight onto the ViewModel; must run in the process that owns the page. */
    private fun pushLiveLocal(cl: ClassLoader, weightKg: Double, resistanceOhm: Double) {
        val vm = viewModel ?: return
        runCatching {
            val wdCls = cl.loadClass(WEIGHT_DATA)
            val wd = wdCls.getConstructor().newInstance()
            wdCls.getMethod("setWeight", Double::class.javaPrimitiveType).invoke(wd, weightKg)
            if (resistanceOhm > 0) {
                wdCls.getMethod("setResistance50k", Double::class.javaPrimitiveType).invoke(wd, resistanceOhm)
                runCatching { wdCls.getMethod("setResistance5k", Double::class.javaPrimitiveType).invoke(wd, resistanceOhm) }
            }
            vm.javaClass.getMethod("f", wdCls).invoke(vm, wd)
            BridgeLog.i("measure page live %.2f kg".format(weightKg))
        }.onFailure { BridgeLog.e("measure push failed", it as? Exception) }
    }
}
