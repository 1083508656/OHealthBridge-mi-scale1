package io.github.brucezhang1993.ohealthdevicebridge.hook.oppo

import android.content.Context
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import io.github.brucezhang1993.ohealthdevicebridge.BridgeLog

/**
 * Production entry for the 6.10.13 integration.
 *  - Captures an application context once.
 *  - Installs the catalog / pairing / bind / measurement hooks in the UI process.
 *  - Starts the background measurement loop in :SportDaemonService (where the DB engine lives).
 *  - Arms the optional data-export hook (gated behind a marker file).
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
                    DbBridge.install(cl)
                    CatalogHook.install(cl)
                    ScanHook.install(cl)
                    BindProbe.install(cl)
                    val proc = runCatching { android.app.Application.getProcessName() }.getOrNull()
                    if (proc == "com.heytap.health:SportDaemonService") {
                        XiaomiAutoRecord.start(cl, ctx)
                        Thread({
                            runCatching {
                                if (java.io.File("/sdcard/Download/oh_fix_members.flag").exists()) {
                                    Thread.sleep(4_000)
                                    MemberFix.run(cl, ctx)
                                }
                            }.onFailure { BridgeLog.e("memberfix trigger failed", it as? Exception) }
                        }, "oh-memfix").start()
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
