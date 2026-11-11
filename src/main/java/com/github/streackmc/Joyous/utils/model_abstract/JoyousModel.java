package com.github.streackmc.Joyous.utils.model_abstract;

public abstract class JoyousModel {

  abstract public String MODEL_NAME();

  public void onEnable() throws Exception {};

  public void onDisable() throws Exception {};
  
  public JoyousModel() {};
}