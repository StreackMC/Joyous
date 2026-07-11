package com.github.streackmc.Joyous.Restarter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.scheduler.BukkitTask;

import com.github.streackmc.Joyous.Joyous;
import com.github.streackmc.Joyous.logger;
import com.github.streackmc.Joyous._Model.JoyousModel;

/**
 * 服务器重启/关闭管理模块
 * <p>
 * 提供计划重启、计划关闭、自动重启、假人恢复等功能。
 *
 * @author kdxiaoyi
 * @since 0.2.0
 */
public class RestarterMain extends JoyousModel {
  public String MODEL_NAME() {
    return "Restarter";
  }

  public RestarterMain() {
  };

  public static final class NAMES {
    public static final String FP_SAVES = "models/Restarter.fp.dat";
    public static final String LOG_FILE = "logs/Restarter";
    public static final String PERMISSION_PREFIX = "joyous.restarter.";

    public static String PERMISSION_PREFIX(String txt) {
      return PERMISSION_PREFIX + txt;
    }
  }

  // ------------------------------------------------------------------------
  // 服务实例
  // ------------------------------------------------------------------------

  public static volatile RestarterCommand CommandService = new RestarterCommand();

  // ------------------------------------------------------------------------
  // 状态
  // ------------------------------------------------------------------------

  /** 是否已有计划的重启/关闭 */
  public static volatile boolean scheduled = false;
  /** 计划的是重启(true)还是关闭(false) */
  public static volatile boolean restartMode = false;
  /** 倒计时剩余秒数 */
  public static volatile int countdownSeconds = -1;
  /** 原始总秒数（用于 BossBar 进度计算） */
  private static volatile int totalSeconds = -1;
  /** 重启/关闭理由 */
  public static volatile String reason = "";
  /** Server Server */
  private static volatile Server Server = Joyous.plugin.getServer();
  /** 倒计时调度任务 */
  private static volatile BukkitTask countdownTask = null;
  /** BossBar 实例 */
  private static BossBar bossBar = null;
  /** 自动重启检查任务 */
  private static volatile BukkitTask autoCheckTask = null;
  /** 假人名册（小写） */
  public static volatile Set<String> fakePlayers = new HashSet<>();
  /** 假人持久化文件 */
  private static volatile Path fakePlayerFile = Joyous.dataPath.toPath().resolve(NAMES.FP_SAVES);
  /** 是否由 /jstop 触发关闭（用于 preventInterrupt） */
  private static volatile boolean stoppedByJstop = false;

  // ------------------------------------------------------------------------
  // 生命周期
  // ------------------------------------------------------------------------

  @Override
  public void onEnable() {
    // 恢复假人
    if (isFpEnabled()) {
      loadFakePlayers();
      recoverFakePlayers();
    }

    // 启动自动重启检查
    if (getAutoRestartTimeout() >= 0) {
      startAutoCheck();
    }

    // 监听玩家加入以更新 BossBar
    Bukkit.getPluginManager().registerEvents(new RestarterListener(), Joyous.plugin);

    CommandService.register();

    // preventInterrupt 提示
    if (isPreventInterrupt()) {
      logger.info("Restarter | preventInterrupt 已启用，只有 /jstop 可以安全关闭服务器。");
    }
  }

  @Override
  public void onDisable() {
    cancelCountdown();
    if (autoCheckTask != null) {
      autoCheckTask.cancel();
      autoCheckTask = null;
    }

    // 如果 non-jstop 关闭且开启了 preventInterrupt，尝试重启
    if (isPreventInterrupt() && !stoppedByJstop) {
      logger.warn("Restarter | 检测到非正常关闭！preventInterrupt 已启用，将尝试重启服务器。");
      if (isFpEnabled())
        saveFakePlayers();
      // 异步重启以避免阻塞关闭流程
      Server.getScheduler().runTask(Joyous.plugin, () -> {
        Server.restart();
      });
      return;
    }

    // save fake players for normal restart / jstop
    if (scheduled && isFpEnabled()) {
      saveFakePlayers();
    }
  }

  // ------------------------------------------------------------------------
  // 计划重启/关闭
  // ------------------------------------------------------------------------

  /**
   * 计划重启服务器
   * @param seconds 倒计时秒数
   * @param reason  理由
   */
  public static void scheduleRestart(int seconds, String reason) {
    schedule(seconds, reason, true);
  }

  /**
   * 计划关闭服务器
   * @param seconds 倒计时秒数
   * @param reason  理由
   */
  public static void scheduleStop(int seconds, String reason) {
    stoppedByJstop = true;
    schedule(seconds, reason, false);
  }

