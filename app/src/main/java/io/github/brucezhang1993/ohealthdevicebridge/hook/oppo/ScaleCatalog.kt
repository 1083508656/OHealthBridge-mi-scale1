package io.github.brucezhang1993.ohealthdevicebridge.hook.oppo

import android.content.Context
import io.github.brucezhang1993.ohealthdevicebridge.BridgeConstants
import io.github.brucezhang1993.ohealthdevicebridge.BridgeLog
import io.github.brucezhang1993.ohealthdevicebridge.device.DeviceRegistry
import io.github.brucezhang1993.ohealthdevicebridge.device.xiaomi.XiaomiModels
import org.json.JSONArray
import java.io.File

/**
 * Single source of truth for "which scales does the bridge support".
 *
 * Adding a scale later needs no changes to the pairing hooks: append a Spec here (or drop
 * `oh_scales.json` into the app's external files dir with
 * `[{"model":"...","name":"...","slogan":"..."}]`). The same list drives the injected product
 * catalog, the pairing-page detection, the BLE scan and the background measurement loop.
 * Only models that have a driver in [DeviceRegistry] are exposed.
 */
object ScaleCatalog {
    data class Spec(val model: String, val name: String, val slogan: String)

    private const val CONFIG_FILE = "oh_scales.json"

    private val builtIn = listOf(
        Spec(XiaomiModels.V1_BODY, "小米体脂秤（一代）", "支持小米体脂秤一代"),
        Spec(XiaomiModels.V1, "小米体重秤（一代）", "支持小米体重秤一代"),
        Spec(XiaomiModels.V2, "小米体脂秤（二代）", "支持小米体脂秤二代"),
        Spec(BridgeConstants.AFU_MODEL, "薄荷健康B1体脂秤", "支持薄荷健康B1"),
        Spec(BridgeConstants.YODA_MODEL, BridgeConstants.YODA_DISPLAY_NAME, "支持京东健康 LEN-1001（广播直连）"),
    )

    @Volatile private var cache: List<Spec>? = null

    fun all(context: Context? = ContextHolder.get()): List<Spec> {
        cache?.let { return it }
        val configured = mutableListOf<Spec>()
        runCatching {
            val ctx = context ?: return@runCatching
            val dir = runCatching { ctx.getExternalFilesDir(null) }.getOrNull() ?: ctx.filesDir
            val file = File(dir, CONFIG_FILE)
            if (!file.exists()) return@runCatching
            val array = JSONArray(file.readText())
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                val model = item.optString("model").trim()
                val name = item.optString("name").trim()
                if (model.isNotEmpty() && name.isNotEmpty()) {
                    configured += Spec(model, name, item.optString("slogan", name))
                }
            }
        }.onFailure { BridgeLog.e("SCALE config read failed", it as? Exception) }
        val result = (builtIn + configured)
            .distinctBy { it.model }
            .filter { DeviceRegistry.byModel(it.model) != null }
            .ifEmpty { builtIn }
        cache = result
        return result
    }

    /** True for anything that identifies one of our injected products. */
    fun isOurs(value: String?): Boolean {
        if (value.isNullOrBlank()) return false
        val v = value.trim()
        if (all().any { v.equals(it.model, ignoreCase = true) || v.equals(it.name, ignoreCase = true) }) return true
        return v.contains("小米") && (v.contains("秤") || v.contains("SCALE", ignoreCase = true))
    }

    fun specFor(value: String?): Spec {
        val v = value?.trim().orEmpty()
        return all().firstOrNull { v.equals(it.model, ignoreCase = true) || v.equals(it.name, ignoreCase = true) }
            ?: all().first()
    }

    fun default(): Spec = all().first()
}
