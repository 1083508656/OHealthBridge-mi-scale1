package io.github.brucezhang1993.ohealthdevicebridge.device.xiaomi

import io.github.brucezhang1993.ohealthdevicebridge.device.MeasurementRecord
import java.time.DateTimeException
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Calendar

internal object MiScaleProtocol {
    data class Reading(val record: MeasurementRecord, val stable: Boolean, val removed: Boolean, val dated: Boolean)
    /** Little-endian unsigned 16-bit word. */
    private fun u16(data: ByteArray, offset: Int): Int = (data[offset].toInt() and 255) or ((data[offset + 1].toInt() and 255) shl 8)
    fun parse(data: ByteArray, v2: Boolean, now: Long): Reading? {
        if (data.size != if (v2) 13 else 10) return null
        fun u(i: Int) = data[i].toInt() and 255
        val flags = u(if (v2) 1 else 0)
        val pounds = u(0) and 1 != 0
        val catty = flags and (if (v2) 0x40 else 0x10) != 0
        val raw = u16(data, if (v2) 11 else 1)
        val kg = when { pounds -> raw / 100.0 * 0.45359237; catty -> raw / 200.0; else -> raw / 200.0 }
        if (kg !in 0.0..350.0) return null
        val d = if (v2) 2 else 3
        val dated = try {
            val year = u16(data, d)
            if (year !in 2000..2100) null else LocalDateTime.of(year, u(d + 2), u(d + 3), u(d + 4), u(d + 5), u(d + 6)).atZone(ZoneId.systemDefault()).toEpochSecond()
        } catch (_: DateTimeException) { null }
        val resistance = if (v2 && flags and 2 != 0) u16(data, 9).takeIf { it in 1..2000 } else null
        return Reading(MeasurementRecord(dated ?: now, kg, resistance), flags and 0x20 != 0, flags and 0x80 != 0, dated != null)
    }
    fun currentTime(calendar: Calendar = Calendar.getInstance()): ByteArray {
        val year = calendar.get(Calendar.YEAR)
        return byteArrayOf(year.toByte(), (year shr 8).toByte(), (calendar.get(Calendar.MONTH) + 1).toByte(),
            calendar.get(Calendar.DAY_OF_MONTH).toByte(), calendar.get(Calendar.HOUR_OF_DAY).toByte(),
            calendar.get(Calendar.MINUTE).toByte(), calendar.get(Calendar.SECOND).toByte(), 0, 0, 1)
    }
    /** Streams may split a 10-byte v1 record across notifications. Buffer stays under one record. */
    class History(private val v2: Boolean) {
        private var pending = byteArrayOf()
        private var count = 0
        var transferring = true; private set
        fun feed(bytes: ByteArray, now: Long): List<Reading> {
            if (bytes.contentEquals(byteArrayOf(3))) { pending = byteArrayOf(); transferring = false; return emptyList() }
            if (bytes.size >= 6 && bytes[0] == 1.toByte() && (u16(bytes, 2) == 0 || u16(bytes, 2) == 0xffff)) {
                pending = byteArrayOf(); return emptyList()
            }
            val size = if (v2 && bytes.size in setOf(13, 26)) 13 else 10
            require(pending.size + bytes.size <= 2048) { "小米历史记录缓冲超限" }
            val combined = pending + bytes
            val result = mutableListOf<Reading>()
            var offset = 0
            while (offset + size <= combined.size) {
                require(++count <= 1024) { "小米历史记录数量超限" }
                parse(combined.copyOfRange(offset, offset + size), size == 13, now)?.let { result += it }
                offset += size
            }
            pending = combined.copyOfRange(offset, combined.size)
            return result
        }
    }
}
