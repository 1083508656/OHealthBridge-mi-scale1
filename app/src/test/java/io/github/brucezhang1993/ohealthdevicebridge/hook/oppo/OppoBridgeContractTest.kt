package io.github.brucezhang1993.ohealthdevicebridge.hook.oppo

import android.content.Context
import android.content.ContextWrapper
import com.boohee.scale_sdk.BHScaleManager
import com.boohee.scale_sdk.data.BHScaleModel
import com.boohee.scale_sdk.device.BHDeviceModel
import com.boohee.scale_sdk.user.BHUserGender
import com.heytap.device.ui.weight.scale.BindableScaleDevice
import com.heytap.device.ui.weight.scale.boohee.BooheeUnclaimedImporter
import com.heytap.health.account.AccountHelper
import com.heytap.health.devicemanager.third_device.weightscale.WeightScaleDeviceInfo
import io.github.brucezhang1993.ohealthdevicebridge.device.MeasurementRecord
import org.junit.Assert.*
import org.junit.Test

/** Fixtures reflect the inspected 6.9.37 signatures; this does not execute the proprietary algorithm. */
class OppoBridgeContractTest {
    class Manager {
        var manager: BHScaleManager? = null
        var initializedWith: Context? = null
        var refreshed = false
        var allowInit = true
        fun ensureInit(context: Context): Boolean {
            initializedWith = context
            if (allowInit && manager == null) manager = BHScaleManager()
            return allowInit
        }
        fun refreshUserModelToSdk(tag: String?) { refreshed = true }
    }
    private val loader = javaClass.classLoader!!
    private val factory = OppoObjectFactory(loader)
    private val body = OppoBodyComposition(loader, factory)
    private val context = ContextWrapper(null)
    private val mac = "AA:BB:CC:DD:EE:FF"
    private val record = MeasurementRecord(1000, 61.05, 551)

    @Test fun firstUseInitializesWithContextAndRefreshesNullUserTag() {
        val manager = Manager(); body.prepare(manager, context, null)
        assertSame(context, manager.initializedWith); assertTrue(manager.refreshed)
        assertFalse(body.currentUserProfile(manager).male)
        manager.manager!!.builder.userModel.sex = BHUserGender.BHUserGenderMale
        assertTrue(body.currentUserProfile(manager).male)
    }
    @Test fun initializationFailureStopsBeforeProfileRefresh() {
        val manager = Manager(); manager.allowInit = false
        try { body.prepare(manager, context, null); fail("Expected failure") } catch (_: IllegalStateException) { }
        assertFalse(manager.refreshed)
    }
    @Test fun calculationCannotWriteToBooheeTransport() {
        val manager = Manager(); body.prepare(manager, context, null)
        val model = body.buildScaleModel(manager, mac, record) as BHScaleModel
        assertEquals(61.05f, model.weight, 0.001f); assertEquals(551f, model.bodyResistance, 0f)
        assertEquals(1000L, model.second); assertTrue(model.isLockData); assertFalse(model.isHistory)
        assertFalse(model.deviceModel.isConnectScale); assertEquals(0, manager.manager!!.transportWrites)
        val bindable = factory.createBindable(mac) as BindableScaleDevice
        assertTrue(bindable.isConnectScale); assertFalse((bindable.rawRef as BHDeviceModel).isConnectScale)
    }
    @Test fun algorithmFailureIsReportedInsteadOfSilentRawResult() {
        val manager = Manager(); body.prepare(manager, context, null); manager.manager!!.failCalculation = true
        try { body.buildScaleModel(manager, mac, record); fail("Expected failure") }
        catch (error: IllegalStateException) { assertEquals("algorithm failed", error.message) }
    }
    @Test fun overlayDoesNotNeedExistingDeviceTemplate() {
        val item = factory.createWeightDevice(mac, true) as WeightScaleDeviceInfo
        assertEquals(mac, item.id); assertEquals(mac, item.mac); assertEquals(100, item.deviceType)
        assertTrue(item.connectScale); assertTrue(item.connected)
    }
    @Test fun historyUsesNativeUnclaimedImporterAndHistoryFlag() {
        val manager = Manager(); body.prepare(manager, context, null); body.importHistory(manager, mac, record)
        val model = BooheeUnclaimedImporter.INSTANCE.received
        assertTrue(model.isHistory); assertEquals(551f, model.bodyResistance, 0f)
        assertEquals(0, manager.manager!!.transportWrites)
    }
    @Test fun staleTicketIsInvalidAfterAccountSwitch() {
        AccountHelper.getAccountManager().ssoid = "fixture-account-A"
        val ticket = OppoAfuRuntime.Ticket(OppoAccount.key(loader))
        assertTrue(ticket.valid(loader))
        AccountHelper.getAccountManager().ssoid = "fixture-account-B"
        assertFalse(ticket.valid(loader)); assertFalse(ticket.active)
    }
    @Test fun scanErrorCallbackReceivesTheOriginalThrowable() {
        val expected = IllegalStateException("Bluetooth disabled")
        var received: Throwable? = null
        val callback: (Throwable) -> Unit = { received = it }
        OppoReflect.call(callback, "invoke", expected)
        assertSame(expected, received)
    }
    @Test fun ticketCancellationRunsScanCleanupAndRejectsLateCallbacks() {
        val ticket = OppoAfuRuntime.Ticket(OppoAccount.key(loader)); var cancelled = 0
        ticket.cancelScan = { cancelled++ }; ticket.cancel(); ticket.cancel()
        assertEquals(1, cancelled); assertFalse(ticket.valid(loader))
    }
    @Test fun xiaomiRoutingRetainsModelAndHeartRateWithoutBooheeTransportWrites() {
        val manager = Manager(); body.prepare(manager, context, null)
        val device = io.github.brucezhang1993.ohealthdevicebridge.device.xiaomi.XiaomiModels.V1_BODY
        val bindable = factory.createBindable(mac, device) as BindableScaleDevice
        assertEquals(device, bindable.model)
        val scale = body.buildScaleModel(manager, mac, record.copy(heartRateBpm = 80), deviceModel = device) as BHScaleModel
        assertEquals(80, scale.heartRate)
        assertEquals(0, manager.manager!!.transportWrites)
        val weightOnly = body.buildScaleModel(manager, mac, MeasurementRecord(1001, 62.0, null), deviceModel = device) as BHScaleModel
        assertEquals(0, weightOnly.heartRate)
        assertEquals(0f, weightOnly.bodyResistance, 0f)
    }

}
