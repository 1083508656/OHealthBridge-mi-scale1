package io.github.brucezhang1993.ohealthdevicebridge.device.xiaomi

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.os.Handler
import android.os.Looper
import io.github.brucezhang1993.ohealthdevicebridge.BridgeLog
import io.github.brucezhang1993.ohealthdevicebridge.device.MeasurementRecord
import io.github.brucezhang1993.ohealthdevicebridge.device.afu.AfuGattQueue
import java.util.UUID

/** One GATT owner, one bounded operation queue, all transitions on the main looper. */
@SuppressLint("MissingPermission")
internal class MiScaleGattSession(
    private val context: Context, private val device: BluetoothDevice, private val transport: XiaomiModels.Transport,
    private val mode: Mode, private val verified: () -> Unit, private val live: (Double) -> Unit,
    private val final: (MeasurementRecord) -> Unit, private val history: (MeasurementRecord) -> Unit,
    private val onConnected: (Boolean) -> Unit,
    private val failed: (Throwable) -> Unit, private val closed: () -> Unit,
) {
    enum class Mode { BIND_VERIFY, MEASURE, HISTORY }
    private val main = Handler(Looper.getMainLooper())
    private val scheduled = mutableSetOf<Runnable>()
    private var gatt: BluetoothGatt? = null
    private var active = true
    var connected = false; private set
    private val queue = AfuGattQueue({ gatt }, ::schedule, ::fail)
    private val transfer = MiScaleProtocol.History(transport.bodyComposition)
    private val seen = mutableSetOf<Pair<Long, Double>>()
    private var historyCharacteristic: BluetoothGattCharacteristic? = null
    private var pendingFinal: MiScaleProtocol.Reading? = null
    private var transferMarker = 0
    private var logged = 0
    fun connect() {
        try {
            gatt = device.connectGatt(context, false, callbacks, BluetoothDevice.TRANSPORT_LE) ?: error("无法连接小米秤")
            schedule(10_000) { if (!connected) fail(IllegalStateException("小米秤连接超时，请唤醒秤并关闭占用蓝牙的应用")) }
            schedule(120_000) { fail(IllegalStateException("小米测量会话超时，请重新开始")) }
        } catch (error: Exception) { fail(error) }
    }
    private val callbacks = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) = dispatch {
            if (status != BluetoothGatt.GATT_SUCCESS || newState != BluetoothProfile.STATE_CONNECTED) fail(IllegalStateException("小米蓝牙连接断开（$status）"))
            else check(gatt.discoverServices()) { "无法读取小米蓝牙服务" }
        }
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) = dispatch {
            check(status == BluetoothGatt.GATT_SUCCESS) { "无法读取小米蓝牙服务（$status）" }
            configure(gatt)
        }
        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) = dispatch { queue.onDescriptorWrite(descriptor, status) }
        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) = dispatch { queue.onCharacteristicWrite(characteristic, status) }
        @Deprecated("Deprecated in Android 13")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION") val data = characteristic.value?.copyOf() ?: return
            dispatch { receive(characteristic.uuid, data) }
        }
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            val data = value.copyOf(); dispatch { receive(characteristic.uuid, data) }
        }
    }
    private fun configure(gatt: BluetoothGatt) {
        val serviceUuid = XiaomiBleScan.uuid(transport.serviceShort)
        BridgeLog.i("XIAOMI services: " + gatt.services.joinToString(", ") { it.uuid.toString().take(8) })
        val service = gatt.getService(serviceUuid)
            ?: error("小米秤型号与蓝牙服务不匹配（缺少 ${serviceUuid.toString().take(8)}）")
        // Xiaomi stores the history channel on its own vendor service (…-3512-2118-0009af100700),
        // not inside 0x181B; some firmware also re-exports it. Treat it as optional either way.
        val historyChar = runCatching {
            (gatt.getService(XIAOMI_SERVICE) ?: service).getCharacteristic(HISTORY)
        }.getOrNull()?.takeIf { transport.history }
        historyCharacteristic = historyChar
        if (historyChar == null && transport.history) BridgeLog.i("XIAOMI history channel absent; live measurement only")

        fun subscribe(characteristic: BluetoothGattCharacteristic, done: () -> Unit) {
            check(gatt.setCharacteristicNotification(characteristic, true)) { "无法订阅小米测量数据" }
            val descriptor = characteristic.getDescriptor(CCCD) ?: error("小米通知描述符不可用")
            val value = if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) BluetoothGattDescriptor.ENABLE_INDICATION_VALUE else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            queue.enqueueDescriptor(descriptor, value, "Xiaomi measurement subscription", done)
        }
        fun ready() {
            connected = true
            if (mode == Mode.BIND_VERIFY) { disconnect(); verified(); return }
            onConnected(true)
            if (!active) return
            val history = historyCharacteristic ?: return
            if (!transport.history) return
            val marker = (System.nanoTime() and 0xffff).toInt()
            transferMarker = marker
            // Request all available history, never send a delete-history command.
            queue.enqueueWrite(history, byteArrayOf(1, 0, 0, (marker shr 8).toByte(), marker.toByte()), "Xiaomi history range")
            queue.enqueueWrite(history, byteArrayOf(2), "Xiaomi history transfer")
        }
        // Body composition (0x2A9C) is the live channel on a body-fat scale; 0x2A9D only exists on
        // the weight-only generation and is subscribed as a fallback.
        val measurement = listOfNotNull(
            service.getCharacteristic(XiaomiBleScan.uuid(0x2a9c)),
            service.getCharacteristic(XiaomiBleScan.uuid(0x2a9d)),
        )
        check(measurement.isNotEmpty()) { "小米秤测量通道不可用" }
        fun subscribeAll(index: Int) {
            if (index >= measurement.size) { ready(); return }
            subscribe(measurement[index]) { subscribeAll(index + 1) }
        }

        if (historyChar == null) { subscribeAll(0); return }
        val magic = byteArrayOf(1, 0x96.toByte(), 0x8a.toByte(), 0xbd.toByte(), 0x62)
        if (transport != XiaomiModels.Transport.BODY_V2) queue.enqueueWrite(historyChar, magic, "Xiaomi history enable")
        subscribe(historyChar) {
            if (transport == XiaomiModels.Transport.BODY_V2) queue.enqueueWrite(historyChar, magic, "Xiaomi history enable")
            subscribeAll(0)
        }
    }
    private fun receive(uuid: UUID, data: ByteArray) {
        if (mode == Mode.BIND_VERIFY) return
        if (uuid != HISTORY && logged < 4) {
            logged++
            BridgeLog.i("XIAOMI notify ${data.size}B ${uuid.toString().take(8)}: " + data.joinToString(" ") { "%02X".format(it) })
        }
        val wasHistory = transfer.transferring && uuid == HISTORY
        val records = if (uuid == HISTORY) transfer.feed(data, epoch()) else listOfNotNull(MiScaleProtocol.parse(data, transport.bodyComposition && data.size == transport.payloadSize, epoch()))
        if (uuid == HISTORY && data.contentEquals(byteArrayOf(3))) historyCharacteristic?.let {
            queue.enqueueWrite(it, byteArrayOf(3), "Xiaomi history stop acknowledgement")
            queue.enqueueWrite(it, byteArrayOf(4, 0xff.toByte(), 0xff.toByte(), (transferMarker shr 8).toByte(), transferMarker.toByte()), "Xiaomi history completion acknowledgement") {
                if (mode == Mode.HISTORY) disconnect()
            }
        }
        records.forEach { reading ->
            if (reading.removed || reading.record.weightKg <= 0) { pendingFinal = null; return@forEach }
            if (wasHistory) {
                if (reading.stable && reading.dated && seen.add(reading.record.timestampEpochSeconds to reading.record.weightKg)) history(reading.record)
            } else if (mode == Mode.MEASURE) {
                live(reading.record.weightKg)
                if (reading.stable) {
                    if (!transport.bodyComposition || reading.record.resistanceOhm != null) emitFinal(reading.record)
                    else {
                        pendingFinal = reading
                        schedule(transport.impedanceWaitMs) { if (pendingFinal === reading) { pendingFinal = null; emitFinal(reading.record) } }
                    }
                }
            }
        }
    }
    private fun emitFinal(record: MeasurementRecord) { pendingFinal = null; if (seen.add(record.timestampEpochSeconds to record.weightKg)) final(record) }
    fun disconnect() {
        if (!active) return
        val wasConnected = connected
        active = false; connected = false; scheduled.forEach(main::removeCallbacks); scheduled.clear(); queue.clear()
        val old = gatt; gatt = null
        try { old?.disconnect() } catch (_: Exception) { }
        finally { try { old?.close() } catch (_: Exception) { } finally { closed() } }
        if (wasConnected) onConnected(false)
    }
    private fun fail(error: Exception) { if (active) { disconnect(); failed(error) } }
    private fun dispatch(block: () -> Unit) { main.post { if (active) try { block() } catch (error: Exception) { fail(error) } } }
    private fun schedule(delay: Long, block: () -> Unit): () -> Unit {
        lateinit var task: Runnable
        task = Runnable { scheduled.remove(task); if (active) try { block() } catch (error: Exception) { fail(error) } }
        scheduled += task; main.postDelayed(task, delay)
        return { scheduled.remove(task); main.removeCallbacks(task) }
    }
    private fun epoch() = System.currentTimeMillis() / 1000
    companion object {
        val HISTORY: UUID = UUID.fromString("00002a2f-0000-3512-2118-0009af100700")
        val XIAOMI_SERVICE: UUID = UUID.fromString("00001530-0000-3512-2118-0009af100700")
        val CCCD = XiaomiBleScan.uuid(0x2902)
    }
}
