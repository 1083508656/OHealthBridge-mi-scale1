package io.github.brucezhang1993.ohealthdevicebridge.hook.oppo

import android.content.Context
import io.github.brucezhang1993.ohealthdevicebridge.BridgeConstants
import io.github.brucezhang1993.ohealthdevicebridge.BridgeLog
import io.github.brucezhang1993.ohealthdevicebridge.store.ScalePrefs

/**
 * Writes a measurement (weight + resistance) into OPPO Health's body-fat store.
 *
 * Two versions use two different write paths:
 *
 *  - 6.10.x: BHScaleModel -> h.m() compute -> i0.d() build record ->
 *    WeightScaleServiceImpl.q0() normalize -> BodyFatStore.b() persist.
 *  - 6.9.x:  BooheeWeightSaver.saveLockedMeasure(BHScaleModel, kg, tag, prevWeight)
 *    -> WeightBodyFat (single step, no obfuscated holder chain).
 *
 * Must run in the :SportDaemonService process where the database engine lives.
 */
object OppoRecordWriter {

    @Volatile private var profile: OppoVersionProfile? = null

    fun write(cl: ClassLoader, ctx: Context, weightKg: Double, resistanceOhm: Float, timestampEpochSeconds: Long): Any? {
        val p = profile ?: OppoVersionProfile.detect(cl).also { profile = it }
        return when (p) {
            OppoVersionProfile.V69 -> writeV69(cl, weightKg, resistanceOhm)
            OppoVersionProfile.V610 -> writeV610(cl, ctx, weightKg, resistanceOhm, timestampEpochSeconds)
        }
    }

    /**
     * 6.9.x write path: BooheeWeightSaver.saveLockedMeasure is a public, un-obfuscated single step.
     * It needs the ssoid-derived tag to attribute the row to the account owner.
     */
    private fun writeV69(cl: ClassLoader, weightKg: Double, resistanceOhm: Float): Any? = runCatching {
        val modelCls = cl.loadClass("com.boohee.scale_sdk.data.BHScaleModel")
        val model = modelCls.getDeclaredConstructor().newInstance()
        modelCls.getField("weight").setFloat(model, weightKg.toFloat())
        if (resistanceOhm > 0) {
            modelCls.getField("bodyResistance").setFloat(model, resistanceOhm.toFloat())
        }
        modelCls.getField("second").setLong(model, System.currentTimeMillis() / 1000)

        val saverCls = cl.loadClass("com.heytap.device.ui.weight.scale.boohee.BooheeWeightSaver")
        val saver = saverCls.getField("INSTANCE").get(null)
        val tag = ssoid(cl)
        BridgeLog.i("WRITE V69 saveLockedMeasure tag=${if (tag.isNullOrBlank()) "none" else "set"}")
        val record = saverCls.getMethod(
            "saveLockedMeasure", modelCls,
            Double::class.javaPrimitiveType, String::class.java, String::class.java
        ).invoke(saver, model, weightKg, tag, null)
        BridgeLog.i("WRITE V69 result=${record != null}")
        record
    }.onFailure { BridgeLog.e("WRITE V69 aborted", it as? Exception); null }.getOrNull()

    private fun writeV610(cl: ClassLoader, ctx: Context, weightKg: Double, resistanceOhm: Float, timestampEpochSeconds: Long): Any? {
        return runCatching {
            val ssoid = ssoid(cl)
            val ownerTag = ownerTagId(cl) ?: ssoid
            BridgeLog.i("WRITE start w=${weightKg} r=$resistanceOhm owner=${if (ownerTag.isNullOrBlank()) "none" else "set"}")

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
                    .invoke(null, computed ?: model, weightKg, "BHScaleType_bodyFat", ownerTag, System.currentTimeMillis())
            }.onFailure { BridgeLog.e("WRITE convert failed", it as? Exception) }.getOrNull()
            if (record == null) { BridgeLog.e("WRITE record null"); return false }
            // i0.d names the source from the bound device record (or the owner tag), which shows up
            // as "薄荷健康体脂秤" on the result page. Override it so the row says where it really
            // came from — and follow whichever scale is actually bound.
            runCatching { record.javaClass.getMethod("setDeviceName", String::class.java).invoke(record, deviceLabel(ctx)) }

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
            if (inserted == null) return null
            normalized
        }.onFailure { BridgeLog.e("WRITE aborted", it as? Exception); null }.getOrNull()
    }

    private fun ssoid(cl: ClassLoader): String? = runCatching {
        val holder = cl.loadClass("lc0.d").getMethod("h").invoke(null)
        holder.javaClass.getMethod("getSsoid").invoke(holder) as? String
    }.getOrNull()

    /** Device name shown on the result page; follows whichever scale is currently bound. */
    private fun deviceLabel(ctx: Context): String {
        val model = runCatching { ScalePrefs.load(ctx)?.model }.getOrNull()
        return if (model == BridgeConstants.YODA_MODEL) "LEN-1001 体脂秤" else "小米体脂秤"
    }

    /**
     * The account owner's userTagId (`utils.h0.i()` = `le.a(ssoid)`). `i0.d` uses its 4th
     * argument as the record's `userTagId`; leaving it null leaves the row unattributed, which is
     * what makes OPPO park it under "待认领" instead of filing it under the member.
     */
    private fun ownerTagId(cl: ClassLoader): String? = runCatching {
        cl.loadClass("com.heytap.device.ui.weight.utils.h0")
            .getMethod("i").invoke(null) as? String
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
