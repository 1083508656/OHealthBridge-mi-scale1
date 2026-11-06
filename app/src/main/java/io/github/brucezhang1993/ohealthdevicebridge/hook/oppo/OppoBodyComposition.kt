package io.github.brucezhang1993.ohealthdevicebridge.hook.oppo

import android.content.Context
import io.github.brucezhang1993.ohealthdevicebridge.BridgeConstants
import io.github.brucezhang1993.ohealthdevicebridge.device.*

class OppoBodyComposition(private val classLoader: ClassLoader, private val factory: OppoObjectFactory) {
    fun prepare(manager: Any, context: Context, userTag: String?) {
        check(OppoReflect.call(manager, "ensureInit", context) == true) { "Boohee SDK initialization failed" }
        OppoReflect.call(manager, "refreshUserModelToSdk", userTag)
        checkNotNull(findUserModel(manager)) { "Boohee user profile unavailable" }
    }
    fun currentUserProfile(manager: Any): UserProfile {
        val user = checkNotNull(findUserModel(manager)) { "Boohee user profile unavailable" }
        val sex = OppoReflect.read(user, "getSex", "sex") as? Enum<*> ?: error("Unknown Boohee gender type")
        val male = when (sex.name) {
            "BHUserGenderMale" -> true
            "BHUserGenderFemale" -> false
            else -> error("Unknown Boohee gender")
        }
        val age = (OppoReflect.read(user, "getAge", "age") as Number).toInt()
        val target = (OppoReflect.read(user, "getWeight", "weight") as Number).toDouble()
        require(age in 5..120 && target > 0) { "Invalid Boohee user profile" }
        return UserProfile(age, male, target)
    }
    fun buildScaleModel(manager: Any, mac: String, record: MeasurementRecord, history: Boolean = false, deviceModel: String = BridgeConstants.AFU_MODEL): Any {
        val clazz = classLoader.loadClass(OppoObjectFactory.BH_SCALE_MODEL)
        val model = clazz.getConstructor().newInstance()
        OppoReflect.call(model, "setWeight", record.weightKg.toFloat())
        OppoReflect.call(model, "setBodyResistance", (record.resistanceOhm ?: 0).toFloat())
        OppoReflect.call(model, "setSecond", record.timestampEpochSeconds)
        OppoReflect.call(model, "setHistory", history)
        OppoReflect.call(model, "setLockData", true)
        OppoReflect.call(model, "setDeviceModel", factory.createBhDevice(mac, deviceModel))
        record.heartRateBpm?.let { OppoReflect.call(model, "setHeartRate", it) }
        if (record.resistanceOhm == null || record.resistanceOhm <= 0) return model
        val scaleManager = checkNotNull(findScaleManager(manager)) { "Boohee SDK unavailable" }
        val calculated = OppoReflect.call(scaleManager, "handleTheHistoryScaleModelDetailByScaleModel", model)
        check(clazz.isInstance(calculated)) { "ICOMON returned no result" }
        record.heartRateBpm?.let { OppoReflect.call(requireNotNull(calculated), "setHeartRate", it) }
        return requireNotNull(calculated)
    }
    fun importHistory(manager: Any, mac: String, record: MeasurementRecord, deviceModel: String = BridgeConstants.AFU_MODEL) {
        val model = buildScaleModel(manager, mac, record, true, deviceModel)
        val capability = classLoader.loadClass(CAPABILITY).getField("INSTANCE").get(null)!!
        // AFU binding is already complete; do not suppress its first independent history session.
        OppoReflect.call(capability, "clearSkipHistoryUntilDisconnect", mac)
        val importer = classLoader.loadClass("com.heytap.device.ui.weight.scale.boohee.BooheeUnclaimedImporter")
            .getField("INSTANCE").get(null)!!
        OppoReflect.call(importer, "importHistory", false, OppoReflect.read(model, "getDeviceModel", "deviceModel"), model)
    }
    private fun findScaleManager(manager: Any) = OppoReflect.fieldByType(manager, OppoObjectFactory.BH_SCALE_MANAGER)
    private fun findUserModel(manager: Any): Any? {
        val scale = findScaleManager(manager) ?: return null
        val builder = OppoReflect.call(scale, "getBuilder") ?: return null
        return OppoReflect.fieldByType(builder, OppoObjectFactory.BH_USER_MODEL)
    }
    companion object { const val CAPABILITY = "com.heytap.device.ui.weight.scale.boohee.BooheeScaleCapabilityStore" }
}
