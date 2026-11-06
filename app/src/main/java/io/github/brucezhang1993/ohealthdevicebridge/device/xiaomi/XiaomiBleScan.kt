package io.github.brucezhang1993.ohealthdevicebridge.device.xiaomi

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal class XiaomiBleScan {
    /**
     * [permissive] drops the service-data filter and resolves the service UUID by hand, because
     * first-generation scales may advertise 0x181B without carrying the 13-byte payload.
     *
     * BLE scanning must be started on a Looper thread. Callers used to run this from a background
     * thread and hit an immediate IllegalStateException; the whole setup is now posted to the main
     * looper and stop() is safe from any thread.
     */
    @SuppressLint("MissingPermission")
    fun start(
        context: Context,
        service: UUID,
        mac: String?,
        timeoutMs: Long,
        permissive: Boolean = false,
        result: (ScanResult, ByteArray) -> Unit,
        expired: () -> Unit,
        failed: (Throwable) -> Unit,
    ): () -> Unit {
        val main = Handler(Looper.getMainLooper())
        val active = AtomicBoolean(true)
        val scannerRef = AtomicReference<BluetoothLeScanner?>(null)
        val callbackRef = AtomicReference<ScanCallback?>(null)
        val timeoutRef = AtomicReference<Runnable?>(null)

        fun stopInternal() {
            if (!active.compareAndSet(true, false)) return
            timeoutRef.getAndSet(null)?.let { main.removeCallbacks(it) }
            val cb = callbackRef.getAndSet(null)
            val scanner = scannerRef.getAndSet(null)
            if (cb != null && scanner != null) runCatching { scanner.stopScan(cb) }
        }
        fun stop() { main.post { stopInternal() } }

        main.post {
            if (!active.get()) return@post
            val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            val scanner = adapter?.bluetoothLeScanner
            if (adapter == null || !adapter.isEnabled || scanner == null) {
                stopInternal()
                failed(IllegalStateException("请开启蓝牙后重试"))
                return@post
            }
            scannerRef.set(scanner)

            val callback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, scan: ScanResult) {
                    main.post {
                        if (!active.get()) return@post
                        try {
                            if (mac != null && !scan.device.address.equals(mac, true)) return@post
                            val data = scan.scanRecord?.getServiceData(ParcelUuid(service))
                            if (data != null) {
                                result(scan, data.copyOf())
                            } else if (permissive &&
                                scan.scanRecord?.serviceUuids?.any { it.uuid == service } == true
                            ) {
                                result(scan, ByteArray(0))
                            }
                        } catch (error: Exception) {
                            stopInternal(); failed(error)
                        }
                    }
                }

                override fun onScanFailed(errorCode: Int) {
                    main.post {
                        if (active.get()) {
                            stopInternal()
                            failed(IllegalStateException("蓝牙扫描失败（$errorCode）"))
                        }
                    }
                }
            }
            callbackRef.set(callback)

            val timeout = Runnable { if (active.get()) { stopInternal(); expired() } }
            timeoutRef.set(timeout)
            try {
                val settings = ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
                if (permissive) {
                    scanner.startScan(null, settings, callback)
                } else {
                    val filter = ScanFilter.Builder()
                        .setServiceData(ParcelUuid(service), byteArrayOf())
                        .apply { if (mac != null) setDeviceAddress(mac) }
                        .build()
                    scanner.startScan(listOf(filter), settings, callback)
                }
                main.postDelayed(timeout, timeoutMs)
            } catch (error: Exception) {
                stopInternal(); failed(error)
            }
        }
        return ::stop
    }

    companion object {
        fun uuid(short: Int): UUID = UUID.fromString("%08x-0000-1000-8000-00805f9b34fb".format(short))
        val MIBEACON = uuid(0xfe95)
    }
}
