package io.github.brucezhang1993.ohealthdevicebridge.hook.oppo

import android.content.Context
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import io.github.brucezhang1993.ohealthdevicebridge.BridgeLog

/**
 * Production entry for the 6.10.14 integration.
 *  - Captures an application context once.
 *  - Installs the catalog / pairing / bind / measurement hooks in the UI process.
 *  - Injects the bound Mi scale into the device list / switcher as a native body-fat scale.
 *  - Starts the background measurement loop in :SportDaemonService (where the DB engine lives).
 */
object RecordInjector {

    @Volatile private var armed = false
    @Volatile private var installed = false

    fun install(cl: ClassLoader) {
        if (installed) return
        installed = true
        runCatching {
            XposedBridge.hookAllMethods(android.app.Application::class.java, "attach", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (armed) return
                    val ctx = param.args.firstOrNull { it is Context } as? Context ?: return
                    armed = true
                    ContextHolder.set(ctx)
                    CatalogHook.install(cl)
                    ScanHook.install(cl)
                    BindProbe.install(cl)
                    // Device-list overlay: make the bound Mi scale show up next to other devices.
                    runCatching { BooheeDeviceOverlayHook.install(cl) }.onFailure {
                        if (it is VirtualMachineError) throw it
                        BridgeLog.e("overlay hook failed", it as? Exception)
                    }
                    runCatching { BooheeDeviceOverlayHook.installDeviceTab(cl) }.onFailure {
                        if (it is VirtualMachineError) throw it
                        BridgeLog.e("deviceTab hook failed", it as? Exception)
                    }
                    runCatching { BooheeDeviceOverlayHook.installSelectDevice(cl) }.onFailure {
                        if (it is VirtualMachineError) throw it
                        BridgeLog.e("selectDevice hook failed", it as? Exception)
                    }
                    // Mirror live readings into OPPO's own measure page (BodyFatMeasureActivity).
                    runCatching { BodyFatMeasureHook.install(cl, ctx) }.onFailure {
                        if (it is VirtualMachineError) throw it
                        BridgeLog.e("measure hook failed", it as? Exception)
                    }
                    val proc = runCatching { android.app.Application.getProcessName() }.getOrNull()
                    if (proc == "com.heytap.health:SportDaemonService") {
                        XiaomiAutoRecord.start(cl, ctx)
                        BridgeLog.i("auto-record armed in $proc")
                    }
                }
            })
            // Application.attach() may run before getApplicationContext() exists; onCreate() is
            // the first point where the Application context is definitely usable.
            XposedBridge.hookAllMethods(android.app.Application::class.java, "onCreate", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val app = param.thisObject as? Context ?: return
                    ContextHolder.set(app)
                }
            })
            BridgeLog.i("entry hook installed on Application.attach")
        }.onFailure {
            installed = false
            BridgeLog.e("entry hook failed", it as? Exception)
        }
    }
}
