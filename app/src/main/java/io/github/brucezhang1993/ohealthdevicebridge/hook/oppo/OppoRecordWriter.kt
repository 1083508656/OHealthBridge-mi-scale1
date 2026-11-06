package io.github.brucezhang1993.ohealthdevicebridge.hook.oppo

import android.content.Context
import io.github.brucezhang1993.ohealthdevicebridge.BridgeLog

/**
 * Writes a measurement (weight + resistance) into OPPO Health's body-fat store, reusing the
 * exact verified pipeline for 6.10.13:
 *   BHScaleModel -> h.m() compute -> i0.d() build record -> WeightScaleServiceImpl.q0() normalize
 *   -> BodyFatStore.b() persist (must run in the :SportDaemonService process).
 */
object OppoRecordWriter {

    fun write(cl: ClassLoader, ctx: Context, weightKg: Double, resistanceOhm: Float, timestampEpochSeconds: Long): Boolean {
        return runCatching {
            val ssoid = ssoid(cl)
            BridgeLog.i("WRITE start w=${weightKg} r=$resistanceOhm ssoid=[$ssoid]")

            val modelCls = cl.loadClass("com.boohee.scale_sdk.data.BHScaleModel")
            val model = modelCls.getDeclaredConstructor().newInstance()
            set(model, "setWeight", weightKg.toFloat())
            set(model, "setBodyResistance", resistanceOhm)
            set(model, "setSecond", timestampEpochSeconds)
            set(model, "setHistory", true)
            set(model, "setLockData", true)

            val holder = holder(cl) ?: run { BridgeLog.e("WRITE holder null"); return false }
            runCatching { holder.javaClass.methods.first { it.name == "o" && it.parameterCount == 1 }.invoke(holder, ctx) }
            Thread.sleep(800)
            val computed = runCatching {
                holder.javaClass.getMethod("m", Double::class.javaPrimitiveType, modelCls, String::class.java)
                    .invoke(holder, weightKg, model, ssoid)
            }.getOrNull()
            BridgeLog.i("WRITE composed=${computed != null}")

            val record = runCatching {
                cl.loadClass("com.heytap.device.ui.weight.scale.boohee.i0")
                    .getMethod("d", modelCls, Double::class.javaPrimitiveType, String::class.java, String::class.java, Class.forName("java.lang.Long"))
                    .invoke(null, computed ?: model, weightKg, "BHScaleType_bodyFat", null, System.currentTimeMillis())
            }.onFailure { BridgeLog.e("WRITE convert failed", it as? Exception) }.getOrNull()
            if (record == null) { BridgeLog.e("WRITE record null"); return false }

            val normalized = if (ssoid.isNullOrBlank()) record else runCatching {
                val impl = cl.loadClass("com.heytap.device.manager.WeightScaleServiceImpl").getDeclaredConstructor().newInstance()
                val m = impl.javaClass.methods.first { it.name == "q0" && it.parameterCount == 2 }
                m.invoke(impl, record, ssoid) ?: record
            }.getOrNull() ?: record

            val inserted = runCatching {
                val store = cl.loadClass("com.heytap.databaseengineservice.store.business.BodyFatStore").getDeclaredConstructor().newInstance()
                val m = store.javaClass.methods.first { it.name == "b" && it.parameterCount == 1 && List::class.java.isAssignableFrom(it.parameterTypes[0]) }
                m.invoke(store, arrayListOf(normalized))
            }.onFailure {
                BridgeLog.e("WRITE insert failed", it as? Exception)
                // Reflection wraps the real failure in InvocationTargetException; unwrap it so the
                // log shows the actual cause (6.10.14 changed some internal obfuscation names).
                val cause = (it as? java.lang.reflect.InvocationTargetException)?.targetException ?: it.cause
                if (cause != null) {
                    BridgeLog.e("WRITE insert cause=${cause.javaClass.name}: ${cause.message}")
                    cause.stackTrace.take(12).forEach { f -> BridgeLog.e("  at $f") }
                }
            }.getOrNull()

            BridgeLog.i("WRITE insert result=$inserted")
            inserted != null
        }.onFailure { BridgeLog.e("WRITE aborted", it as? Exception); false }.getOrDefault(false)
    }

    private fun ssoid(cl: ClassLoader): String? = runCatching {
        val holder = cl.loadClass("lc0.d").getMethod("h").invoke(null)
        holder.javaClass.getMethod("getSsoid").invoke(holder) as? String
    }.getOrNull()

    private fun holder(cl: ClassLoader): Any? = runCatching {
        val holderCls = cl.loadClass("com.heytap.device.ui.weight.scale.boohee.h")
        runCatching {
            val companion = holderCls.getDeclaredField("Companion").apply { isAccessible = true }.get(null)
            companion.javaClass.getMethod("a").invoke(companion)
        }.getOrNull() ?: runCatching {
            holderCls.getDeclaredField("z").apply { isAccessible = true }.get(null)
        }.getOrNull()
    }.getOrNull()

    private fun set(target: Any, name: String, value: Any) {
        runCatching {
            target.javaClass.methods.first { it.name == name && it.parameterCount == 1 }.invoke(target, value)
        }.onFailure { BridgeLog.e("WRITE setter $name failed", it as? Exception) }
    }
}