  private static void schedule(int seconds, String reason, boolean isRestart) {
    // 取消已有计划
    cancelCountdown();

    scheduled = true;
    restartMode = isRestart;
    countdownSeconds = seconds;
    totalSeconds = seconds;
    String effectiveReason = (reason != null && !reason.isEmpty())
        ? reason
        : Joyous.i18n.tr("restarter.default-reason");
    RestarterMain.reason = effectiveReason;

    // 初始化 BossBar
    if (isBossBarEnabled()) {
      if (bossBar == null) {
        bossBar = Server.createBossBar("", BarColor.RED, BarStyle.SOLID);
      }
      bossBar.setVisible(true);
      bossBar.removeAll();
      for (Player p : Server.getOnlinePlayers()) {
        bossBar.addPlayer(p);
      }
    }

    // 首次广播
    String notifyKey = isRestart ? "restarter.restart.notify" : "restarter.stop.notify";
    Server.broadcastMessage(Joyous.i18n.tr(notifyKey, effectiveReason, seconds));

    // 启动倒计时（每秒）
    countdownTask = Server.getScheduler().runTaskTimer(Joyous.plugin, () -> {
      countdownSeconds--;

      // 更新 BossBar
      updateBossBar();

      if (countdownSeconds <= 0) {
        cancelCountdown();
        executeShutdown();
        return;
      }

      // 关键节点广播
      if (countdownSeconds <= 5 || countdownSeconds == 10 || countdownSeconds == 30
          || (countdownSeconds <= 60 && countdownSeconds % 30 == 0)
          || (countdownSeconds > 60 && countdownSeconds % 60 == 0)) {
        Server.broadcastMessage(Joyous.i18n.tr(notifyKey, RestarterMain.reason, countdownSeconds));
      }
    }, 0L, 20L);
  }

  /** 取消当前计划 */
  public static void cancelCountdown() {
    if (countdownTask != null) {
      countdownTask.cancel();
      countdownTask = null;
    }
    if (bossBar != null) {
      bossBar.setVisible(false);
      bossBar.removeAll();
    }
    scheduled = false;
    restartMode = false;
    countdownSeconds = -1;
    totalSeconds = -1;
  }

  /** 执行关机/重启 */
  private static void executeShutdown() {
    String bcKey = restartMode ? "restarter.shutting-down.restart-broadcast" : "restarter.shutting-down.stop-broadcast";
    Server.broadcastMessage(Joyous.i18n.tr(bcKey));

    // 踢出所有玩家
    String kickKey = restartMode ? "restarter.shutting-down.restart-kick" : "restarter.shutting-down.stop-kick";
    for (Player p : new ArrayList<>(Server.getOnlinePlayers())) {
      p.kickPlayer(Joyous.i18n.tr(kickKey, reason));
    }

    // 保存假人
    if (isFpEnabled()) {
      saveFakePlayers();
    }

    // 延迟执行以确保踢出完成
    Server.getScheduler().runTaskLater(Joyous.plugin, () -> {
      if (restartMode) {
        Server.spigot().restart();
      } else {
        Server.shutdown();
      }
    }, 20L);
  }

  // ------------------------------------------------------------------------
  // BossBar
  // ------------------------------------------------------------------------

  private static boolean isBossBarEnabled() {
    return Joyous.conf.getBoolean("Restarter.showBossbar", true);
  }

  private static void updateBossBar() {
    if (bossBar == null || !bossBar.isVisible())
      return;

    double progress = totalSeconds > 0
        ? Math.max(0.0, (double) countdownSeconds / totalSeconds)
        : 0.0;
    bossBar.setProgress(progress);

    // 颜色随剩余时间变化
    if (countdownSeconds <= 5) {
      bossBar.setColor(BarColor.RED);
    } else if (countdownSeconds <= 15) {
      bossBar.setColor(BarColor.YELLOW);
    } else {
      bossBar.setColor(BarColor.GREEN);
    }

    String bossKey = restartMode ? "restarter.restart.bossbar" : "restarter.stop.bossbar";
    bossBar.setTitle(Joyous.i18n.tr(bossKey, countdownSeconds));
  }

  // ------------------------------------------------------------------------
  // 自动重启
  // ------------------------------------------------------------------------

  private static int getAutoRestartTimeout() {
    return Joyous.conf.getInt("Restarter.autoRestart.timeout", -1);
  }

  private void startAutoCheck() {
    // 每秒检查一次以确保精确到秒的时间匹配
    autoCheckTask = Server.getScheduler().runTaskTimer(Joyous.plugin, () -> {
      if (scheduled)
        return;

      if (shouldAutoRestart()) {
        int timeout = getAutoRestartTimeout();
        if (timeout == 0) {
          // 立即重启
          logger.info("Restarter | 满足自动重启条件，立即执行重启。");
          Server.broadcastMessage(Joyous.i18n.tr("restarter.auto-restart.immediate.broadcast"));
          for (Player p : new ArrayList<>(Server.getOnlinePlayers())) {
            p.kickPlayer(Joyous.i18n.tr("restarter.auto-restart.immediate.kick"));
          }
          if (isFpEnabled())
            saveFakePlayers();
          Server.getScheduler().runTaskLater(Joyous.plugin, () -> Server.spigot().restart(), 20L);
        } else {
          scheduleRestart(timeout, Joyous.i18n.tr("restarter.auto-restart.reason"));
        }
      }
    }, 20L, 20L); // 每秒
  }

