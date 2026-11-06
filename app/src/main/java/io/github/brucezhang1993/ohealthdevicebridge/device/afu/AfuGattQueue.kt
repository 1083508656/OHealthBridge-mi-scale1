package io.github.brucezhang1993.ohealthdevicebridge.device.afu

import android.annotation.SuppressLint
import android.bluetooth.*
import android.os.Build

internal class AfuGattQueue(
    private val gattProvider: () -> BluetoothGatt?,
    schedule: (Long, () -> Unit) -> (() -> Unit),
    onFatalError: (Exception) -> Unit,
) {
    private val operations = AfuOperationQueue(schedule, onFatalError)

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    fun enqueueDescriptor(descriptor: BluetoothGattDescriptor, value: ByteArray, description: String, done: () -> Unit = {}) {
        val bytes = value.copyOf()
        operations.enqueue(descriptor, description, {
            val gatt = gattProvider() ?: return@enqueue false
            if (Build.VERSION.SDK_INT >= 33) gatt.writeDescriptor(descriptor, bytes) == BluetoothStatusCodes.SUCCESS
            else {
                descriptor.value = bytes
                gatt.writeDescriptor(descriptor)
            }
        }, done)
    }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    fun enqueueWrite(characteristic: BluetoothGattCharacteristic, value: ByteArray, description: String, done: () -> Unit = {}) {
        val bytes = value.copyOf()
        operations.enqueue(characteristic, description, {
            val gatt = gattProvider() ?: return@enqueue false
            val type = if ((characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0)
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            if (Build.VERSION.SDK_INT >= 33) gatt.writeCharacteristic(characteristic, bytes, type) == BluetoothStatusCodes.SUCCESS
            else {
                characteristic.writeType = type
                characteristic.value = bytes
                gatt.writeCharacteristic(characteristic)
            }
        }, done)
    }

    fun onDescriptorWrite(descriptor: BluetoothGattDescriptor, status: Int) = operations.complete(descriptor, status == BluetoothGatt.GATT_SUCCESS)
    fun onCharacteristicWrite(characteristic: BluetoothGattCharacteristic, status: Int) = operations.complete(characteristic, status == BluetoothGatt.GATT_SUCCESS)
    fun clear() = operations.close()
}
