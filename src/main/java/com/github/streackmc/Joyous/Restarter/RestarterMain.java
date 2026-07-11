package com.github.streackmc.Joyous.Restarter;

import java.io.File;
import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.management.MBeanServer;
import javax.management.ObjectName;

import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.scheduler.BukkitTask;

import com.github.streackmc.Joyous.Joyous;
import com.github.streackmc.Joyous.logger;
import com.github.streackmc.Joyous._Model.JoyousModel;
import com.github.streackmc.StreackLib.utils.SConfig;

/**
 * 服务器重启/关闭管理模块
 * <p>
 * 提供计划重启、计划关闭、自动重启（时间条件 + 内存检测）、假人恢复等功能。
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
    /** 持久化数据文件（JSON 格式，通过 SConfig 管理） */
    public static final String DAT_FILE = "models/Restarter.dat.json";
    /** 旧版假人持久化文件（用于迁移） */
    public static final String OLD_FP_FILE = "models/Restarter.fp.dat";
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
  /** 时间条件自动重启检查任务（每秒） */
  private static volatile BukkitTask autoCheckTask = null;
  /** 内存检测任务（事件触发，独立调度） */
  private static volatile BukkitTask memoryCheckTask = null;
  /** 假人名册（小写） */
  public static volatile Set<String> fakePlayers = new HashSet<>();
  /** 持久化数据存储（JSON 格式） */
  private static volatile SConfig datStore = null;
  /** 是否由 /jstop 触发关闭（用于 preventInterrupt） */
  private static volatile boolean stoppedByJstop = false;

  // ------------------------------------------------------------------------
  // 内存监测状态
  // ------------------------------------------------------------------------

  /** 老年代内存池 */
  private static volatile MemoryPoolMXBean oldGenPool = null;
  /** 连续超过阈值的采样次数 */
  private static volatile int consecutiveFailCount = 0;
  /** 上次因内存原因重启的时间戳 */
  private static volatile long lastMemoryRestart = 0;
  /** 采样窗口 */
  private static final List<MemorySample> sampleWindow = new ArrayList<>();

  static {
    // 探测老年代内存池
    for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
      String name = pool.getName().toLowerCase();
      if (pool.getType() == MemoryType.HEAP
          && (name.contains("old") || name.contains("tenured") || name.contains("zheap"))) {
        oldGenPool = pool;
        break;
      }
    }
  }

  /** 内存采样数据 */
  private static class MemorySample {
    final long timestamp;
    final long oldGenUsed;
    final long oldGenMax;
    final long fullGCCount;

    MemorySample(long timestamp, long oldGenUsed, long oldGenMax, long fullGCCount) {
      this.timestamp = timestamp;
      this.oldGenUsed = oldGenUsed;
      this.oldGenMax = oldGenMax;
      this.fullGCCount = fullGCCount;
    }
  }

  // ------------------------------------------------------------------------
  // 生命周期
  // ------------------------------------------------------------------------

  @Override
  public void onEnable() {
    // 初始化持久化数据存储
    initDatStore();

    // 恢复假人
    if (isFpEnabled()) {
      loadFakePlayers();
      recoverFakePlayers();
    }

    // 启动时间条件自动重启检查
    if (getAutoRestartTimeout() >= 0) {
      startAutoCheck();
    }

    // 启动内存检测（独立于时间条件）
    if (getAutoRestartTimeout() >= 0 && getOldGenPercent() > 0) {
      startMemoryCheck();
    }

    // 监听玩家加入以更新 BossBar
    Bukkit.getPluginManager().registerEvents(new RestarterListener(), Joyous.plugin);

    CommandService.register();

    // preventInterrupt 提示
    if (isPreventInterrupt()) {
      logger.info("Restarter | preventInterrupt 已启用，只有 /jstop 可以安全关闭服务器。");
    }

    // 内存池探测结果
    if (oldGenPool != null) {
      logger.debug("Restarter | 老年代内存池: %s", oldGenPool.getName());
    } else {
      logger.warn("Restarter | 未找到老年代内存池，内存检测将使用整体堆内存。");
    }
  }

  @Override
  public void onDisable() {
    cancelCountdown();
    if (autoCheckTask != null) {
      autoCheckTask.cancel();
      autoCheckTask = null;
    }
    if (memoryCheckTask != null) {
      memoryCheckTask.cancel();
      memoryCheckTask = null;
    }

    // 如果 non-jstop 关闭且开启了 preventInterrupt，尝试重启
    if (isPreventInterrupt() && !stoppedByJstop) {
      if (!isRestartConfigured()) {
        logger.err("Restarter | preventInterrupt 已启用且检测到非正常关闭，但重启脚本未配置，无法自动重启，改为关闭。");
        return;
      }
      logger.warn("Restarter | 检测到非正常关闭！preventInterrupt 已启用，将尝试重启服务器。");
      if (isFpEnabled())
        saveFakePlayers();
      // 异步重启以避免阻塞关闭流程
      Server.getScheduler().runTask(Joyous.plugin, () -> performRestart());
      return;
    }

    // 正常重启/jstop 时保存假人
    if (scheduled && isFpEnabled()) {
      saveFakePlayers();
    }
  }

  // ------------------------------------------------------------------------
  // 持久化数据存储
  // ------------------------------------------------------------------------

  /** 初始化 dat.json，并处理旧版 fp.dat 迁移 */
  private void initDatStore() {
    Path datPath = Joyous.dataPath.toPath().resolve(NAMES.DAT_FILE);
    datStore = new SConfig(datPath, "json");

    // 旧版 fp.dat 迁移
    Path oldFile = Joyous.dataPath.toPath().resolve(NAMES.OLD_FP_FILE);
    if (Files.exists(oldFile) && !datStore.isExist("fakePlayers")) {
      try {
        List<String> lines = Files.readAllLines(oldFile);
        List<String> migrated = new ArrayList<>();
        for (String line : lines) {
          String name = line.trim().toLowerCase();
          if (!name.isEmpty())
            migrated.add(name);
        }
        datStore.putListOfString("fakePlayers", migrated);
        datStore.save();
        Files.deleteIfExists(oldFile);
        logger.info("Restarter | 已从 fp.dat 迁移 %d 个假人到 dat.json", migrated.size());
      } catch (IOException e) {
        logger.err("Restarter | 迁移 fp.dat 失败: %s", e.getLocalizedMessage(), e);
      }
    }

    // 加载上次内存重启时间
    lastMemoryRestart = datStore.getLong("lastMemoryRestart", 0L);
  }

  // ------------------------------------------------------------------------
  // 计划重启/关闭
  // ------------------------------------------------------------------------

  /**
   * 计划重启服务器
   *
   * @param seconds 倒计时秒数
   * @param reason  理由
   * @return true 如果计划成功；false 如果重启脚本未配置
   */
  public static boolean scheduleRestart(int seconds, String reason) {
    if (!isRestartConfigured()) {
      logger.err("Restarter | 重启脚本未配置，拒绝执行重启。请在 spigot.yml 中设置 settings.restart-script。");
      return false;
    }
    schedule(seconds, reason, true);
    return true;
  }

  /**
   * 计划关闭服务器
   *
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
        boolean wasRestart = restartMode;
        cancelCountdown();
        executeShutdown(wasRestart);
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

  // ------------------------------------------------------------------------
  // 执行：语义化的重启/关闭方法
  // ------------------------------------------------------------------------

  /** 踢出所有在线玩家 */
  private static void kickAllPlayers(String kickKey, Object... args) {
    for (Player p : new ArrayList<>(Server.getOnlinePlayers())) {
      p.kickPlayer(Joyous.i18n.tr(kickKey, args));
    }
  }

  /** 重启前保存状态（假人等） */
  private static void saveStateBeforeRestart() {
    if (isFpEnabled())
      saveFakePlayers();
  }

  /**
   * 执行服务器重启
   * <p>
   * 检查重启脚本配置 → 保存状态 → 延迟执行 {@link Server#restart()}
   */
  private static void performRestart() {
    if (!isRestartConfigured()) {
      logger.err("Restarter | 重启脚本未配置，将执行关闭而非重启。");
      Server.shutdown();
      return;
    }
    saveStateBeforeRestart();
    Server.getScheduler().runTaskLater(Joyous.plugin, () -> {
      logger.info("→\u200bJ\u200bo\u200by\u200bo\u200bu\u200bs\u200b←");
      Server.restart();
    }, 20L);
  }

  /**
   * 执行服务器关闭
   * <p>
   * 保存状态 → 延迟执行 {@link Server#shutdown()}
   */
  private static void performShutdown() {
    saveStateBeforeRestart();
    Server.getScheduler().runTaskLater(Joyous.plugin, () -> {
      Server.shutdown();
    }, 20L);
  }

  /** 倒计时结束后的执行入口 */
  private static void executeShutdown(boolean isRestart) {
    String bcKey = isRestart
        ? "restarter.shutting-down.restart-broadcast"
        : "restarter.shutting-down.stop-broadcast";
    Server.broadcastMessage(Joyous.i18n.tr(bcKey));

    String kickKey = isRestart
        ? "restarter.shutting-down.restart-kick"
        : "restarter.shutting-down.stop-kick";
    kickAllPlayers(kickKey, reason);

    if (isRestart)
      performRestart();
    else
      performShutdown();
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
  // 自动重启 — 时间条件（when）
  // ------------------------------------------------------------------------

  private static int getAutoRestartTimeout() {
    return Joyous.conf.getInt("Restarter.autoRestart.timeout", -1);
  }

  /** 启动时间条件检查（每秒，确保精确到秒的时间匹配） */
  private void startAutoCheck() {
    autoCheckTask = Server.getScheduler().runTaskTimer(Joyous.plugin, () -> {
      if (scheduled)
        return;

      if (checkTimeConditions()) {
        if (!isRestartConfigured()) {
          logger.err("Restarter | 满足时间条件，但重启脚本未配置，改为关闭。请在 spigot.yml 中设置 settings.restart-script。");
        }
        int timeout = getAutoRestartTimeout();
        if (timeout == 0) {
          // 立即重启
          logger.info("Restarter | 满足时间条件，立即执行重启。");
          Server.broadcastMessage(Joyous.i18n.tr("restarter.auto-restart.immediate.broadcast"));
          kickAllPlayers("restarter.auto-restart.immediate.kick");
          performRestart();
        } else {
          scheduleRestart(timeout, Joyous.i18n.tr("restarter.auto-restart.reason"));
        }
      }
    }, 20L, 20L); // 每秒
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
    String regex = "^" + pattern.replace("?", "\\d") + "$";
    return String.valueOf(day).matches(regex);
  }

  // ------------------------------------------------------------------------
  // 自动重启 — 内存检测（while，事件触发）
  // ------------------------------------------------------------------------

  /** 内存检测检查间隔（秒） */
  private static int getMemoryCheckInterval() {
    return Joyous.conf.getInt("Restarter.autoRestart.while.checkInterval", 20);
  }

  /** 老年代占用率阈值（百分比，<=0 禁用） */
  private static int getOldGenPercent() {
    return Joyous.conf.getInt("Restarter.autoRestart.while.oldGenPercent", 85);
  }

  /** 连续采样失败次数 */
  private static int getMemorySamples() {
    return Joyous.conf.getInt("Restarter.autoRestart.while.samples", 2);
  }

  /** 重启间隔下限（分钟） */
  private static int getMinRestartInterval() {
    return Joyous.conf.getInt("Restarter.autoRestart.while.minRestartInterval", 60);
  }

  /** 是否启用内存泄漏检测 */
  private static boolean isLeakDetectionEnabled() {
    return Joyous.conf.getBoolean("Restarter.autoRestart.while.leakDetection.enabled", true);
  }

  /** 泄漏检测时间窗口（分钟） */
  private static int getLeakWindowMinutes() {
    return Joyous.conf.getInt("Restarter.autoRestart.while.leakDetection.windowMinutes", 5);
  }

  /** Full GC 回收率阈值（百分比） */
  private static int getGCRecoveryThreshold() {
    return Joyous.conf.getInt("Restarter.autoRestart.while.leakDetection.gcRecoveryThreshold", 5);
  }

  /** 是否启用堆转储 */
  private static boolean isHeapDumpEnabled() {
    return Joyous.conf.getBoolean("Restarter.autoRestart.while.heapDump.enabled", false);
  }

  /** 堆转储目录 */
  private static String getHeapDumpPath() {
    return Joyous.conf.getString("Restarter.autoRestart.while.heapDump.path", "dumps/");
  }

  /** 获取老年代内存使用情况 */
  private static MemoryUsage getOldGenUsage() {
    if (oldGenPool != null) {
      return oldGenPool.getUsage();
    }
    // 回退到整体堆内存
    return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
  }

  /** 获取 Full GC 次数 */
  private static long getFullGCCount() {
    long count = 0;
    for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
      String name = gc.getName().toLowerCase();
      if (name.contains("old") || name.contains("marksweep")
          || name.contains("zgc") || name.contains("shenandoah")) {
        long c = gc.getCollectionCount();
        if (c > 0)
          count += c;
      }
    }
    return count;
  }

  /** 启动内存检测（独立调度器，间隔由配置决定） */
  private void startMemoryCheck() {
    int interval = getMemoryCheckInterval();
    long intervalTicks = Math.max(1L, interval * 20L);

    logger.info("Restarter | 内存检测已启动，间隔 %d 秒，老年代阈值 %d%%", interval, getOldGenPercent());

    memoryCheckTask = Server.getScheduler().runTaskTimer(Joyous.plugin, () -> {
      if (scheduled)
        return;

      // 采样
      long now = System.currentTimeMillis();
      MemoryUsage usage = getOldGenUsage();
      long fullGCCount = getFullGCCount();

      if (usage.getMax() <= 0)
        return; // 无法检测

      MemorySample sample = new MemorySample(
          now, usage.getUsed(), usage.getMax(), fullGCCount);

      // 维护采样窗口
      synchronized (sampleWindow) {
        sampleWindow.add(sample);
        int windowMs = getLeakWindowMinutes() * 60 * 1000;
        sampleWindow.removeIf(s -> (now - s.timestamp) > windowMs);
      }

      double oldGenRatio = (double) sample.oldGenUsed / sample.oldGenMax * 100;

      // 阈值检查
      if (oldGenRatio >= getOldGenPercent()) {
        consecutiveFailCount++;
        logger.debug("Restarter | %s",
            Joyous.i18n.tr("restarter.auto-restart.memory.sample-failed",
                oldGenRatio, getOldGenPercent(), consecutiveFailCount, getMemorySamples()));

        if (consecutiveFailCount >= getMemorySamples()) {
          triggerMemoryRestart(false);
          return;
        }
      } else {
        consecutiveFailCount = 0;
      }

      // 内存泄漏检测（独立于阈值，可提前触发）
      if (isLeakDetectionEnabled() && checkLeakDetection()) {
        triggerMemoryRestart(true);
      }
    }, intervalTicks, intervalTicks);
  }

  /**
   * 检查内存泄漏
   * <p>
   * 条件：时间窗口内老年代持续上涨，且 Full GC 后回收率低于阈值
   *
   * @return true 如果检测到内存泄漏
   */
  private static boolean checkLeakDetection() {
    synchronized (sampleWindow) {
      if (sampleWindow.size() < 2)
        return false;

      long now = System.currentTimeMillis();
      int windowMs = getLeakWindowMinutes() * 60 * 1000;

      // 获取窗口内的采样
      List<MemorySample> window = new ArrayList<>();
      for (MemorySample s : sampleWindow) {
        if (now - s.timestamp <= windowMs)
          window.add(s);
      }

      if (window.size() < 2)
        return false;

      MemorySample oldest = window.get(0);
      MemorySample newest = window.get(window.size() - 1);

      // 老年代必须呈上涨趋势
      if (newest.oldGenUsed <= oldest.oldGenUsed)
        return false;

      // Full GC 必须在窗口内发生过
      if (newest.fullGCCount <= oldest.fullGCCount)
        return false;

      // 计算回收率：(窗口起始占用 - 窗口结束占用) / 窗口起始占用
      // 如果回收率为负或低于阈值，说明 Full GC 无法有效回收
      double recoveryRate = (double) (oldest.oldGenUsed - newest.oldGenUsed)
          / oldest.oldGenUsed * 100;

      if (recoveryRate < getGCRecoveryThreshold()) {
        logger.warn("Restarter | %s",
            Joyous.i18n.tr("restarter.auto-restart.memory.leak-detected",
                (double) oldest.oldGenUsed / oldest.oldGenMax * 100,
                (double) newest.oldGenUsed / newest.oldGenMax * 100,
                recoveryRate, getGCRecoveryThreshold()));
        return true;
      }

      return false;
    }
  }

  /**
   * 触发内存重启
   *
   * @param isLeak true 表示因内存泄漏触发（可提前触发，不受阈值采样次数限制）
   */
  private static void triggerMemoryRestart(boolean isLeak) {
    // 重启间隔保护
    int minInterval = getMinRestartInterval();
    if (minInterval > 0 && lastMemoryRestart > 0) {
      long elapsed = System.currentTimeMillis() - lastMemoryRestart;
      long minMs = minInterval * 60L * 1000L;
      if (elapsed < minMs) {
        logger.info("Restarter | %s",
            Joyous.i18n.tr("restarter.auto-restart.memory.cooldown", minInterval));
        return;
      }
    }

    // 记录重启时间
    lastMemoryRestart = System.currentTimeMillis();
    if (datStore != null) {
      datStore.putLong("lastMemoryRestart", lastMemoryRestart);
      datStore.save();
    }

    // 重置采样
    consecutiveFailCount = 0;

    // 内存泄漏时生成堆转储
    if (isLeak && isHeapDumpEnabled()) {
      dumpHeap(getHeapDumpPath());
    }

    // 确定重启理由
    String restartReason = isLeak
        ? Joyous.i18n.tr("restarter.auto-restart.memory.leak-reason")
        : Joyous.i18n.tr("restarter.auto-restart.memory.reason");

    int timeout = getAutoRestartTimeout();

    if (timeout == 0) {
      // 立即重启
      String bcKey = isLeak
          ? "restarter.auto-restart.memory.leak-broadcast"
          : "restarter.auto-restart.memory.broadcast";
      String kickKey = isLeak
          ? "restarter.auto-restart.memory.leak-kick"
          : "restarter.auto-restart.memory.kick";

      logger.info("Restarter | 内存触发立即重启（%s）。", isLeak ? "内存泄漏" : "内存压力");
      Server.broadcastMessage(Joyous.i18n.tr(bcKey));
      kickAllPlayers(kickKey);
      performRestart();
    } else if (timeout > 0) {
      logger.info("Restarter | 内存触发计划重启（%s），倒计时 %d 秒。", isLeak ? "内存泄漏" : "内存压力", timeout);
      scheduleRestart(timeout, restartReason);
    }
  }

  /**
   * 生成堆转储文件
   * <p>
   * 使用 HotSpotDiagnosticMXBean 通过 JMX 调用，兼容所有 HotSpot JVM。
   * 可使用 VisualVM / JProfiler 分析生成的 .hprof 文件。
   *
   * @param dirPath 堆转储存放目录（相对于服务器根目录）
   */
  private static void dumpHeap(String dirPath) {
    try {
      MBeanServer server = ManagementFactory.getPlatformMBeanServer();
      ObjectName diagName = new ObjectName("com.sun.management:type=HotSpotDiagnostic");

      File serverRoot = Joyous.plugin.getDataFolder().getParentFile().getParentFile();
      File dumpDir = new File(dirPath);
      if (!dumpDir.isAbsolute())
        dumpDir = new File(serverRoot, dirPath);
      if (!dumpDir.exists())
        dumpDir.mkdirs();

      String fileName = "heapdump-" + System.currentTimeMillis() + ".hprof";
      File dumpFile = new File(dumpDir, fileName);

      server.invoke(diagName, "dumpHeap",
          new Object[] { dumpFile.getAbsolutePath(), true },
          new String[] { "java.lang.String", "boolean" });

      logger.info("Restarter | %s",
          Joyous.i18n.tr("restarter.auto-restart.memory.heap-dump-saved", dumpFile.getAbsolutePath()));
    } catch (Exception e) {
      logger.err("Restarter | %s",
          Joyous.i18n.tr("restarter.auto-restart.memory.heap-dump-failed"), e);
    }
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

  /** 持久化假人名单到 dat.json */
  static void saveFakePlayers() {
    collectFakePlayers();
    if (datStore == null)
      return;

    if (fakePlayers.isEmpty()) {
      datStore.remove("fakePlayers");
    } else {
      datStore.putListOfString("fakePlayers", new ArrayList<>(fakePlayers));
    }
    datStore.save();
    logger.debug("Restarter | 已保存 %d 个假人到 dat.json", fakePlayers.size());
  }

  /** 从 dat.json 读取假人名单 */
  private static void loadFakePlayers() {
    if (datStore == null)
      return;

    fakePlayers.clear();
    List<String> list = datStore.getListOfString("fakePlayers");
    if (list != null) {
      for (String name : list) {
        String n = name.trim().toLowerCase();
        if (!n.isEmpty())
          fakePlayers.add(n);
      }
    }
    logger.debug("Restarter | 从 dat.json 加载了 %d 个假人", fakePlayers.size());
  }

  /** 重启后恢复假人 */
  private static void recoverFakePlayers() {
    if (fakePlayers.isEmpty()) {
      // 清理持久化数据
      if (datStore != null && datStore.isExist("fakePlayers")) {
        datStore.remove("fakePlayers");
        datStore.save();
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
      // 清理持久化数据
      if (datStore != null && datStore.isExist("fakePlayers")) {
        datStore.remove("fakePlayers");
        datStore.save();
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

  /**
   * 检查服务器是否已配置重启脚本。
   * <p>
   * Paper 的 {@link Server#restart()} 在未配置 restart-script 时
   * 会直接停止服务器而非重启。
   *
   * @return true 如果 spigot.yml 中配置了 restart-script 且脚本文件存在
   */
  public static boolean isRestartConfigured() {
    File serverRoot = Joyous.plugin.getDataFolder().getParentFile().getParentFile();
    File spigotYml = new File(serverRoot, "spigot.yml");
    if (!spigotYml.exists()) {
      logger.warn("Restarter | 无法找到 spigot.yml，无法验证重启脚本配置。");
      return false;
    }
    YamlConfiguration spigotConfig = YamlConfiguration.loadConfiguration(spigotYml);
    String scriptPath = spigotConfig.getString("settings.restart-script", "");
    if (scriptPath == null || scriptPath.isEmpty()) {
      return false;
    }
    File scriptFile = new File(scriptPath);
    if (!scriptFile.isAbsolute()) {
      scriptFile = new File(serverRoot, scriptPath);
    }
    if (!scriptFile.exists()) {
      logger.warn("Restarter | 重启脚本 %s 不存在。", scriptFile.getAbsolutePath());
      return false;
    }
    return true;
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
