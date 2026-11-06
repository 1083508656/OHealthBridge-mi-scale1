package com.heytap.device.ui.weight.scale.boohee;
import com.boohee.scale_sdk.device.BHDeviceModel;import com.boohee.scale_sdk.data.BHScaleModel;
public class BooheeUnclaimedImporter {
 public static final BooheeUnclaimedImporter INSTANCE=new BooheeUnclaimedImporter();public BHScaleModel received;
 public void importHistory(boolean binding,BHDeviceModel device,BHScaleModel model){
  if(binding||BooheeScaleCapabilityStore.INSTANCE.skip)throw new IllegalStateException("history suppressed");received=model;
 }
}
