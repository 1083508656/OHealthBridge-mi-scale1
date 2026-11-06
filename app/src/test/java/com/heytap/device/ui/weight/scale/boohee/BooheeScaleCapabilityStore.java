package com.heytap.device.ui.weight.scale.boohee;
public class BooheeScaleCapabilityStore {
 public static final BooheeScaleCapabilityStore INSTANCE=new BooheeScaleCapabilityStore();public boolean skip=true;
 public void clearSkipHistoryUntilDisconnect(String mac){skip=false;}
}
