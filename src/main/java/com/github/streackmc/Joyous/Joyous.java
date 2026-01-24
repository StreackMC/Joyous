package com.github.streackmc.Joyous;

import java.io.File;

import org.bukkit.plugin.java.JavaPlugin;

import com.github.streackmc.StreackLib.utils.SConfig;

public class Joyous {

  public static final Long CONFIG_VERSION = 000001L;

  public static SConfig conf;
  public static JavaPlugin plugin;
  public static File dataPath;

  /**
   * 是否启用调试模式
   * @return 启用状态
   * @since 0.0.1
   */
  public static boolean isDebugMode() {
    return conf.getBoolean("debug", false);
  }

  /**
   * 获取配置文件版本差异
   * 负数表示低于当前版本，正数表示高于当前版本，0表示相同版本
   * @return 差异版本数量
   * @since 0.0.1
   */
  public static int getConfigVerisonDiff() {
    Long cfgVer = conf.getLong("config-version", 000000L);
    return Long.compare(cfgVer, CONFIG_VERSION);
  }

  private Joyous() {
  }
}
