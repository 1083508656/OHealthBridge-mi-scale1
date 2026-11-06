package io.github.brucezhang1993.ohealthdevicebridge.device.afu

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.os.*
import io.github.brucezhang1993.ohealthdevicebridge.BridgeLog
import io.github.brucezhang1993.ohealthdevicebridge.device.*
import java.util.UUID

internal class AfuGattSession(
    private val context: Context,
    private val device: BluetoothDevice,
    val mode: Mode,
    profile: UserProfile?,
    private val onBindVerified: () -> Unit,
    onLiveWeight: (Double) -> Unit,
    onFinal: (MeasurementRecord) -> Unit,
    onHistory: (MeasurementRecord) -> Unit,
    private val onConnected: (Boolean) -> Unit,
    private val onError: (Throwable) -> Unit,
    private val onClosed: () -> Unit,
) {
    enum class Mode { BIND_VERIFY, MEASURE, HISTORY }
    private val handler = Handler(Looper.getMainLooper())
    private val scheduled = mutableSetOf<Runnable>()
    private var finished = false
    @Volatile var connected = false
        private set
    private var state = AfuSessionState.DISCONNECTED
    private var gatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private val queue = AfuGattQueue({ gatt }, ::schedule, ::fail)
    private val flow = profile?.let {
        AfuMeasurementFlow(it, mode == Mode.HISTORY, ::enqueueWrite, onLiveWeight, onFinal, onHistory, ::disconnect)
    }

    @SuppressLint("MissingPermission")
    fun connect() = dispatch {
        if (state != AfuSessionState.DISCONNECTED) return@dispatch
        state = AfuSessionState.CONNECTING
        try {
            gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
                ?: throw IllegalStateException("connectGatt returned null")
            schedule(10_000L) { if (state == AfuSessionState.CONNECTING) fail(IllegalStateException("GATT connect timeout")) }
            schedule(120_000L) { fail(IllegalStateException("AFU session timeout")) }
        } catch (error: Exception) { fail(error) }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        check(Looper.myLooper() == handler.looper)
        if (finished) return
        finished = true
        state = AfuSessionState.FINISHED
        flow?.cancel()
        scheduled.forEach(handler::removeCallbacks)
        scheduled.clear()
        queue.clear()
        val oldGatt = gatt
        gatt = null
        try { oldGatt?.disconnect() } catch (error: Exception) { BridgeLog.e("AFU disconnect failed", error) }
        finally {
            try { oldGatt?.close() } catch (error: Exception) { BridgeLog.e("AFU GATT close failed", error) }
            val wasConnected = connected
            connected = false
            onClosed()
            if (wasConnected) onConnected(false)
        }
    }

    private val callback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) = dispatch {
            if (status != BluetoothGatt.GATT_SUCCESS || newState != BluetoothProfile.STATE_CONNECTED) {
                fail(IllegalStateException("GATT connection failed status=$status state=$newState"))
            } else {
                state = AfuSessionState.DISCOVERING
                if (!gatt.discoverServices()) fail(IllegalStateException("discoverServices could not start"))
                else schedule(8_000L) { if (state == AfuSessionState.DISCOVERING) fail(IllegalStateException("service discovery timeout")) }
            }
        }
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) = dispatch {
            if (status != BluetoothGatt.GATT_SUCCESS) fail(IllegalStateException("service discovery failed status=$status"))
            else configureGatt(gatt)
        }
        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) =
            dispatch { queue.onDescriptorWrite(descriptor, status) }
        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) =
            dispatch { queue.onCharacteristicWrite(characteristic, status) }
        @Deprecated("Deprecated in Android 13")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION") val value = characteristic.value?.copyOf() ?: return
            dispatch { handleNotification(value) }
        }
        @Suppress("OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            val copy = value.copyOf()
            dispatch { handleNotification(copy) }
        }
    }

    @SuppressLint("MissingPermission")
    private fun configureGatt(gatt: BluetoothGatt) {
        writeCharacteristic = gatt.services.flatMap { it.characteristics }.firstOrNull { it.uuid == WRITE_CHARACTERISTIC_UUID }
            ?: throw IllegalStateException("AFU write characteristic not found")
        val write = requireNotNull(writeCharacteristic)
        check(write.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
            "AFU characteristic is not writable"
        }
        val subscribable = gatt.services.flatMap { it.characteristics }.filter {
            it.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
        }
        check(subscribable.isNotEmpty()) { "AFU exposes no Notify/Indicate characteristic" }
        state = AfuSessionState.SUBSCRIBING
        var pending = subscribable.size
        // Validate every subscription before enqueueing; callbacks are dispatched on the same looper.
        val descriptors = subscribable.map { characteristic ->
            check(gatt.setCharacteristicNotification(characteristic, true)) { "setCharacteristicNotification failed" }
            val descriptor = characteristic.getDescriptor(CCCD_UUID) ?: error("AFU notification CCCD missing")
            val value = if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0)
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            descriptor to value
        }
        descriptors.forEach { (descriptor, value) ->
            queue.enqueueDescriptor(descriptor, value, "subscribe ${descriptor.characteristic.uuid}") {
                pending--
                if (pending == 0) handshake()
            }
        }
    }

    private fun handshake() {
        state = AfuSessionState.HANDSHAKE
        schedule(20_000L) { if (state == AfuSessionState.HANDSHAKE) fail(IllegalStateException("AFU handshake timeout")) }
        enqueueWrite(AfuProtocol.buildTimeSync(epoch()), "time-sync")
        enqueueWrite(AfuProtocol.buildSyncFirst(), "sync-first")
        write(AfuProtocol.buildSyncSecond(), "sync-second") {
            if (mode == Mode.BIND_VERIFY) {
                disconnect()
                onBindVerified()
            } else {
                state = AfuSessionState.IDLE_HISTORY
                connected = true
                onConnected(true)
                flow?.ready(elapsed(), epoch())
                poll()
            }
        }
    }

    private fun poll() {
        schedule(250L) {
            flow?.poll(elapsed(), epoch())
            if (!finished) poll()
        }
    }
    private fun handleNotification(value: ByteArray) {
        val frame = AfuProtocol.parse(value) ?: return
        flow?.receive(frame, elapsed(), epoch())
    }
    private fun enqueueWrite(frame: ByteArray, description: String) = write(frame, description)
    private fun write(frame: ByteArray, description: String, done: () -> Unit = {}) {
        if (finished) return
        val characteristic = writeCharacteristic ?: throw IllegalStateException("AFU write characteristic unavailable")
        BridgeLog.i("AFU TX $description bytes=${frame.size}")
        queue.enqueueWrite(characteristic, frame, description, done)
    }
    private fun fail(error: Exception) {
        if (finished) return
        BridgeLog.e("AFU session failed state=$state", error)
        disconnect()
        onError(error)
    }
    private fun dispatch(block: () -> Unit) {
        val action = {
            if (!finished) try { block() } catch (error: Exception) { fail(error) }
        }
        if (Looper.myLooper() == handler.looper) action() else handler.post(action)
    }
    private fun schedule(delay: Long, block: () -> Unit): () -> Unit {
        lateinit var task: Runnable
        task = Runnable {
            scheduled.remove(task)
            if (!finished) try { block() } catch (error: Exception) { fail(error) }
        }
        scheduled.add(task)
        handler.postDelayed(task, delay)
        return { handler.removeCallbacks(task); scheduled.remove(task) }
    }
    private fun epoch() = System.currentTimeMillis() / 1000L
    private fun elapsed() = SystemClock.elapsedRealtime()
    companion object {
        val WRITE_CHARACTERISTIC_UUID: UUID = UUID.fromString("af000002-9f2d-4c8a-8d6f-6a7b45f90000")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
