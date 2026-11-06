package io.github.brucezhang1993.ohealthdevicebridge

import io.github.brucezhang1993.ohealthdevicebridge.hook.oppo.RecordInjector
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

/** Modern libxposed (API 101) entry. The whole bridge is installed by [RecordInjector]. */
class HookEntry : XposedModule() {
    private var installed = false

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        if (param.packageName != BridgeConstants.TARGET_PACKAGE || installed) return
        installed = true
        BridgeLog.i("loading modern Xposed entry for ${param.packageName} target=${BridgeConstants.TARGET_VERSION}")
        runCatching {
            RecordInjector.install(param.classLoader)
        }.onFailure {
            if (it is VirtualMachineError) throw it
            installed = false
            BridgeLog.e("failed to install OPPO Health hooks", it)
        }
    }
}
