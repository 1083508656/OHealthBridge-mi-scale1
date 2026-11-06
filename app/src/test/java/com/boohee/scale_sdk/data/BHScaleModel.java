package com.boohee.scale_sdk.data;
import com.boohee.scale_sdk.device.BHDeviceModel;
public class BHScaleModel {
 public int heartRate; public void setHeartRate(int v){heartRate=v;}
 public float weight, bodyResistance, bodyfat; public long second; public boolean isHistory,isLockData; public BHDeviceModel deviceModel;
 public void setWeight(float v){weight=v;} public void setBodyResistance(float v){bodyResistance=v;}
 public void setSecond(long v){second=v;} public void setHistory(boolean v){isHistory=v;}
 public void setLockData(boolean v){isLockData=v;} public void setDeviceModel(BHDeviceModel v){deviceModel=v;}
}
