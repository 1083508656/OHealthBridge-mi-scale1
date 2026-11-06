package io.github.brucezhang1993.ohealthdevicebridge.device.afu

import io.github.brucezhang1993.ohealthdevicebridge.device.MeasurementRecord
import io.github.brucezhang1993.ohealthdevicebridge.device.UserProfile
import kotlin.math.abs

/** Protocol state runs on one thread. Android transport owns connection and write timeouts. */
internal class AfuMeasurementFlow(
    private val profile: UserProfile,
    private val historyOnly: Boolean,
    private val send: (ByteArray, String) -> Unit,
    private val onLive: (Double) -> Unit,
    private val onFinal: (MeasurementRecord) -> Unit,
    private val onHistory: (MeasurementRecord) -> Unit,
    private val onFinished: () -> Unit,
) {
    var state = AfuSessionState.HANDSHAKE
        private set
    private var pendingStored: AfuProtocol.StoredRecord? = null
    private var pendingLive: AfuProtocol.LiveWeight? = null
    private var weighStarted = 0L
    private var lastFrameAt = 0L
    private var lastWeight = 0
    private var locked = false
    private var retryHistory = false
    private var commitAt = 0L
    private var drainAt = 0L
    private var freshRecord: Pair<Long, Int>? = null
    private val imported = linkedSetOf<Pair<Long, Int>>()

    fun ready(now: Long, epoch: Long) {
        if (state != AfuSessionState.HANDSHAKE) return
        state = AfuSessionState.IDLE_HISTORY
        lastFrameAt = now
        send(historyDump(epoch), "history-dump")
        pendingStored?.let { pendingStored = null; stored(it, now, epoch) }
        pendingLive?.let { pendingLive = null; live(it, now, epoch) }
    }

    fun receive(frame: AfuProtocol.ParsedFrame, now: Long, epoch: Long) {
        if (state == AfuSessionState.FINISHED) return
        lastFrameAt = now
        when (frame) {
            is AfuProtocol.LiveWeight -> {
                if (state == AfuSessionState.HANDSHAKE) {
                    // Keep the locked frame if the scale emits it before the final handshake write.
                    if (pendingLive?.locked != true) pendingLive = frame
                } else live(frame, now, epoch)
            }
            is AfuProtocol.StoredRecord -> if (state == AfuSessionState.HANDSHAKE) pendingStored = frame else stored(frame, now, epoch)
            is AfuProtocol.Impedance -> {
                send(AfuProtocol.buildAck(AfuProtocol.WIRE_IMPEDANCE), "ack-impedance")
                if (state == AfuSessionState.COMMIT) {
                    send(profile(AfuProtocol.ProfileVariant.FINAL, epoch), "profile-final")
                    sync(epoch)
                }
            }
            is AfuProtocol.ReportChunk -> send(AfuProtocol.buildAck(0x14), "ack-report")
            else -> Unit
        }
    }

    fun poll(now: Long, epoch: Long) {
        when (state) {
            AfuSessionState.WAIT_IDLE -> if (now - lastFrameAt >= IDLE_MS) {
                state = AfuSessionState.COMMIT
                commitAt = now
                sync(epoch)
                send(profile(AfuProtocol.ProfileVariant.GET_WEIGH_AGAIN_1, epoch), "profile-4b")
                send(profile(AfuProtocol.ProfileVariant.GET_WEIGH_AGAIN_2, epoch), "profile-5b")
                send(AfuProtocol.buildChunkRequest(), "chunk-request")
            }
            AfuSessionState.COMMIT -> if (now - commitAt >= COMMIT_MS) {
                state = AfuSessionState.FINISHED
                try { onFinal(MeasurementRecord(epoch, lastWeight / 1000.0, null)) }
                finally { onFinished() }
            }
            AfuSessionState.DRAIN_HISTORY -> if (now - lastFrameAt >= IDLE_MS || now - drainAt >= COMMIT_MS) finish()
            AfuSessionState.IDLE_HISTORY -> if (historyOnly && now - lastFrameAt >= IDLE_MS) finish()
            else -> Unit
        }
    }

    fun cancel() { state = AfuSessionState.FINISHED; pendingLive = null; pendingStored = null }

    private fun live(frame: AfuProtocol.LiveWeight, now: Long, epoch: Long) {
        if (historyOnly || state == AfuSessionState.DRAIN_HISTORY || state == AfuSessionState.FINISHED) return
        if (weighStarted == 0L) {
            weighStarted = epoch
            state = AfuSessionState.LIVE
            send(profile(AfuProtocol.ProfileVariant.INITIAL, epoch), "profile-initial")
        }
        if (frame.weightGrams <= 0) return
        lastWeight = frame.weightGrams
        onLive(lastWeight / 1000.0)
        if (frame.locked && !locked) {
            locked = true
            state = AfuSessionState.WAIT_IDLE
            send(AfuProtocol.buildAck(AfuProtocol.WIRE_LIVE_WEIGHT), "ack-live-lock")
        }
    }

    private fun stored(frame: AfuProtocol.StoredRecord, now: Long, epoch: Long) {
        send(AfuProtocol.buildAck(AfuProtocol.WIRE_STORED, frame.sequence), "ack-stored")
        if (state == AfuSessionState.IDLE_HISTORY && !retryHistory) {
            retryHistory = true
            send(historyDump(epoch), "history-dump-retry")
        }
        if (frame.weightGrams <= 0) return
        val key = frame.timestampEpochSeconds to frame.weightGrams
        val record = MeasurementRecord(frame.timestampEpochSeconds, frame.weightGrams / 1000.0, frame.resistanceOhm.takeIf { it > 0 })
        if (state == AfuSessionState.COMMIT && isFresh(frame, epoch)) {
            freshRecord = key
            state = AfuSessionState.DRAIN_HISTORY
            drainAt = now
            onFinal(record)
            sync(epoch)
            send(historyDump(epoch), "cleanup-history-dump")
        } else if (key != freshRecord && imported.add(key)) {
            if (imported.size > MAX_HISTORY_KEYS) imported.remove(imported.first())
            onHistory(record)
        }
    }

    private fun isFresh(frame: AfuProtocol.StoredRecord, epoch: Long) = weighStarted > 0L &&
        frame.timestampEpochSeconds >= weighStarted - 60L && abs(epoch - frame.timestampEpochSeconds) <= 600L &&
        abs(frame.weightGrams - lastWeight) <= 1000

    private fun sync(epoch: Long) {
        send(AfuProtocol.buildTimeSync(epoch), "time-sync")
        send(AfuProtocol.buildSyncFirst(), "sync-first")
        send(AfuProtocol.buildSyncSecond(), "sync-second")
    }
    private fun profile(variant: AfuProtocol.ProfileVariant, epoch: Long) =
        AfuProtocol.buildProfile(variant, epoch, profile.userSlot, profile.age, profile.male, profile.targetKg)
    private fun historyDump(epoch: Long) = profile(AfuProtocol.ProfileVariant.HISTORY_DUMP, epoch)
    private fun finish() { state = AfuSessionState.FINISHED; onFinished() }

    companion object {
        const val IDLE_MS = 2000L
        const val COMMIT_MS = 15000L
        private const val MAX_HISTORY_KEYS = 256
    }
}
