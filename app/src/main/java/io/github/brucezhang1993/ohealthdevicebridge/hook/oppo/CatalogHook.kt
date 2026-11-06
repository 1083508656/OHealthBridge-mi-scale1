package io.github.brucezhang1993.ohealthdevicebridge.hook.oppo

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import io.github.brucezhang1993.ohealthdevicebridge.BridgeLog

/**
 * Route B stage 1 - catalog injection.
 * OPPO's "add device" catalog comes from a cloud response (ProductCategoryRsp). We append one
 * product per spec in [ScaleCatalog] to the body-fat-scale category, cloning deviceType/imagery
 * from an existing entry so the list renders correctly. Adding a scale later only needs a new
 * entry in ScaleCatalog (or oh_scales.json), not a change here.
 */
object CatalogHook {
    private const val RSP = "com.heytap.health.devicemanager.processor.cloudaccess.response.ProductCategoryRsp"
    private const val CATEGORY = "$RSP\$ProductCategory"
    private const val PRODUCT = "$RSP\$Product"

    const val DEVICE_NAME = "小米体脂秤（一代）"
    const val MODEL = "XIAOMI-MI-SCALE-V1-BODY"
    const val SLOGAN = "支持小米体脂秤一代"

    @Volatile private var injected = false

    fun install(cl: ClassLoader) {
        runCatching {
            val base = cl.loadClass("com.heytap.health.network.core.BaseResponse")
            XposedBridge.hookAllMethods(base, "getBody", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val body = param.result ?: return
                    if (body.javaClass.name != RSP) return
                    runCatching { inject(cl, body) }
                        .onFailure { BridgeLog.e("CATALOG inject failed", it as? Exception) }
                }
            })
            BridgeLog.i("CATALOG hook installed on BaseResponse.getBody")
        }.onFailure { BridgeLog.e("CATALOG hook failed", it as? Exception) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun inject(cl: ClassLoader, rsp: Any) {
        val list = rsp.javaClass.getField("modelsList").get(rsp) as? MutableList<Any> ?: run {
            BridgeLog.e("CATALOG modelsList null/immutable")
            return
        }
        val catCls = cl.loadClass(CATEGORY)
        val prodCls = cl.loadClass(PRODUCT)

        var target: Any? = null
        var sample: Any? = null
        for (c in list) {
            val name = catCls.getField("deviceTypeName").get(c) as? String ?: ""
            val models = catCls.getField("modelList").get(c) as? List<Any> ?: continue
            if (models.isNotEmpty() && (name.contains("体脂") || name.contains("体重") || name.contains("秤"))) {
                target = c; sample = models.first(); break
            }
        }
        if (target == null) {
            for (c in list) {
                val models = catCls.getField("modelList").get(c) as? List<Any> ?: continue
                if (models.isNotEmpty()) { target = c; sample = models.first(); break }
            }
        }
        if (target == null || sample == null) { BridgeLog.e("CATALOG no category/sample"); return }

        val models = catCls.getField("modelList").get(target) as? MutableList<Any> ?: return
        val present = models.mapNotNull { prodCls.getField("model").get(it) as? String }.toSet()

        for (spec in ScaleCatalog.all()) {
            if (spec.model in present) continue
            val product = prodCls.getDeclaredConstructor().newInstance()
            prodCls.getField("deviceType").set(product, prodCls.getField("deviceType").get(sample))
            prodCls.getField("imageList").set(product, prodCls.getField("imageList").get(sample))
            prodCls.getField("imageUrl").set(product, prodCls.getField("imageUrl").get(sample))
            prodCls.getField("deviceName").set(product, spec.name)
            prodCls.getField("model").set(product, spec.model)
            prodCls.getField("thirdPartyModel").set(product, spec.model)
            prodCls.getField("slogan").set(product, spec.slogan)
            models.add(product)
            BridgeLog.i("CATALOG added '${spec.name}' model=${spec.model} size=${models.size}")
        }

        if (!injected) {
            injected = true
            BridgeLog.i("CATALOG injected into category='${catCls.getField("deviceTypeName").get(target)}' size=${models.size}")
        }
    }
}
