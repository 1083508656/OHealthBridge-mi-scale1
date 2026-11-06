package com.boohee.scale_sdk.device;
public class BHDeviceModel {
 public String deviceID, deviceMac, deviceModel, deviceName; public boolean isConnectScale;
 public BHDeviceModel(String mac,String name){deviceMac=mac;deviceName=name;}
 public void setDeviceId(String v){deviceID=v;} public void setDeviceMac(String v){deviceMac=v;}
 public void setDeviceModel(String v){deviceModel=v;} public void setDeviceName(String v){deviceName=v;}
}
