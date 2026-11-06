package io.github.brucezhang1993.ohealthdevicebridge.hook.oppo

import de.robv.android.xposed.*
import io.github.brucezhang1993.ohealthdevicebridge.device.DeviceRegistry
import io.github.brucezhang1993.ohealthdevicebridge.*

object ScaleRoutingHook{
    private const val INTENT_KEYS="com.heytap.health.devicemanager.third_device.bpg.IntentKeys";private const val CAPABILITY="com.heytap.device.ui.weight.scale.boohee.BooheeScaleCapabilityStore"
    fun install(cl:ClassLoader){val clazz=XposedHelpers.findClass(INTENT_KEYS,cl);hook(clazz,"isBooheeModel",true);hook(clazz,"isBooheeConnectModel",true);hook(clazz,"isBooheeBroadcastModel",false);XposedBridge.hookAllMethods(clazz,"resolveBodyFatVendor",object:XC_MethodHook(){override fun afterHookedMethod(param:MethodHookParam){if(isAfu(param.args.firstOrNull()))param.result="boohee"}});runCatching{hook(XposedHelpers.findClass(CAPABILITY,cl),"isConnectScale",true)}.onFailure{if(it is VirtualMachineError)throw it;BridgeLog.e("optional Boohee capability hook failed",it)};HookInstallState.routing=true;BridgeLog.i("routing hooks installed: AFU -> BOOHEE/CONNECT")}
    private fun hook(clazz:Class<*>,name:String,result:Boolean){val hooks=XposedBridge.hookAllMethods(clazz,name,object:XC_MethodHook(){override fun afterHookedMethod(param:MethodHookParam){if(isAfu(param.args.firstOrNull()))param.result=result}});check(hooks.isNotEmpty()){"No method $name on ${clazz.name}"}}
    private fun isAfu(v:Any?)=DeviceRegistry.byModel(v as? String)!=null
}
