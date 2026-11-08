package io.github.brucezhang1993.ohealthdevicebridge.hook.oppo

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import io.github.brucezhang1993.ohealthdevicebridge.BridgeConstants
import io.github.brucezhang1993.ohealthdevicebridge.BridgeLog
import io.github.brucezhang1993.ohealthdevicebridge.device.DeviceDriver
import io.github.brucezhang1993.ohealthdevicebridge.device.DeviceRegistry
import io.github.brucezhang1993.ohealthdevicebridge.device.MeasurementRecord
import io.github.brucezhang1993.ohealthdevicebridge.device.UserProfile
import io.github.brucezhang1993.ohealthdevicebridge.device.xiaomi.MiScaleDriver
import io.github.brucezhang1993.ohealthdevicebridge.device.xiaomi.XiaomiModels
import io.github.brucezhang1993.ohealthdevicebridge.store.ScalePrefs
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Route B stage 4 - measurement callback.
 * Runs in :SportDaemonService (where the SQLCipher database engine is initialised). It listens
 * for scale advertisements and, when a measurement appears, writes it into OPPO Health through
 * the verified h.m -> i0.d -> q0 -> BodyFatStore.b pipeline.
 *
 * The MAC is the one learned from the real BLE scan during pairing (ScalePrefs), never hardcoded.
 * With no binding yet it still scans generically, so a new scale can be discovered on its own.
 *
 * Thread model per driver:
 *  - Xiaomi scales advertise weight directly; their scan/measure tolerate a background thread.
 *  - AFU B1 is a GATT device whose driver asserts the main looper, so its scan + measure run on
 *    the main thread and the DB write (which sleeps) is posted to a worker thread.
 */
object XiaomiAutoRecord {
    private const val SCAN_TIMEOUT_MS = 20_000L
    // Long enough to swallow duplicate frames of one weighing, short enough that the next
    // stepping-on is not missed (the old 60s cooldown blocked back-to-back measurements).
    private const val COOLDOWN_MS = 20_000L
    private val main = Handler(Looper.getMainLooper())

    /** Last advertisement frame already handled, so re-advertised stale values are ignored. */
    @Volatile private var lastBroadcast: Pair<Long, Double>? = null

    /** Last measurement already persisted, so the GATT final callback can't double-write the same
     *  weighing that the advertisement path just wrote. */
    @Volatile private var lastWritten: Pair<Long, Double>? = null

    /** Debounce for auto-opening the measure page (avoid double-start on re-advertised frames). */
    @Volatile private var lastMeasurePageOpenAt = 0L

