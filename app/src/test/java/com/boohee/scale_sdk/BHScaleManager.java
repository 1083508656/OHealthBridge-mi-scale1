package com.boohee.scale_sdk;
import com.boohee.scale_sdk.user.BHUserModel;
import com.boohee.scale_sdk.data.BHScaleModel;
public class BHScaleManager {
 public static class Builder { public BHUserModel userModel=new BHUserModel(); }
 private Builder builder=new Builder(); public int transportWrites; public boolean failCalculation;
 public Builder getBuilder(){return builder;}
 public BHScaleModel handleTheHistoryScaleModelDetailByScaleModel(BHScaleModel model){
  if(failCalculation)throw new IllegalStateException("algorithm failed");
  if(model.deviceModel.isConnectScale)transportWrites++;
  model.bodyfat=20;return model;
 }
}
