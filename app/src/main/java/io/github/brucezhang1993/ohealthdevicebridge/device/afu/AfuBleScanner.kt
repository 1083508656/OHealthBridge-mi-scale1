package io.github.brucezhang1993.ohealthdevicebridge.device.afu

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.Context
import android.os.*
import io.github.brucezhang1993.ohealthdevicebridge.BridgeConstants
import io.github.brucezhang1993.ohealthdevicebridge.device.BridgeDevice
import java.util.UUID

class AfuBleScanner {
    private val handler = Handler(Looper.getMainLooper())
    @SuppressLint("MissingPermission")
    fun scan(context: Context, timeoutMs: Long, onDevice: (BridgeDevice) -> Unit, onTimeout: () -> Unit, onError: (Throwable) -> Unit): () -> Unit {
        check(Looper.myLooper() == handler.looper)
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        val scanner = adapter?.bluetoothLeScanner
        if (adapter == null || !adapter.isEnabled || scanner == null) {
            onError(IllegalStateException("Bluetooth is unavailable or disabled"))
            return {}
        }
        var finished = false
        lateinit var callback: ScanCallback
        lateinit var timeout: Runnable
        fun finish(action: () -> Unit) {
            if (finished) return
            finished = true
            handler.removeCallbacks(timeout)
            try { scanner.stopScan(callback) }
            catch (error: Exception) { io.github.brucezhang1993.ohealthdevicebridge.BridgeLog.e("AFU scan stop failed", error) }
            action()
        }
        callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                handler.post {
                    if (finished) return@post
                    try {
                        val record = result.scanRecord ?: return@post
                        val name = record.deviceName ?: result.device.name
                        if (record.serviceUuids.orEmpty().none { it.uuid == SERVICE_UUID } || name?.contains("AFU", true) != true) return@post
                        finish { onDevice(BridgeDevice(result.device.address, name, BridgeConstants.AFU_MODEL)) }
                    } catch (error: Exception) { finish { onError(error) } }
                }
            }
            override fun onScanFailed(errorCode: Int) { handler.post { finish { onError(IllegalStateException("BLE scan failed: $errorCode")) } } }
        }
        timeout = Runnable { finish(onTimeout) }
        try {
            val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build()
            val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
            handler.postDelayed(timeout, timeoutMs)
            scanner.startScan(listOf(filter), settings, callback)
        } catch (error: Exception) { finish { onError(error) } }
        return { finish {} }
    }
    companion object { val SERVICE_UUID: UUID = UUID.fromString("0000fc50-0000-1000-8000-00805f9b34fb") }
}
