package com.github.streackmc.Joyous._Model;

public abstract class JoyousModel {
  public static final String MODEL_NAME = "Unknown_or_unset";

  public String MODEL_NAME() {
    return MODEL_NAME;
  }

  public void onEnable() throws Exception {};

  public void onDisable() throws Exception {};
  
  public JoyousModel() {};
}