  private static boolean shouldAutoRestart() {
    return checkTimeConditions() || checkWhileConditions();
  }

  /** 检查时间条件（OR 关系：任一配置的有效条件匹配即触发） */
  @SuppressWarnings("unchecked")
  private static boolean checkTimeConditions() {
    var when = Joyous.conf.getSection("Restarter.autoRestart.when");
    if (when == null || when.isEmpty())
      return false;

    LocalDateTime now = LocalDateTime.now();

    // 小时 (0-23，-1 或越界视为禁用)
    int hour = ((Number) when.getOrDefault("hour", -1)).intValue();
    if (hour >= 0 && hour <= 23 && now.getHour() == hour)
      return true;

    // 分钟 (0-59，-1 或越界视为禁用)
    int min = ((Number) when.getOrDefault("min", -1)).intValue();
    if (min >= 0 && min <= 59 && now.getMinute() == min)
      return true;

    // 秒 (0-59，-1 或越界视为禁用)
    int sec = ((Number) when.getOrDefault("sec", -1)).intValue();
    if (sec >= 0 && sec <= 59 && now.getSecond() == sec)
      return true;

    // 星期（数字拼接：164 = 星期一、六、四；非法值跳过）
    Object weekdayObj = when.get("weekday");
    if (weekdayObj != null) {
      try {
        String weekdayStr = String.valueOf(((Number) weekdayObj).intValue());
        int today = now.getDayOfWeek().getValue(); // 1=Mon ... 7=Sun
        for (char c : weekdayStr.toCharArray()) {
          int day = c - '0';
          if (day >= 1 && day <= 7 && day == today)
            return true;
        }
      } catch (NumberFormatException ignored) {
      }
    }

    // 指定日期 M.dd
    List<String> days = (List<String>) when.get("days");
    if (days != null && !days.isEmpty()) {
      for (String day : days) {
        try {
          String[] parts = day.split("\\.");
          if (parts.length != 2)
            continue;
          int m = Integer.parseInt(parts[0]);
          int d = Integer.parseInt(parts[1]);
          if (m >= 1 && m <= 12 && d >= 1 && d <= 31
              && m == now.getMonthValue() && d == now.getDayOfMonth())
            return true;
        } catch (NumberFormatException ignored) {
        }
      }
    }

    // dayOfM 通配符
    List<String> dayOfMList = (List<String>) when.get("dayOfM");
    if (dayOfMList != null && !dayOfMList.isEmpty()) {
      int today = now.getDayOfMonth();
      for (String pattern : dayOfMList) {
        if (matchesDayOfMonth(pattern, today))
          return true;
      }
    }

    return false;
  }

  /**
   * 匹配 dayOfM 模式：
   * <ul>
   * <li>"1"  - 精确匹配1日</li>
   * <li>"?5" - 匹配 5,15,25 日</li>
   * <li>"1?" - 匹配 10-19 日</li>
   * </ul>
   */
  private static boolean matchesDayOfMonth(String pattern, int day) {
    // 将 ? 替换为 \d 后做正则匹配
    String regex = "^" + pattern.replace("?", "\\d") + "$";
    return String.valueOf(day).matches(regex);
  }

  /** 检查附加条件（OR 关系：任一启用的条件匹配即触发） */
  private static boolean checkWhileConditions() {
    // 堆内存 OOM 检查（<=0 视为禁用）
    int oomPercent = Joyous.conf.getInt("Restarter.autoRestart.while.oomPercent", 0);
    if (oomPercent > 0) {
      Runtime rt = Runtime.getRuntime();
      long maxMemory = rt.maxMemory();
      long usedMemory = rt.totalMemory() - rt.freeMemory();
      double usedPercent = (double) usedMemory / maxMemory * 100;
      if (usedPercent >= oomPercent) {
        logger.debug("Restarter | OOM 条件触发: %.1f%% >= %d%%", usedPercent, oomPercent);
        return true;
      }
    }

    return false;
  }

  // ------------------------------------------------------------------------
  // 假人 (Fake Player) 管理
  // ------------------------------------------------------------------------

  private static boolean isFpEnabled() {
    return Joyous.conf.getBoolean("Restarter.autoRestart.fp.enabled", false);
  }

  /** 是否有权限识别假人 */
  private static String getFpPerm() {
    return Joyous.conf.getString("Restarter.autoRestart.fp.identify.perm", "streack.fakeplayer");
  }

