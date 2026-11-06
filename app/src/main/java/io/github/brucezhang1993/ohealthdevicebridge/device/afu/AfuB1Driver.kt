package io.github.brucezhang1993.ohealthdevicebridge.device.afu

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Looper
import io.github.brucezhang1993.ohealthdevicebridge.BridgeConstants
import io.github.brucezhang1993.ohealthdevicebridge.device.*
import java.util.Locale

object AfuB1Driver : HistoryDeviceDriver {
    override val model = BridgeConstants.AFU_MODEL
    private val sessions = java.util.concurrent.ConcurrentHashMap<String, AfuGattSession>()
    private fun key(mac: String) = mac.uppercase(Locale.ROOT)
    override fun scan(context: Context, timeoutMs: Long, onDevice: (BridgeDevice) -> Unit, onTimeout: () -> Unit, onError: (Throwable) -> Unit) =
        AfuBleScanner().scan(context, timeoutMs, onDevice, onTimeout, onError)
    override fun verifyForBind(context: Context, mac: String, onSuccess: () -> Unit, onError: (Throwable) -> Unit) =
        start(context, mac, AfuGattSession.Mode.BIND_VERIFY, null, onSuccess, {}, {}, {}, {}, onError)
    override fun measure(context: Context, mac: String, profile: UserProfile, onLiveWeight: (Double) -> Unit,
                         onFinal: (MeasurementRecord) -> Unit, onHistory: (MeasurementRecord) -> Unit, onError: (Throwable) -> Unit) =
        start(context, mac, AfuGattSession.Mode.MEASURE, profile, {}, onLiveWeight, onFinal, onHistory, {}, onError)
    override fun receiveHistory(context: Context, mac: String, profile: UserProfile, onHistory: (MeasurementRecord) -> Unit,
                       onConnected: (Boolean) -> Unit, onError: (Throwable) -> Unit) =
        start(context, mac, AfuGattSession.Mode.HISTORY, profile, {}, {}, {}, onHistory, onConnected, onError)

    @SuppressLint("MissingPermission")
    private fun start(context: Context, mac: String, mode: AfuGattSession.Mode, profile: UserProfile?, onBind: () -> Unit,
                      onLive: (Double) -> Unit, onFinal: (MeasurementRecord) -> Unit, onHistory: (MeasurementRecord) -> Unit,
                      onConnected: (Boolean) -> Unit, onError: (Throwable) -> Unit) {
        check(Looper.myLooper() == Looper.getMainLooper())
        disconnect(mac)
        try {
            val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: error("Bluetooth adapter unavailable")
            check(adapter.isEnabled) { "Bluetooth is disabled" }
            lateinit var session: AfuGattSession
            session = AfuGattSession(context, adapter.getRemoteDevice(mac), mode, profile, onBind, onLive, onFinal, onHistory,
                onConnected, onError, { sessions.remove(key(mac), session) })
            sessions[key(mac)] = session
            session.connect()
        } catch (error: Exception) { onError(error) }
    }
    override fun disconnect(mac: String) { sessions.remove(key(mac))?.disconnect() }
    override fun hasSession(mac: String) = sessions.containsKey(key(mac))
    override fun isConnected(mac: String) = sessions[key(mac)]?.connected == true
}
