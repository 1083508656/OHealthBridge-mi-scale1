package io.github.brucezhang1993.ohealthdevicebridge.store

import android.content.Context
import io.github.brucezhang1993.ohealthdevicebridge.BridgeConstants
import io.github.brucezhang1993.ohealthdevicebridge.device.DeviceRegistry

/** Account-scoped model routing. Existing AFU v2 records remain readable. */
object BridgeBindingStore {
    private fun prefs(context: Context) = context.getSharedPreferences("ohealth_device_bridge", Context.MODE_PRIVATE)
    fun devices(context: Context, account: String): Map<String, String> {
        val result = AfuBindingStore.macs(context, account).associateWith { BridgeConstants.AFU_MODEL }.toMutableMap()
        prefs(context).getStringSet("bridge_bound_v3_$account", emptySet()).orEmpty().forEach {
            val parts = it.split('|')
            if (parts.size == 2 && DeviceRegistry.byModel(parts[0]) != null) result[AfuBindingStore.normalize(parts[1])] = DeviceRegistry.byModel(parts[0])!!.model
        }
        return result
    }
    fun model(context: Context, account: String, mac: String?) = mac?.let { devices(context, account)[AfuBindingStore.normalize(it)] }
    fun contains(context: Context, account: String, mac: String?) = model(context, account, mac) != null
    fun add(context: Context, account: String, mac: String, model: String) {
        val driver = requireNotNull(DeviceRegistry.byModel(model))
        val devices = devices(context, account).toMutableMap().apply { put(AfuBindingStore.normalize(mac), driver.model) }
        prefs(context).edit().putStringSet("bridge_bound_v3_$account", devices.map { "${it.value}|${it.key}" }.toSet()).apply()
    }
    fun remove(context: Context, account: String, mac: String) {
        val devices = devices(context, account).toMutableMap().apply { remove(AfuBindingStore.normalize(mac)) }
        prefs(context).edit().putStringSet("bridge_bound_v3_$account", devices.map { "${it.value}|${it.key}" }.toSet()).apply()
        AfuBindingStore.remove(context, account, mac)
    }
}
