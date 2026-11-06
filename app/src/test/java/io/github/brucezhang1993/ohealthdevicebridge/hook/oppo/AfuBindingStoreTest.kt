package io.github.brucezhang1993.ohealthdevicebridge.hook.oppo

import android.content.ContextWrapper
import android.content.SharedPreferences
import io.github.brucezhang1993.ohealthdevicebridge.store.AfuBindingStore
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

class AfuBindingStoreTest {
    @Test fun accountNamespacesAndUnbindSurviveNewContext() {
        val values = mutableMapOf<String, Set<String>>()
        lateinit var prefs: SharedPreferences
        lateinit var editor: SharedPreferences.Editor
        editor = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { _, method, args ->
            when (method.name) {
                "putStringSet" -> { @Suppress("UNCHECKED_CAST") val set = args!![1] as Set<String>; values[args[0] as String] = set.toSet(); editor }
                "apply" -> null
                "commit" -> true
                else -> editor
            }
        } as SharedPreferences.Editor
        prefs = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) { "getStringSet" -> values[args!![0]] ?: args[1]; "edit" -> editor; else -> null }
        } as SharedPreferences
        fun context() = object : ContextWrapper(null) {
            override fun getSharedPreferences(name: String, mode: Int) = prefs
        }
        val mac = "aa:bb:cc:dd:ee:ff"
        AfuBindingStore.add(context(), "account-A", mac)
        AfuBindingStore.add(context(), "account-A", mac.uppercase())
        assertEquals(1, AfuBindingStore.macs(context(), "account-A").size)
        assertTrue(AfuBindingStore.contains(context(), "account-A", mac))
        assertFalse(AfuBindingStore.contains(context(), "account-B", mac))
        AfuBindingStore.remove(context(), "account-A", mac)
        assertTrue(AfuBindingStore.macs(context(), "account-A").isEmpty())
        val store = io.github.brucezhang1993.ohealthdevicebridge.store.BridgeBindingStore
        val xiaomi = io.github.brucezhang1993.ohealthdevicebridge.device.xiaomi.XiaomiModels.V2
        val second = "AA:BB:CC:DD:EE:00"
        AfuBindingStore.add(context(), "account-A", mac)
        store.add(context(), "account-A", second, xiaomi.lowercase())
        assertEquals(io.github.brucezhang1993.ohealthdevicebridge.BridgeConstants.AFU_MODEL, store.model(context(), "account-A", mac))
        assertEquals(xiaomi, store.model(context(), "account-A", second))
        assertNull(store.model(context(), "account-B", second))
        store.remove(context(), "account-A", mac)
        assertFalse(store.contains(context(), "account-A", mac))
        assertEquals(xiaomi, store.model(context(), "account-A", second))
    }
}