    /** Debounced write for the broadcast path; replaced every time a newer frame arrives. */
    @Volatile private var pendingWrite: Runnable = Runnable { }

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
                    val active = if (driver.model == BridgeConstants.AFU_MODEL) {
                        measureAfu(cl, appCtx, driver)
                    } else {
                        measureMi(cl, appCtx, driver, binding)
                    }
                    if (active) lastWriteAt = System.currentTimeMillis()
                } catch (t: Throwable) {
                    BridgeLog.e("AUTORECORD loop error", t as? Exception)
                    Thread.sleep(10_000)
                }
            }
        }, "oh-autorecord").start()
    }

    fun stop() { running = false }

    // --- Xiaomi path: unchanged behaviour (background-thread scan + measure). -------------------

    private fun measureMi(cl: ClassLoader, ctx: Context, driver: DeviceDriver, binding: ScalePrefs.Binding?): Boolean {
        var handled = false
        val done = AtomicBoolean(false)
        val mi = driver as? MiScaleDriver
        val stop = if (binding != null && mi != null) {
            BridgeLog.i("AUTORECORD listening on bound mac=${BridgeLog.maskedMac(binding.mac)}")
            mi.scanMac(ctx, binding.mac, SCAN_TIMEOUT_MS, { device ->
                handled = true
                measureOnce(cl, ctx, driver, device.mac)
                done.set(true)
            }, { BridgeLog.i("AUTORECORD no measurement this window"); done.set(true) }, { e ->
                BridgeLog.e("AUTORECORD scan error", e); done.set(true)
            }, { rec ->
                // The scale keeps re-advertising its LAST stable measurement with an unchanged
                // timestamp, so the same frame arrives over and over long after the user stepped
                // off. Only a frame carrying a new timestamp is a fresh weighing.
                val key = rec.timestampEpochSeconds to rec.weightKg
                if (key == lastBroadcast) {
                    BridgeLog.i("AUTORECORD broadcast duplicate t=${rec.timestampEpochSeconds}")
                    return@scanMac
                }
                lastBroadcast = key
                handled = true
                // A re-advertised stale measurement must not pull up the measure page (that is the
                // "no one standing on it, but 正在测量 pops up" bug). Only a frame with a recent
                // embedded timestamp counts as a live weighing.
                val age = System.currentTimeMillis() / 1000 - rec.timestampEpochSeconds
                val fresh = age in 0..120
                BridgeLog.i("AUTORECORD broadcast w=${rec.weightKg} r=${rec.resistanceOhm} t=${rec.timestampEpochSeconds} age=${age}s fresh=$fresh")
                if (fresh) {
                    // Pull up OPPO's own "measuring" page so the user sees the live weight, then
                    // settle it on the impedance-bearing frame below.
                    openMeasurePage(ctx, binding.mac, binding.model)
                    BodyFatMeasureHook.broadcastLive(
                        ctx, rec.weightKg, (rec.resistanceOhm ?: 0).toDouble(), stable = rec.resistanceOhm != null
                    )
                }
                // A broadcast that carries impedance is a settled measurement — the same data the
                // scale would send over GATT. Persist it right here so background weighing works
                // on any OPPO Health page without needing a GATT link (which the scale drops after
                // ~30s of silence, making the GATT path unreliable).
                if (rec.resistanceOhm != null && rec.weightKg >= 10.0) {
                    persist(cl, ctx, rec, "broadcast")
                }
            })
        } else {
            driver.scan(ctx, SCAN_TIMEOUT_MS, { device ->
                handled = true
                BridgeLog.i("AUTORECORD found ${BridgeLog.maskedMac(device.mac)}")
                measureOnce(cl, ctx, driver, device.mac)
                done.set(true)
            }, { BridgeLog.i("AUTORECORD scan timeout"); done.set(true) }, { e ->
                BridgeLog.e("AUTORECORD scan error", e); done.set(true)
            })
        }
        // Wait for the scan to finish (device found / timeout / error) instead of sleeping the
        // full timeout blind: that blind sleep made the scan window only ~44% of the cycle, so a
        // stepping-on landed in a gap half the time. Poll until the callback settles.
        val deadline = System.currentTimeMillis() + SCAN_TIMEOUT_MS + 5_000
        while (!done.get() && System.currentTimeMillis() < deadline) {
            Thread.sleep(200)
        }
        runCatching { stop() }
        if (handled) Thread.sleep(3_000)
        return handled
    }

    private fun measureOnce(cl: ClassLoader, ctx: Context, driver: DeviceDriver, mac: String) {
        // TODO: load the real OPPO family profile (height/age/sex) for exact body composition.
        val profile = UserProfile(age = 30, male = true, targetKg = 75.0)
        driver.measure(ctx, mac, profile, { w ->
            BridgeLog.i("AUTORECORD live %.2f kg".format(w))
            // GATT saw the scale before the advertisement did: pull up the measure page here too.
            openMeasurePage(ctx, mac, driver.model)
            // Mirror the live reading into OPPO's measure page. It lives in the main process, so
            // this has to travel as a broadcast instead of a direct call. stable=false keeps the
            // page animating instead of finalising on the first frame.
            BodyFatMeasureHook.broadcastLive(ctx, w, 0.0, stable = false)
        }, { rec ->
            BridgeLog.i("AUTORECORD final w=${rec.weightKg} r=${rec.resistanceOhm} t=${rec.timestampEpochSeconds}")
            // Settle the measure page on the final reading (with impedance): this is the only frame
            // marked stable, so the page finishes here and then shows the body-fat analysis.
            BodyFatMeasureHook.broadcastLive(ctx, rec.weightKg, (rec.resistanceOhm ?: 0).toDouble(), stable = true)
            // Plausible body weight only: the scale also advertises wake-up frames (a few kg).
            if (rec.weightKg >= 10.0) {
                persist(cl, ctx, rec, "final")
            }
            driver.disconnect(mac)
        }, { }, { e ->
            BridgeLog.e("AUTORECORD measure error", e)
            driver.disconnect(mac)
        })
    }

    /**
     * Pulls up OPPO's own "measuring" page (BodyFatMeasureActivity) so the user sees the live
     * weight instead of weighing blindly in the background. Reads its intent via getStringExtra, so
     * a plain component Intent works — no ARouter needed. vendor=boohee matches what the pairing
     * hooks already force for our models.
     */
    private fun openMeasurePage(ctx: Context, mac: String, model: String) {
        if (BodyFatMeasureHook.isMeasurePageOpen(ctx)) return  // already on the page
        val now = System.currentTimeMillis()
        if (now - lastMeasurePageOpenAt < 5_000L) return        // debounce re-advertised frames
        lastMeasurePageOpenAt = now
        runCatching {
            val intent = Intent().apply {
                setClassName("com.heytap.health", "com.heytap.device.ui.weight.BodyFatMeasureActivity")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra("body_fat_scale_vendor", "boohee")
                putExtra("body_fat_device_mac", mac)
                putExtra("key_device_mac", mac)
                putExtra("body_fat_device_type", model)
            }
            ctx.startActivity(intent)
            BridgeLog.i("AUTORECORD opened measure page for ${BridgeLog.maskedMac(mac)}")
        }.onFailure { BridgeLog.e("AUTORECORD open measure page failed", it as? Exception) }
    }

    /**
     * Persists one settled measurement.
     *
     * Who writes depends on whether OPPO's measure page is open:
     *
     *  - Page open: the page's own pipeline does it. The stable frame we broadcast lands in
     *    `BodyFatMeasureViewModel.l`, `c.onChanged` calls `u6()`, and `l.call()` inserts the row
     *    into table 1021 already attributed to the bound member. Writing here as well would
     *    duplicate the measurement, so the module stays out of the way.
     *  - Page closed: nobody else will, so the module writes it directly.
     *
     * Room rejects DB access from the main thread on 6.10.14+, and the BLE final callback runs on
     * the main looper, so the write always goes to a worker thread.
     */
    private fun persist(cl: ClassLoader, ctx: Context, rec: MeasurementRecord, tag: String) {
        if (rec.weightKg < 10.0) return
        val key = rec.timestampEpochSeconds to rec.weightKg
        if (key == lastWritten) {
            BridgeLog.i("AUTORECORD $tag skip already written")
            return
        }
        lastWritten = key
        val pageOpen = BodyFatMeasureHook.isMeasuring() || BodyFatMeasureHook.isMeasurePageOpen(ctx)
        if (pageOpen) {
            BridgeLog.i("AUTORECORD $tag: OPPO measure page owns the write")
            return
        }
        Thread({
            val saved = OppoRecordWriter.write(
                cl, ctx, rec.weightKg, (rec.resistanceOhm ?: 0).toFloat(), rec.timestampEpochSeconds
            )
            BridgeLog.i("AUTORECORD $tag write ${if (saved != null) "OK" else "FAILED"}")
        }, "oh-mi-write").start()
    }

    // --- AFU path: main-thread scan + measure, DB write on a worker thread. --------------------

    private fun measureAfu(cl: ClassLoader, ctx: Context, driver: DeviceDriver): Boolean {
        val latch = CountDownLatch(1)
        val wrote = AtomicBoolean(false)
        main.post {
            // TODO: load the real OPPO family profile (height/age/sex) for exact body composition.
            val profile = UserProfile(age = 30, male = true, targetKg = 75.0)
            driver.scan(ctx, SCAN_TIMEOUT_MS, { device ->
                BridgeLog.i("AUTORECORD AFU found ${BridgeLog.maskedMac(device.mac)}")
                driver.measure(ctx, device.mac, profile, { w ->
                    BridgeLog.i("AUTORECORD AFU live %.2f kg".format(w))
                }, { rec ->
                    BridgeLog.i("AUTORECORD AFU final w=${rec.weightKg} r=${rec.resistanceOhm} t=${rec.timestampEpochSeconds}")
                    wrote.set(true)
                    persist(cl, ctx, rec, "AFU")
                    runCatching { driver.disconnect(device.mac) }
                    latch.countDown()
                }, { }, { e ->
                    BridgeLog.e("AUTORECORD AFU measure error", e)
                    runCatching { driver.disconnect(device.mac) }
                    latch.countDown()
                })
            }, {
                BridgeLog.i("AUTORECORD AFU scan timeout")
                latch.countDown()
            }, { e ->
                BridgeLog.e("AUTORECORD AFU scan error", e)
                latch.countDown()
            })
        }
        try { latch.await(SCAN_TIMEOUT_MS + 150_000, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { }
        return wrote.get()
    }
}
