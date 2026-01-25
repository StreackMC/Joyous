package com.github.streackmc.Joyous;

import java.io.File;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import com.github.streackmc.StreackLib.StreackLib;
import com.github.streackmc.StreackLib.utils.SConfig;

public class Joyous {

  public static final Long CONFIG_VERSION = 000001L;

  public static SConfig conf;
  public static JavaPlugin plugin;
  public static File dataPath;

  /**
   * 是否启用调试模式
   * 
   * @return 启用状态
   * @since 0.0.1
   */
  public static boolean isDebugMode() {
    // 继承StreackLib的调试状态
    return StreackLib.conf.getBoolean("debug", false);
  }

  /**
   * 获取配置文件版本差异
   * 负数表示低于当前版本，正数表示高于当前版本，0表示相同版本
   * 
   * @return 差异版本数量
   * @since 0.0.1
   */
  public static int getConfigVerisonDiff() {
    Long cfgVer = conf.getLong("config-version", 000000L);
    int diff = Long.compare(cfgVer, CONFIG_VERSION);// TODO: bug,无法正常检测
    logger.debug(String.format("配置文件版本：%d，适配版本：%d，差值：%d", cfgVer, CONFIG_VERSION, diff));
    return diff;
  }

  //TODO: 需要改进
  /**
   * 获取当前服务器的TPS数值（Ticks Per Second，每秒刻数）
   * <p>
   * 通过反射调用 Paper/Spigot 服务端方法获取性能数据。
   * 兼容 Paper（有实时TPS）和纯 Spigot（只有平均值）。
   * 
   * @return double[4] 数组，索引对应：
   *         [0] = 最近1秒的TPS（Paper为实时计算，Spigot为1分钟平均）
   *         [1] = 最近1分钟的平均TPS
   *         [2] = 最近5分钟的平均TPS
   *         [3] = 最近15分钟的平均TPS
   *         [4] = 时间戳
   *         如果发生非致命错误则会返回-1.0
   * @throws Exception 当服务器不支持TPS查询或反射调用失败时
   * @author KimiAI
   * @author kdxiaoyi 审计
   * @since 0.0.1
   */
  public static double[] getServerTPS() throws Exception {
    double[] tps = new double[5];
    tps[4] = System.currentTimeMillis();
    try {
      // 获取1m/5m/15m TPS
      java.lang.reflect.Method getTpsMethod = Bukkit.class.getMethod("getTPS");
      double[] paperTps = (double[]) getTpsMethod.invoke(null);
      tps[1] = paperTps[0];
      tps[2] = paperTps[1];
      tps[3] = paperTps[2];
      // 获取1s TPS
      try {
        java.lang.reflect.Method tickTimeMethod = Bukkit.class.getMethod("getAverageTickTime");
        double avgTickTime = (Double) tickTimeMethod.invoke(null);
        if (avgTickTime > 0) {
          tps[0] = 1000.0 / avgTickTime;
        } else {
          tps[0] = 20.0;
        }
      } catch (NoSuchMethodException e) {
        tps[0] = tps[1];
      }
      return tps;
    } catch (Exception e) {
      throw new Exception("获取TPS时发生未知错误：" + e.getLocalizedMessage(), e);
    }
  }

  private Joyous() {
  }
}
