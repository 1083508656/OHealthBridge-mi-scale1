package io.github.brucezhang1993.ohealthdevicebridge.hook.oppo

import android.content.Context
import io.github.brucezhang1993.ohealthdevicebridge.BridgeLog
import io.github.brucezhang1993.ohealthdevicebridge.device.DeviceRegistry
import io.github.brucezhang1993.ohealthdevicebridge.device.UserProfile
import io.github.brucezhang1993.ohealthdevicebridge.device.xiaomi.MiScaleDriver
import io.github.brucezhang1993.ohealthdevicebridge.device.xiaomi.XiaomiModels
import io.github.brucezhang1993.ohealthdevicebridge.store.ScalePrefs

/**
 * Route B stage 4 - measurement callback.
 * Runs in :SportDaemonService (where the SQLCipher database engine is initialised). It listens
 * for scale advertisements and, when a measurement appears, writes it into OPPO Health through
 * the verified h.m -> i0.d -> q0 -> BodyFatStore.b pipeline.
 *
 * The MAC is the one learned from the real BLE scan during pairing (ScalePrefs), never hardcoded.
 * With no binding yet it still scans generically, so a new scale can be discovered on its own.
 */
object XiaomiAutoRecord {
    private const val SCAN_TIMEOUT_MS = 20_000L
    private const val COOLDOWN_MS = 60_000L

    @Volatile private var running = false

    fun start(cl: ClassLoader, ctx: Context) {
        if (running) return
        running = true
        val appCtx = (ctx.applicationContext ?: ctx).also {
            BridgeLog.i("AUTORECORD context ready=${it.javaClass.simpleName}")
        }
        Thread({
            var lastWriteAt = 0L
            BridgeLog.i("AUTORECORD started (measurement listener)")
            while (running) {
                try {
                    if (System.currentTimeMillis() - lastWriteAt < COOLDOWN_MS) {
                        Thread.sleep(5_000); continue
                    }
                    val binding = ScalePrefs.load(appCtx)
                    val driver = DeviceRegistry.byModel(binding?.model) ?: MiScaleDriver(XiaomiModels.V1_BODY)
                    var handled = false
                    // Generic scan, never bound to a single MAC: any Xiaomi scale that advertises
                    // (service UUID / name / parseable payload) is picked up. Binding once only
                    // records the model so a different scale keeps working without re-pairing.
                    val stop = driver.scan(appCtx, SCAN_TIMEOUT_MS, { device ->
                        handled = true
                        BridgeLog.i("AUTORECORD found ${BridgeLog.maskedMac(device.mac)}")
                        if (driver is MiScaleDriver) measureOnce(cl, appCtx, driver, device.mac)
                        lastWriteAt = System.currentTimeMillis()
                    }, { BridgeLog.i("AUTORECORD scan timeout") }, { e ->
                        BridgeLog.e("AUTORECORD scan error", e)
                    })
                    Thread.sleep(SCAN_TIMEOUT_MS + 2_000)
                    runCatching { stop() }
                    if (handled) Thread.sleep(3_000)
                } catch (t: Throwable) {
                    BridgeLog.e("AUTORECORD loop error", t as? Exception)
                    Thread.sleep(10_000)
                }
            }
        }, "oh-autorecord").start()
    }

    fun stop() { running = false }

    private fun measureOnce(cl: ClassLoader, ctx: Context, driver: MiScaleDriver, mac: String) {
        // Member profile is resolved after the final weight is known (nearest reference weight),
        // so the body-composition pipeline uses that member's own age/sex/height rather than the
        // main account's numbers.
        driver.measure(ctx, mac, UserProfile(age = 30, male = true, targetKg = 75.0), { w ->
            BridgeLog.i("AUTORECORD live %.2f kg".format(w))
        }, { rec ->
            BridgeLog.i("AUTORECORD final w=${rec.weightKg} r=${rec.resistanceOhm} t=${rec.timestampEpochSeconds}")
            // GATT callbacks are dispatched on the main looper (MiScaleGattSession.dispatch), but
            // OppoRecordWriter.write -> BodyFatStore.b() is a Room write that forbids the main
            // thread (throws IllegalStateException: Cannot access database on the main thread).
            // Run the write and the disconnect off the main thread.
            Thread({
                val defaultTag = runCatching { OppoRecordWriter.ssoid(cl) }.getOrNull() ?: ""
                val member = FamilyMatcher.match(cl, ctx, rec.weightKg, defaultTag)
                BridgeLog.i("MEMBERPROFILE tag=${member.tag.take(8)}… name=${member.name} " +
                    "sex=${member.sex} age=${member.age} height=${member.heightMm}mm ref=${member.lastWeight}kg")
                val ok = OppoRecordWriter.write(cl, ctx, rec.weightKg, (rec.resistanceOhm ?: 0).toFloat(), rec.timestampEpochSeconds, member.tag)
                BridgeLog.i("AUTORECORD write ${if (ok) "OK" else "FAILED"} tag=${member.tag.take(8)}… name=${member.name}")
                driver.disconnect(mac)
            }, "oh-autorecord-write").start()
        }, { }, { e ->
            BridgeLog.e("AUTORECORD measure error", e)
            driver.disconnect(mac)
        })
    }
}
