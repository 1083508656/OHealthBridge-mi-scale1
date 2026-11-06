package io.github.brucezhang1993.ohealthdevicebridge.hook.oppo

import de.robv.android.xposed.*
import io.github.brucezhang1993.ohealthdevicebridge.*
import io.github.brucezhang1993.ohealthdevicebridge.device.xiaomi.XiaomiModels
import java.lang.reflect.Method

object ProductCatalogHook {
    private const val FRAGMENT = "com.heytap.health.watchpair.watchconnect.pair.producttype.ProductCategorFragment"
    private const val PRODUCT_CATEGORY = "com.heytap.health.devicemanager.processor.cloudaccess.response.ProductCategoryRsp\$ProductCategory"
    private const val PRODUCT = "com.heytap.health.devicemanager.processor.cloudaccess.response.ProductCategoryRsp\$Product"
    private val known = setOf("3102", "3105", "3107", "3501", "3504")
    fun install(cl: ClassLoader) {
        check(HookInstallState.coreReady)
        val fragment = XposedHelpers.findClass(FRAGMENT, cl)
        val category = XposedHelpers.findClass(PRODUCT_CATEGORY, cl)
        val target = findRenderMethod(fragment, category) ?: error("Product category render method not found")
        XposedBridge.hookMethod(target, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) { val c = param.args.firstOrNull(category::isInstance) ?: return; inject(c, cl) }
        })
        HookInstallState.catalog = true
        BridgeLog.i("product catalog hook installed at ${target.name}")
    }
    private fun findRenderMethod(fragment: Class<*>, category: Class<*>): Method? = fragment.declaredMethods.firstOrNull { it.name == "showScreen\$lambda\$4\$lambda\$3" }
        ?: fragment.declaredMethods.firstOrNull { it.name.contains("showScreen") && it.parameterTypes.any(category::isAssignableFrom) }
    @Suppress("UNCHECKED_CAST") private fun inject(category: Any, cl: ClassLoader) {
        val original = OppoReflect.read(category, "getModelList", "modelList") as? List<Any> ?: return
        val template = original.firstOrNull { modelOf(it) in known } ?: return
        val existing = original.map(::modelOf).toSet()
        val names = linkedMapOf(BridgeConstants.AFU_MODEL to BridgeConstants.AFU_DISPLAY_NAME).apply { putAll(XiaomiModels.names) }
        val replacement = ArrayList(original)
        for ((model, name) in names) {
            if (model in existing) continue
            val product = XposedHelpers.newInstance(XposedHelpers.findClass(PRODUCT, cl))
            OppoReflect.set(product, "deviceType", (OppoReflect.read(template, "getDeviceType", "deviceType") as? Number)?.toInt() ?: 0)
            OppoReflect.set(product, "deviceName", name)
            OppoReflect.set(product, "model", model)
            OppoReflect.set(product, "thirdPartyModel", model)
            OppoReflect.set(product, "slogan", model)
            OppoReflect.set(product, "imageUrl", OppoReflect.read(template, "getImageUrl", "imageUrl"))
            runCatching { OppoReflect.set(product, "imageList", emptyList<Any>()) }.onFailure { if (it is VirtualMachineError) throw it }
            replacement += product
        }
        OppoReflect.set(category, "modelList", replacement)
    }
    private fun modelOf(product: Any) = OppoReflect.readString(product, "getModel", "model")
}