  /** 是否允许通过命令添加假人 */
  private static boolean isFpCommandEnabled() {
    return Joyous.conf.getBoolean("Restarter.autoRestart.fp.identify.command", true);
  }

  /** 通过命令添加假人 */
  public static boolean addFakePlayer(String name) {
    return fakePlayers.add(name.toLowerCase());
  }

  /** 通过命令移除假人 */
  public static boolean removeFakePlayer(String name) {
    return fakePlayers.remove(name.toLowerCase());
  }

  /** 扫描在线玩家，将拥有假人权限的加入名单 */
  private static void collectFakePlayers() {
    String perm = getFpPerm();
    for (Player p : Server.getOnlinePlayers()) {
      if (p.hasPermission(perm)) {
        fakePlayers.add(p.getName().toLowerCase());
      }
    }
  }

  /** 持久化假人名单到文件 */
  static void saveFakePlayers() {
    collectFakePlayers();
    if (fakePlayers.isEmpty()) {
      try {
        Files.deleteIfExists(fakePlayerFile);
      } catch (IOException ignored) {
      }
      return;
    }
    try {
      Files.createDirectories(fakePlayerFile.getParent());
      Files.writeString(fakePlayerFile, String.join("\n", fakePlayers));
      logger.debug("Restarter | 已保存 %d 个假人到 %s", fakePlayers.size(), fakePlayerFile);
    } catch (IOException e) {
      logger.err("Restarter | 无法保存假人列表: %s", e.getLocalizedMessage(), e);
    }
  }

  /** 从文件读取假人名单 */
  private static void loadFakePlayers() {
    if (!Files.exists(fakePlayerFile))
      return;
    try {
      List<String> lines = Files.readAllLines(fakePlayerFile);
      fakePlayers.clear();
      for (String line : lines) {
        String name = line.trim().toLowerCase();
        if (!name.isEmpty()) {
          fakePlayers.add(name);
        }
      }
      logger.debug("Restarter | 从文件加载了 %d 个假人", fakePlayers.size());
    } catch (IOException e) {
      logger.err("Restarter | 无法读取假人列表: %s", e.getLocalizedMessage(), e);
    }
  }

  /** 重启后恢复假人 */
  private static void recoverFakePlayers() {
    if (fakePlayers.isEmpty()) {
      try {
        Files.deleteIfExists(fakePlayerFile);
      } catch (IOException ignored) {
      }
      return;
    }

    long delayMs = Joyous.conf.getLong("Restarter.autoRestart.fp.delayOfJoin", 2000L);
    String spawnCmd = Joyous.conf.getString("Restarter.autoRestart.fp.spawn", "fp spawn %name%");
    String loginCmd = Joyous.conf.getString("Restarter.autoRestart.fp.login", "authme forcelogin %name%");
    long delayTicks = Math.max(1, delayMs / 50);

    List<String> toRecover = new ArrayList<>(fakePlayers);

    logger.info("Restarter | 将在 %d ms 后恢复 %d 个假人", delayMs, toRecover.size());

    Server.getScheduler().runTaskLater(Joyous.plugin, () -> {
      // 清理持久化文件
      try {
        Files.deleteIfExists(fakePlayerFile);
      } catch (IOException ignored) {
      }

      for (String name : toRecover) {
        // 生成假人
        String spawn = spawnCmd.replace("%name%", name);
        Server.dispatchCommand(Server.getConsoleSender(), spawn);
        logger.debug("Restarter | 已生成假人: %s", name);

        // 延迟登录
        Server.getScheduler().runTaskLater(Joyous.plugin, () -> {
          String login = loginCmd.replace("%name%", name);
          Server.dispatchCommand(Server.getConsoleSender(), login);
          logger.debug("Restarter | 假人已登录: %s", name);
        }, delayTicks);
      }
      fakePlayers.clear();
      logger.info("Restarter | 假人恢复完成（%d 个）", toRecover.size());
    }, delayTicks);
  }

  // ------------------------------------------------------------------------
  // 配置读取
  // ------------------------------------------------------------------------

  public static int getDefaultTimeout() {
    return Joyous.conf.getInt("Restarter.defaultTimeout", 60);
  }

  public static boolean isPreventInterrupt() {
    return Joyous.conf.getBoolean("Restarter.preventInterrupt", false);
  }

  public static boolean isFpCommandCapable() {
    return isFpEnabled() && isFpCommandEnabled();
  }

  // ------------------------------------------------------------------------
  // 事件监听
  // ------------------------------------------------------------------------

  private class RestarterListener implements Listener {
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
      // 新玩家加入时同步到 BossBar
      if (scheduled && bossBar != null && bossBar.isVisible()) {
        bossBar.addPlayer(event.getPlayer());
      }
    }
  }
}
