package io.github.brucezhang1993.ohealthdevicebridge.device.yoda

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import io.github.brucezhang1993.ohealthdevicebridge.BridgeLog
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Unfiltered BLE scan for the LEN-1001. Unlike the Mi scales this one carries its measurement in
 * a manufacturer-specific AD structure rather than a 0x181B service-data record, so no
 * ScanFilter is used; [YodaScaleProtocol.parse] does the identification instead.
 *
 * Android splits the manufacturer AD into (companyId, payload). The first two bytes of our frame
 * are actually a session counter, not a real company ID, so they have to be reassembled here.
 */
internal class YodaBleScan {
    /** Throttle state for the raw-frame diagnostic dump. */
    @Volatile private var lastRawHex = ""
    @Volatile private var lastRawAt = 0L

    @SuppressLint("MissingPermission")
    fun start(
        context: Context,
        mac: String?,
        timeoutMs: Long,
        onFrame: (ScanResult, YodaScaleProtocol.Frame) -> Unit,
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
                            val sparse = scan.scanRecord?.manufacturerSpecificData ?: return@post
                            for (i in 0 until sparse.size()) {
                                val cid = sparse.keyAt(i)
                                val tail = sparse.valueAt(i) ?: continue
                                val full = byteArrayOf(
                                    (cid and 0xFF).toByte(),
                                    ((cid shr 8) and 0xFF).toByte(),
                                ) + tail
                                if (full.size != YodaScaleProtocol.MANUFACTURER_DATA_LEN) continue
                                val frame = YodaScaleProtocol.parse(full)
                                // Diagnosis: dump the raw AD so a dropped impedance can be told
                                // apart from one the scale never measured (resistance bytes 0).
                                val hex = full.joinToString("") { "%02X".format(it) }
                                if (hex != lastRawHex || System.currentTimeMillis() - lastRawAt > 2_000L) {
                                    lastRawHex = hex
                                    lastRawAt = System.currentTimeMillis()
                                    BridgeLog.i(
                                        "YODA raw=$hex parsed=" + (frame?.let {
                                            "w=${it.weightKg} r=${it.resistanceOhm} seq=${it.sequence} locked=${it.locked}"
                                        } ?: "null")
                                    )
                                }
                                if (frame == null) continue
                                // The frame embeds its own MAC; require it to match the sender so
                                // unrelated 0xFF advertisements can never be mistaken for a scale.
                                if (!frame.mac.equals(scan.device.address, true)) continue
                                onFrame(scan, frame)
                                break
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
                scanner.startScan(null, settings, callback)
                main.postDelayed(timeout, timeoutMs)
            } catch (error: Exception) {
                stopInternal(); failed(error)
            }
        }
        return ::stop
    }
}
