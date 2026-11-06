package io.github.brucezhang1993.ohealthdevicebridge.hook.oppo

import io.github.brucezhang1993.ohealthdevicebridge.device.DeviceRegistry
import io.github.brucezhang1993.ohealthdevicebridge.BridgeConstants
import java.util.WeakHashMap

/** One OPPO manager owns the bridge transport; tickets reject callbacks after cancel/account change. */
internal object OppoAfuRuntime {
    class Ticket(val account: String, val mac: String? = null, val model: String = BridgeConstants.AFU_MODEL) {
        var active = true
        var cancelScan: (() -> Unit)? = null
        var gaveUp = false
        fun valid(cl: ClassLoader): Boolean {
            if (!active) return false
            val matches = try { OppoAccount.key(cl) == account } catch (_: Exception) { false }
            if (!matches) cancel()
            return matches
        }
        fun cancel() {
            active = false
            cancelScan?.invoke()
            cancelScan = null
            mac?.let { DeviceRegistry.byModel(model)?.disconnect(it) }
        }
    }
    val scans = WeakHashMap<Any, Ticket>()
    var bind: Ticket? = null
    var measure: Ticket? = null
    var background: Ticket? = null
    fun stopBind() { bind?.cancel(); bind = null }
    fun stopMeasure() { measure?.cancel(); measure = null }
    fun stopBackground() { background?.cancel(); background = null }
    fun stopAll() {
        scans.values.toList().forEach(Ticket::cancel)
        scans.clear()
        stopBind(); stopMeasure(); stopBackground()
    }
}
