package io.github.brucezhang1993.ohealthdevicebridge.hook.oppo

import android.content.ContentValues
import android.content.Context
import io.github.brucezhang1993.ohealthdevicebridge.BridgeLog

/**
 * Route A: make the Mi body scale show up in OPPO Health's device list by inserting a device
 * record into the app's own tables (through the already-decrypted DB handle).
 *
 * Required columns (from the shipped CREATE TABLE statements):
 *   DBDeviceInfo      -> device_type, create_time, sync_status  (NOT NULL)
 *   DBUserBoundDevice -> bind_time, create_time                 (NOT NULL)
 */
object DeviceInjector {
    const val DEVICE_NAME = "小米体脂秤（一代）"
    const val MODEL = "XIAOMI-MI-SCALE-V1-BODY"
    const val MANUFACTURER = "Xiaomi"

    /** Device category as used by OPPO's device list. 3 is the working guess for a body scale. */
    var deviceType: Int = 3

    private const val TABLE_INFO = "DBDeviceInfo"
    private const val TABLE_BOUND = "DBUserBoundDevice"

    fun inject(cl: ClassLoader, ctx: Context, mac: String, ssoidOverride: String? = null): Boolean = runCatching {
        val ssoid = ssoidOverride ?: ssoid(cl)
        val db = dbHandle(cl, ctx) ?: run { BridgeLog.e("BIND no db handle"); return false }
        val now = System.currentTimeMillis()
        val uniqueId = mac.uppercase()

        val exists = runCatching {
            val c = db.javaClass.getMethod("rawQuery", String::class.java, Array<String>::class.java)
                .invoke(db, "SELECT _id FROM $TABLE_BOUND WHERE bluetooth_address='$mac' OR device_unique_id='$uniqueId' LIMIT 1", null)!!
            val has = c.javaClass.getMethod("moveToNext").invoke(c) as Boolean
            runCatching { c.javaClass.getMethod("close").invoke(c) }
            has
        }.getOrDefault(false)
        if (exists) { BridgeLog.i("BIND already present mac=$mac"); return true }

        val info = ContentValues().apply {
            put("device_name", DEVICE_NAME)
            put("device_type", deviceType)
            put("device_unique_id", uniqueId)
            put("manufacturer", MANUFACTURER)
            put("model", MODEL)
            put("mac", mac)
            put("market_name", DEVICE_NAME)
            put("create_time", now)
            put("sync_status", 0)
        }
        val r1 = insert(db, TABLE_INFO, info)
        BridgeLog.i("BIND DBDeviceInfo row=$r1 name=$DEVICE_NAME type=$deviceType mac=$mac")

        val bound = ContentValues().apply {
            put("ssoid", ssoid)
            put("device_unique_id", uniqueId)
            put("device_name", DEVICE_NAME)
            put("model", MODEL)
            put("market_name", DEVICE_NAME)
            put("bluetooth_address", mac)
            put("bind_time", now)
            put("create_time", now)
        }
        val r2 = insert(db, TABLE_BOUND, bound)
        BridgeLog.i("BIND DBUserBoundDevice row=$r2 ssoid=$ssoid")

        (r1 != -1L) && (r2 != -1L)
    }.onFailure { BridgeLog.e("BIND failed", it as? Exception) }.getOrDefault(false)

    private fun insert(db: Any, table: String, values: ContentValues): Long = runCatching {
        db.javaClass.getMethod("insert", String::class.java, Int::class.javaPrimitiveType, ContentValues::class.java)
            .invoke(db, table, 5, values) as? Long ?: -1L
    }.onFailure { BridgeLog.e("BIND insert $table failed", it as? Exception) }.getOrDefault(-1L)

    private fun dbHandle(cl: ClassLoader, ctx: Context): Any? = runCatching {
        val appDb = cl.loadClass("com.heytap.databaseengineservice.db.AppDatabase")
            .getMethod("G", Context::class.java).invoke(null, ctx) ?: return null
        val helper = appDb.javaClass.getMethod("getOpenHelper").invoke(appDb)
        helper!!.javaClass.getMethod("getWritableDatabase").invoke(helper)
    }.getOrNull()

    private fun ssoid(cl: ClassLoader): String? = runCatching {
        val holder = cl.loadClass("lc0.d").getMethod("h").invoke(null)
        holder.javaClass.getMethod("getSsoid").invoke(holder) as? String
    }.getOrNull()
}
