package io.github.brucezhang1993.ohealthdevicebridge.device

import android.content.Context

data class BridgeDevice(
    val mac: String,
    val name: String,
    val model: String,
)

data class UserProfile(
    val age: Int,
    val male: Boolean,
    val targetKg: Double,
    val userSlot: Int = 1,
)

data class MeasurementRecord(
    val timestampEpochSeconds: Long,
    val weightKg: Double,
    val resistanceOhm: Int?,
    val resistance250KhzOhm: Double? = null,
    val heartRateBpm: Int? = null,
)

interface DeviceDriver {
    val model: String
    fun hasSession(mac: String): Boolean = false
    fun isConnected(mac: String): Boolean = false

    fun scan(
        context: Context,
        timeoutMs: Long,
        onDevice: (BridgeDevice) -> Unit,
        onTimeout: () -> Unit,
        onError: (Throwable) -> Unit,
    ): () -> Unit

    fun verifyForBind(
        context: Context,
        mac: String,
        onSuccess: () -> Unit,
        onError: (Throwable) -> Unit,
    )

    fun measure(
        context: Context,
        mac: String,
        profile: UserProfile,
        onLiveWeight: (Double) -> Unit,
        onFinal: (MeasurementRecord) -> Unit,
        onHistory: (MeasurementRecord) -> Unit,
        onError: (Throwable) -> Unit,
    )

    fun disconnect(mac: String)
}

/** Drivers with a bounded, independently cancellable history transport. */
interface HistoryDeviceDriver : DeviceDriver {
    fun receiveHistory(
        context: Context,
        mac: String,
        profile: UserProfile,
        onHistory: (MeasurementRecord) -> Unit,
        onConnected: (Boolean) -> Unit,
        onError: (Throwable) -> Unit,
    )
}
