package io.github.brucezhang1993.ohealthdevicebridge

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.brucezhang1993.ohealthdevicebridge.hook.oppo.RecordInjector

/** Classic Xposed entry (assets/xposed_init). Kept in sync with the modern entry. */
class LegacyEntry : IXposedHookLoadPackage {
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != BridgeConstants.TARGET_PACKAGE) return
        BridgeLog.i("loading legacy Xposed entry for ${lpparam.packageName} target=${BridgeConstants.TARGET_VERSION}")
        runCatching {
            RecordInjector.install(lpparam.classLoader)
        }.onFailure {
            if (it is VirtualMachineError) throw it
            BridgeLog.e("failed to install OPPO Health hooks (legacy entry)", it)
        }
    }
}
