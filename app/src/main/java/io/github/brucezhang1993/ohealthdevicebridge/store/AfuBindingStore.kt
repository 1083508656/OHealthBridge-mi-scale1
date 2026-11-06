package io.github.brucezhang1993.ohealthdevicebridge.store

import android.content.Context
import java.util.Locale

object AfuBindingStore {
    private const val PREFS = "ohealth_device_bridge"
    // Versioned account namespaces intentionally do not adopt ambiguous legacy MACs.
    private fun key(account: String) = "afu_bound_v2_$account"
    fun add(context: Context, account: String, mac: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putStringSet(key(account), macs(context, account) + normalize(mac)).apply()
    }
    fun remove(context: Context, account: String, mac: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putStringSet(key(account), macs(context, account) - normalize(mac)).apply()
    }
    fun contains(context: Context, account: String, mac: String?) = mac != null && normalize(mac) in macs(context, account)
    fun macs(context: Context, account: String): Set<String> = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getStringSet(key(account), emptySet()).orEmpty().map(::normalize).toSet()
    fun normalize(mac: String) = mac.uppercase(Locale.ROOT)
}
