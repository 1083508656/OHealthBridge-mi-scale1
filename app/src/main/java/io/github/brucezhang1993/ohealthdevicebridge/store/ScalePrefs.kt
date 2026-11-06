package io.github.brucezhang1993.ohealthdevicebridge.store

import android.content.Context
import io.github.brucezhang1993.ohealthdevicebridge.device.xiaomi.XiaomiModels
import java.io.File

/**
 * Persists the MAC learned from a real BLE scan (never hardcoded), so a later pairing or the
 * background measurement loop can target that exact scale without scanning blind.
 * A plain text copy is kept in external files dir because the recorder runs in another process.
 */
object ScalePrefs {
    private const val PREFS = "ohealth_bridge_scale"
    private const val FILE = "oh_scale.txt"
    private const val KEY_MAC = "mac"
    private const val KEY_MODEL = "model"

    data class Binding(val mac: String, val model: String)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun file(context: Context) = File(
        runCatching { context.getExternalFilesDir(null) }.getOrNull() ?: context.filesDir, FILE
    )

    fun save(context: Context, mac: String, model: String) {
        prefs(context).edit().putString(KEY_MAC, mac).putString(KEY_MODEL, model).apply()
        runCatching { file(context).writeText("$mac|$model") }
    }

    fun load(context: Context): Binding? {
        val pm = prefs(context).getString(KEY_MAC, null)
        if (!pm.isNullOrBlank()) {
            return Binding(pm, prefs(context).getString(KEY_MODEL, null) ?: XiaomiModels.V1_BODY)
        }
        return runCatching {
            val parts = file(context).readText().trim().split('|')
            if (parts.size == 2 && parts[0].isNotBlank()) Binding(parts[0], parts[1]) else null
        }.getOrNull()
    }
}
