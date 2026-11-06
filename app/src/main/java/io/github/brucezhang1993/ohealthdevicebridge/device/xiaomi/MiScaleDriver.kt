package io.github.brucezhang1993.ohealthdevicebridge.device.xiaomi

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import io.github.brucezhang1993.ohealthdevicebridge.BridgeLog
import io.github.brucezhang1993.ohealthdevicebridge.device.*
import java.util.Locale

internal class MiScaleDriver(override val model: String) : HistoryDeviceDriver {
    private val transport get() = XiaomiModels.transport(model)
    private val sessions = mutableMapOf<String, MiScaleGattSession>()
    private fun key(mac: String) = mac.uppercase(Locale.ROOT)
    private fun label() = XiaomiModels.names[model] ?: model
    override fun hasSession(mac: String) = sessions.containsKey(key(mac))
    override fun isConnected(mac: String) = sessions[key(mac)]?.connected == true

    /**
     * Discovery is generic and MAC-free: accept only devices that actually advertise a Mi scale
     * service / name / payload. (An earlier version accepted *any* BLE device after a timeout,
     * which could bind a random neighbour's headphones.)
     */
    override fun scan(context: Context, timeoutMs: Long, onDevice: (BridgeDevice) -> Unit, onTimeout: () -> Unit, onError: (Throwable) -> Unit): () -> Unit {
        var finished = false
        var stop: (() -> Unit)? = null
        val service = XiaomiBleScan.uuid(transport.serviceShort)
        stop = XiaomiBleScan().start(context, service, null, timeoutMs, true, { scan, bytes ->
            val name = scan.scanRecord?.deviceName.orEmpty().trim().uppercase(Locale.ROOT)
            val knownName = name.startsWith("MI_SCALE") || name.startsWith("MIBCS") ||
                name.startsWith("MIBFS") || name == "MI SCALE2" || name.contains("MI SCALE")
            val advertised = scan.scanRecord?.serviceUuids?.any {
                it.uuid == service || it.uuid == XiaomiBleScan.MIBEACON
            } == true
            val parsed = bytes.isNotEmpty() &&
                MiScaleProtocol.parse(bytes, transport.bodyComposition, System.currentTimeMillis() / 1000) != null
            if (!finished && (parsed || knownName || advertised)) {
                finished = true; stop?.invoke()
                BridgeLog.i("XIAOMI candidate ${BridgeLog.maskedMac(scan.device.address)} name=${name.ifEmpty { "-" }} payload=${bytes.size} parsed=$parsed advertised=$advertised")
                onDevice(BridgeDevice(scan.device.address, label(), model))
            }
        }, { if (!finished) { finished = true; onTimeout() } }, { if (!finished) { finished = true; onError(it) } })
        return { finished = true; stop?.invoke() }
    }

    /**
     * Target the MAC learned at bind time; still verifies the advertisement before accepting.
     *
     * The scale embeds the same 10/13-byte measurement record in its advertisement while someone
     * stands on it. Reading it straight from the broadcast avoids opening a GATT connection — and
     * a GATT connection is what breaks here: the scale drops the link after ~30s of silence, so a
     * late connect misses the measurement entirely.
     */
    fun scanMac(
        context: Context, mac: String, timeoutMs: Long,
        onDevice: (BridgeDevice) -> Unit, onTimeout: () -> Unit, onError: (Throwable) -> Unit,
        onBroadcast: ((MeasurementRecord) -> Unit)? = null,
    ): () -> Unit {
        var finished = false
        var stop: (() -> Unit)? = null
        val service = XiaomiBleScan.uuid(transport.serviceShort)
        stop = XiaomiBleScan().start(context, service, mac, timeoutMs, true, { scan, bytes ->
            val reading = if (bytes.isNotEmpty()) {
                MiScaleProtocol.parse(bytes, transport.bodyComposition, System.currentTimeMillis() / 1000)
            } else null
            // A scale that is merely woken up advertises a near-empty reading (a few hundred grams
            // to ~3kg). Accepting it would burn the 60s write cooldown and hide the real
            // measurement taken moments later, so anything under 10kg is treated as noise.
            if (reading != null && reading.record.weightKg >= 10.0) {
                BridgeLog.i(
                    "XIAOMI broadcast ${BridgeLog.maskedMac(scan.device.address)} " +
                        "w=${reading.record.weightKg} r=${reading.record.resistanceOhm} stable=${reading.stable}"
                )
                onBroadcast?.invoke(reading.record)
                // Keep scanning until a stable frame arrives. Stopping on the first frame captured
                // the weight mid-step, which is exactly the wrong number to record.
                if (reading.stable && !finished) { finished = true; stop?.invoke() }
            } else if (!finished) {
                finished = true; stop?.invoke()
                BridgeLog.i("XIAOMI known-device hit ${BridgeLog.maskedMac(scan.device.address)}")
                onDevice(BridgeDevice(scan.device.address, label(), model))
            }
        }, { if (!finished) { finished = true; onTimeout() } }, { if (!finished) { finished = true; onError(it) } })
        return { finished = true; stop?.invoke() }
    }

    override fun verifyForBind(context: Context, mac: String, onSuccess: () -> Unit, onError: (Throwable) -> Unit) =
        start(context, mac, MiScaleGattSession.Mode.BIND_VERIFY, onSuccess, {}, {}, {}, {}, onError)

    override fun measure(context: Context, mac: String, profile: UserProfile, onLiveWeight: (Double) -> Unit, onFinal: (MeasurementRecord) -> Unit, onHistory: (MeasurementRecord) -> Unit, onError: (Throwable) -> Unit) =
        start(context, mac, MiScaleGattSession.Mode.MEASURE, {}, onLiveWeight, onFinal, onHistory, {}, onError)

    override fun receiveHistory(context: Context, mac: String, profile: UserProfile, onHistory: (MeasurementRecord) -> Unit, onConnected: (Boolean) -> Unit, onError: (Throwable) -> Unit) =
        start(context, mac, MiScaleGattSession.Mode.HISTORY, {}, {}, {}, onHistory, onConnected, onError)

    @SuppressLint("MissingPermission")
    private fun start(context: Context, mac: String, mode: MiScaleGattSession.Mode, verified: () -> Unit, live: (Double) -> Unit, final: (MeasurementRecord) -> Unit, history: (MeasurementRecord) -> Unit, onConnected: (Boolean) -> Unit, failed: (Throwable) -> Unit) {
        disconnect(mac)
        try {
            val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: error("蓝牙不可用")
            check(adapter.isEnabled) { "请开启蓝牙" }
            lateinit var session: MiScaleGattSession
            session = MiScaleGattSession(context, adapter.getRemoteDevice(mac), transport, mode, verified, live, final, history, onConnected, failed) { sessions.remove(key(mac), session) }
            sessions[key(mac)] = session; session.connect()
        } catch (error: Exception) { failed(error) }
    }

    override fun disconnect(mac: String) { sessions.remove(key(mac))?.disconnect() }
}
