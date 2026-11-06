package io.github.brucezhang1993.ohealthdevicebridge.hook.oppo

import android.content.Context
import de.robv.android.xposed.*
import io.github.brucezhang1993.ohealthdevicebridge.BridgeLog
import io.github.brucezhang1993.ohealthdevicebridge.device.DeviceDriver
import io.github.brucezhang1993.ohealthdevicebridge.device.DeviceRegistry
import io.github.brucezhang1993.ohealthdevicebridge.device.HistoryDeviceDriver
import io.github.brucezhang1993.ohealthdevicebridge.store.BridgeBindingStore

internal object BooheeKeepAliveHook {
    fun install(cl: ClassLoader) {
        val manager = XposedHelpers.findClass("com.heytap.device.ui.weight.scale.boohee.BHDeviceManager", cl)
        OppoAfuLifecycleHook.hook(manager, "startKeepAlive") { param ->
            val mac = param.args[0] as? String ?: return@hook
            val context = OppoReflect.context() ?: return@hook
            val account = try { OppoAccount.key(cl) } catch (_: Exception) { return@hook }
            val model = BridgeBindingStore.model(context, account, mac) ?: return@hook
            val driver = DeviceRegistry.byModel(model) ?: return@hook
            param.result = null
            OppoReflect.postMain {
                startHistory(cl, context, param.thisObject, account, mac, driver, param.args.getOrNull(1))
            }
        }
        for (name in listOf("forceStopKeepAlive", "detachKeepAliveWithoutDisconnect"))
            OppoAfuLifecycleHook.hook(manager, name) { OppoReflect.postMain { OppoAfuRuntime.stopBackground() } }
        for (name in listOf("isKeepAliveConnected", "isSdkGattConnected", "isKeepAliveGiveUp")) {
            OppoAfuLifecycleHook.hook(manager, name) { param ->
                val mac = param.args.firstOrNull() as? String ?: return@hook
                val context = OppoReflect.context() ?: return@hook
                val account = try { OppoAccount.key(cl) } catch (_: Exception) { return@hook }
                if (BridgeBindingStore.contains(context, account, mac)) {
                    param.result = if (name == "isKeepAliveGiveUp")
                        OppoAfuRuntime.background?.let { it.mac.equals(mac, true) && it.gaveUp } == true
                    else BridgeBindingStore.model(context, account, mac)?.let { DeviceRegistry.byModel(it)?.isConnected(mac) } == true
                }
            }
        }
        HookInstallState.keepAlive = true
    }

    internal fun startHistory(cl: ClassLoader, context: Context, manager: Any, account: String, mac: String,
                              driver: DeviceDriver, onState: Any?) {
        if (try { OppoAccount.key(cl) != account } catch (_: Exception) { true }) return
        fun reportCurrent() { onState?.let { OppoReflect.callFirst(it, listOf("invoke"), driver.isConnected(mac)) } }
        val measure = OppoAfuRuntime.measure
        if (measure != null && measure.mac?.let { DeviceRegistry.byModel(measure.model)?.hasSession(it) } != true) {
            measure.active = false
            OppoAfuRuntime.measure = null
        }
        if (OppoAfuRuntime.measure?.active == true || OppoAfuRuntime.bind?.active == true ||
            OppoAfuRuntime.scans.values.any { it.active }) { reportCurrent(); return }
        if (driver !is HistoryDeviceDriver) {
            OppoAfuRuntime.stopBackground()
            reportCurrent()
            return
        }
        val current = OppoAfuRuntime.background
        if (current?.active == true && current.account == account && current.model == driver.model &&
            current.mac.equals(mac, true) && driver.hasSession(mac)) { reportCurrent(); return }
        OppoAfuRuntime.stopBackground()
        val ticket = OppoAfuRuntime.Ticket(account, mac, driver.model)
        var lastState: Boolean? = null
        fun report(connected: Boolean) {
            if (ticket.valid(cl) && lastState != connected) {
                lastState = connected
                onState?.let { OppoReflect.callFirst(it, listOf("invoke"), connected) }
            }
        }
        try {
            // Native stop also cancels our background ticket, so install the new owner afterwards.
            OppoReflect.call(manager, "forceStopKeepAlive", "Bridge history transport")
            OppoAfuRuntime.background = ticket
            val body = OppoBodyComposition(cl, OppoObjectFactory(cl))
            body.prepare(manager, context, null)
            driver.receiveHistory(context, mac, body.currentUserProfile(manager),
                { record -> if (ticket.valid(cl)) body.importHistory(manager, mac, record, deviceModel = driver.model) },
                ::report,
                { error -> if (ticket.valid(cl)) {
                    ticket.gaveUp = true
                    BridgeLog.e("Bridge background history failed", error)
                    report(false)
                } })
        } catch (error: Exception) {
            if (ticket.valid(cl)) {
                ticket.gaveUp = true
                BridgeLog.e("Bridge background preparation failed", error)
                report(false)
            }
        }
    }
}
