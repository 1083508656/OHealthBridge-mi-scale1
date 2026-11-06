package io.github.brucezhang1993.ohealthdevicebridge.hook.oppo

import android.content.Context
import android.content.ContextWrapper
import com.boohee.scale_sdk.BHScaleManager
import com.heytap.device.ui.weight.scale.boohee.BooheeUnclaimedImporter
import com.heytap.health.account.AccountHelper
import io.github.brucezhang1993.ohealthdevicebridge.BridgeConstants
import io.github.brucezhang1993.ohealthdevicebridge.device.*
import io.github.brucezhang1993.ohealthdevicebridge.device.xiaomi.XiaomiModels
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class BooheeKeepAliveTest {
    class Manager {
        var manager: BHScaleManager? = null
        var stops = 0
        var allowInit = true
        fun ensureInit(context: Context): Boolean {
            if (allowInit) manager = BHScaleManager()
            return allowInit
        }
        fun refreshUserModelToSdk(tag: String?) { }
        fun forceStopKeepAlive(reason: String) { stops++; OppoAfuRuntime.stopBackground() }
    }

    private open class Driver(override val model: String) : DeviceDriver {
        var connected = false
        override fun isConnected(mac: String) = connected
        override fun scan(context: Context, timeoutMs: Long, onDevice: (BridgeDevice) -> Unit, onTimeout: () -> Unit, onError: (Throwable) -> Unit): () -> Unit = error("Unexpected scan")
        override fun verifyForBind(context: Context, mac: String, onSuccess: () -> Unit, onError: (Throwable) -> Unit): Unit = error("Unexpected bind")
        override fun measure(context: Context, mac: String, profile: UserProfile, onLiveWeight: (Double) -> Unit, onFinal: (MeasurementRecord) -> Unit, onHistory: (MeasurementRecord) -> Unit, onError: (Throwable) -> Unit): Unit = error("Unexpected measure")
        override fun disconnect(mac: String) { connected = false }
    }
    private class HistoryDriver(model: String) : Driver(model), HistoryDeviceDriver {
        var starts = 0
        var session = false
        lateinit var state: (Boolean) -> Unit
        lateinit var history: (MeasurementRecord) -> Unit
        lateinit var failure: (Throwable) -> Unit
        override fun hasSession(mac: String) = session
        override fun receiveHistory(context: Context, mac: String, profile: UserProfile, onHistory: (MeasurementRecord) -> Unit, onConnected: (Boolean) -> Unit, onError: (Throwable) -> Unit) {
            starts++; session = true
            state = { connected = it; onConnected(it) }
            history = onHistory; failure = onError
        }
    }

    private val loader = javaClass.classLoader!!
    private val context = ContextWrapper(null)
    private val mac = "AA:BB:CC:DD:EE:FF"
    private val manager = Manager()
    private val states = mutableListOf<Boolean>()
    private val onState: (Boolean) -> Unit = { states += it }
    private val record = MeasurementRecord(1000, 61.0, null)
    @Before fun reset() {
        OppoAfuRuntime.stopAll()
        AccountHelper.getAccountManager().ssoid = "keep-alive-account"
        BooheeUnclaimedImporter.INSTANCE.received = null
    }
    @After fun cleanup() { OppoAfuRuntime.stopAll() }
    private fun start(driver: DeviceDriver, account: String = OppoAccount.key(loader)) =
        BooheeKeepAliveHook.startHistory(loader, context, manager, account, mac, driver, onState)

    @Test fun gattModelsStartHistoryReportStateAndRetainModel() {
        for (model in listOf(BridgeConstants.AFU_MODEL, XiaomiModels.V1, XiaomiModels.V2)) {
            val driver = HistoryDriver(model)
            start(driver)
            assertEquals(1, driver.starts)
            assertEquals(model, OppoAfuRuntime.background!!.model)
            driver.state(true); driver.history(record); driver.state(false)
            val received = BooheeUnclaimedImporter.INSTANCE.received
            assertTrue(received.isHistory)
            assertEquals(model, received.deviceModel.deviceModel)
            assertEquals(listOf(true, false), states)
            assertFalse(OppoAfuRuntime.background!!.gaveUp)
            states.clear(); OppoAfuRuntime.stopBackground()
        }
    }
    @Test fun repeatedKeepAliveReusesActiveHistoryTransport() {
        val driver = HistoryDriver(XiaomiModels.V2)
        start(driver); driver.state(true); start(driver)
        assertEquals(1, driver.starts); assertEquals(1, manager.stops)
        assertEquals(listOf(true, true), states)
    }
    @Test fun foregroundScanAndBindKeepOwnershipAndCompleteCallback() {
        val driver = HistoryDriver(XiaomiModels.V1).apply { connected = true }
        OppoAfuRuntime.bind = OppoAfuRuntime.Ticket(OppoAccount.key(loader))
        start(driver)
        OppoAfuRuntime.stopBind()
        OppoAfuRuntime.scans[manager] = OppoAfuRuntime.Ticket(OppoAccount.key(loader))
        start(driver)
        assertEquals(0, driver.starts); assertEquals(0, manager.stops)
        assertEquals(listOf(true, true), states)
    }
    @Test fun disconnectThenFailureReportsFalseOnceAndMarksGiveUp() {
        val driver = HistoryDriver(XiaomiModels.V2)
        start(driver); driver.state(true); driver.state(false)
        driver.failure(IllegalStateException("fixture disconnect"))
        assertEquals(listOf(true, false), states)
        assertTrue(OppoAfuRuntime.background!!.gaveUp)
    }
    @Test fun preparationFailureReportsDisconnectedWithoutStartingDriver() {
        manager.allowInit = false
        val driver = HistoryDriver(XiaomiModels.V1)
        start(driver)
        assertEquals(0, driver.starts); assertEquals(listOf(false), states)
        assertTrue(OppoAfuRuntime.background!!.gaveUp)
    }
    @Test fun cancellationAndAccountSwitchRejectLateHistoryAndState() {
        val driver = HistoryDriver(XiaomiModels.V1)
        start(driver)
        val ticket = OppoAfuRuntime.background!!
        OppoAfuRuntime.stopBackground()
        driver.state(true); driver.history(record); driver.failure(IllegalStateException("late"))
        assertFalse(ticket.active); assertTrue(states.isEmpty())
        assertNull(BooheeUnclaimedImporter.INSTANCE.received)
        start(driver)
        val switched = OppoAfuRuntime.background!!
        AccountHelper.getAccountManager().ssoid = "other-account"
        driver.history(record); driver.state(true)
        assertFalse(switched.active); assertTrue(states.isEmpty())
        assertNull(BooheeUnclaimedImporter.INSTANCE.received)
    }
    @Test fun queuedRequestFromOldAccountCannotReplaceCurrentTicket() {
        val oldAccount = OppoAccount.key(loader)
        AccountHelper.getAccountManager().ssoid = "other-account"
        val current = OppoAfuRuntime.Ticket(OppoAccount.key(loader))
        OppoAfuRuntime.background = current
        val driver = HistoryDriver(XiaomiModels.V2)
        start(driver, oldAccount)
        assertSame(current, OppoAfuRuntime.background); assertTrue(current.active)
        assertEquals(0, driver.starts); assertTrue(states.isEmpty())
    }
}
