package io.github.brucezhang1993.ohealthdevicebridge.device.yoda

import android.content.Context
import io.github.brucezhang1993.ohealthdevicebridge.BridgeConstants
import io.github.brucezhang1993.ohealthdevicebridge.BridgeLog
import io.github.brucezhang1993.ohealthdevicebridge.device.BridgeDevice
import io.github.brucezhang1993.ohealthdevicebridge.device.DeviceDriver
import io.github.brucezhang1993.ohealthdevicebridge.device.MeasurementRecord
import io.github.brucezhang1993.ohealthdevicebridge.device.UserProfile
import io.github.brucezhang1993.ohealthdevicebridge.store.ScalePrefs

/**
 * Driver for the JD Health LEN-1001 ("Yoda0") scale.
 *
 * This is a pure-advertisement device: there is no GATT connection, no history transport and no
 * pairing handshake. The settled weight + impedance arrive in the manufacturer AD structure, so
 * [measure] simply hands back the last locked frame that [scan] observed.
 */
object YodaScaleDriver : DeviceDriver {
    override val model: String = BridgeConstants.YODA_MODEL

    @Volatile private var latest: Pair<String, YodaScaleProtocol.Frame>? = null
    @Volatile private var lastReportedSeq = -1
    @Volatile private var lastReportedAt = 0L

    /** A session counter wraps at 256; a repeat after this long is treated as a new weighing. */
    private const val SEQ_DEDUP_MS = 10 * 60 * 1000L

    override fun scan(
        context: Context,
        timeoutMs: Long,
        onDevice: (BridgeDevice) -> Unit,
        onTimeout: () -> Unit,
        onError: (Throwable) -> Unit,
    ): () -> Unit {
        // Once bound, only listen to that exact scale. Before pairing (or when a different model
        // is bound) stay open so the pairing page can discover a new one.
        val binding = ScalePrefs.load(context)
        val boundMac = binding?.mac?.takeIf { binding.model.equals(model, ignoreCase = true) }
        var reported = false
        return YodaBleScan().start(
            context, boundMac, timeoutMs,
            { scan, frame ->
                if (!frame.locked) return@start
                latest = scan.device.address to frame
                val now = System.currentTimeMillis()
                if (frame.sequence == lastReportedSeq && now - lastReportedAt < SEQ_DEDUP_MS) {
                    return@start
                }
                lastReportedSeq = frame.sequence
                lastReportedAt = now
                reported = true
                BridgeLog.i(
                    "YODA frame w=${frame.weightKg} r=${frame.resistanceOhm} seq=${frame.sequence} mac=${BridgeLog.maskedMac(scan.device.address)}"
                )
                latest = scan.device.address to frame
                onDevice(BridgeDevice(scan.device.address, "京东健康 LEN-1001 体脂秤", model))
            },
            { if (!reported) onTimeout() },
            onError,
        )
    }

    override fun verifyForBind(
        context: Context,
        mac: String,
        onSuccess: () -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        var done = false
        YodaBleScan().start(
            context, mac, 30_000L,
            { scan, frame ->
                if (done) return@start
                latest = scan.device.address to frame
                done = true
                onSuccess()
            },
            { if (!done) onError(IllegalStateException("未发现体脂秤，请踩一下秤后再试")) },
            onError,
        )
    }

    override fun measure(
        context: Context,
        mac: String,
        profile: UserProfile,
        onLiveWeight: (Double) -> Unit,
        onFinal: (MeasurementRecord) -> Unit,
        onHistory: (MeasurementRecord) -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        val frame = latest?.takeIf { it.first.equals(mac, ignoreCase = true) }?.second
        if (frame == null) {
            onError(IllegalStateException("没有可用的称重数据"))
            return
        }
        onLiveWeight(frame.weightKg)
        onFinal(
            MeasurementRecord(
                timestampEpochSeconds = System.currentTimeMillis() / 1000,
                weightKg = frame.weightKg,
                // A zero impedance means the electrodes did not get a valid reading. Keep the
                // row weight-only rather than feeding a fake impedance into the body-fat model.
                resistanceOhm = frame.resistanceOhm,
            )
        )
    }

    override fun disconnect(mac: String) {
        // Nothing to tear down for a broadcast-only device. The cached frame is intentionally
        // kept, because the caller may invoke measure() again within the same session.
    }
}
