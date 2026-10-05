package com.github.streackmc.Joyous.Restarter;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.Month;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

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
import com.github.streackmc.Joyous.i18n;
import com.github.streackmc.Joyous.jlogger;
import com.github.streackmc.Joyous._Model.JoyousModel;
import com.github.streackmc.StreackLib.types.SConfig;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

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
  /** Bukkit 服务器实例（用 Bukkit.getServer() 而非 Joyous.plugin，避免类初始化顺序的隐式依赖） */
  private static final Server Server = Bukkit.getServer();
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
  /**
   * 本次关服是否已由本模块接管（计划关闭 / 主动重启）。
   * <p>
   * 只要为 true，{@code preventInterrupt} 就不得再介入——否则一次计划重启会被当成
   * "非正常关闭"重复触发，导致重启脚本被注册两次、执行两次。
   * <p>
   * 置位：{@link #scheduleStop(int, String)}（计划关闭）与 {@link #performRestart()} /
   * {@link #performShutdown()}（真正执行时兜底）；
   * 复位：{@link #cancelPlan()}（用户取消计划）。
   */
  private static volatile boolean exitIntent = false;

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
  /** 堆转储是否已完成（跨线程通信，仅用于判断能否继续重启流程） */
  private static volatile boolean heapDumpFinished = false;
  /** 等待堆转储完成的上限，超时后不再阻塞重启 */
  private static final long HEAP_DUMP_WAIT_LIMIT_MS = 5 * 60 * 1000L;

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
    /** GC 后地板（{@link #getOldGenFloor()}），不可用时为 -1 */
    final long floor;

    MemorySample(long timestamp, long oldGenUsed, long oldGenMax, long floor) {
      this.timestamp = timestamp;
      this.oldGenUsed = oldGenUsed;
      this.oldGenMax = oldGenMax;
      this.floor = floor;
    }
  }

  // ------------------------------------------------------------------------
  // 生命周期
  // ------------------------------------------------------------------------

  @Override
  public void onEnable() {
    // 复位关服意图（防止插件被重载后残留上一次的状态）
    exitIntent = false;

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

    // preventInterrupt：同时充当"是否由本模块自行接管重启"的总开关
    if (isNativeRestartAllowed()) {
      installShutdownHook();
      jlogger.info("Restarter | preventInterrupt 已启用，只有 /jstop 可以安全关闭服务器。");
    } else {
      jlogger.info("Restarter | preventInterrupt 未启用：本模块不自行重启，所有“重启”都将直接关闭服务器，需配合外部重启。");
    }

    // 自动重启（含内存检测、泄漏检测）的总开关是 autoRestart.timeout，<0 时整个 while 段都不会启动。
    // 这条日志是为了让"为什么检测没生效"变得可见——此前它只存在于配置注释里。
    if (getAutoRestartTimeout() < 0) {
      jlogger.info("Restarter | 自动重启未启用（Restarter.autoRestart.timeout < 0）：时间条件、内存检测与泄漏检测都不会启动。");
    }

    // 内存池探测结果
    if (oldGenPool != null) {
      jlogger.debug("Restarter | 老年代内存池: %s", oldGenPool.getName());
    } else {
      jlogger.warn("Restarter | 未找到老年代内存池，内存检测将使用整体堆内存。");
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

    // 关服前落盘假人名册。
    // 注意：这里不能再往调度器里塞任务（例如 runTask(performRestart)）——插件禁用后
    // Paper 会紧接着执行 cancelTasks(plugin)，连尚未执行的 pending 队列一起清空，
    // 那时入队的任务永远不会跑。preventInterrupt 的自动重启改由 JVM 关闭钩子负责。
    if (isFpEnabled()) {
      saveFakePlayers();
    }
  }

  // ------------------------------------------------------------------------
  // preventInterrupt — JVM 关闭钩子
  // ------------------------------------------------------------------------

  /**
   * 注册"非正常关闭则重启"的关闭钩子。
   * <p>
   * 关闭钩子在 {@code onDisable()} 之后、JVM 退出之前执行，是唯一能可靠看到
   * "服务器是否被计划外关闭"并做出反应的时机。
   * <p>
   * 已知边界：{@code Runtime.halt()} / {@code SIGKILL}（例如看门狗强杀、OOM 直接崩溃）
   * 不会执行关闭钩子，这类情况依赖 spigot.yml 的 {@code restart-on-crash}
   * 与本模块的内存预警重启兜底。
   */
  private void installShutdownHook() {
    final org.bukkit.plugin.Plugin owner = Joyous.plugin;
    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      try {
        // 插件被热重载（PlugMan 之类）时，旧类加载器的钩子还挂在 JVM 上，
        // 但 Joyous.plugin 已指向新实例；此时旧钩子必须闭嘴，否则会重复重启。
        if (Joyous.plugin != owner)
          return;
        if (!isPreventInterrupt() || exitIntent)
          return;
        if (!isRestartConfigured()) {
          jlogger.err("Restarter | preventInterrupt 检测到非正常关闭，但重启脚本未配置，无法自动重启，改为关闭。");
          return;
        }
        jlogger.warn("Restarter | 检测到非正常关闭！preventInterrupt 已启用，将尝试重启服务器。");
        launchRestartScript();
      } catch (Throwable t) {
        jlogger.err("Restarter | 关闭钩子执行失败：%s", t.getLocalizedMessage(), t);
      }
    }, "Joyous-Restarter-PreventInterrupt"));
  }

  /**
   * 直接启动 spigot.yml 中配置的重启脚本。
   * <p>
   * 这里刻意不复用 {@link Server#restart()}：该方法内部每次调用都会
   * {@code Runtime.addShutdownHook} 注册一份新的脚本执行钩子（非幂等），
   * 在关服流程中重复调用会导致脚本被执行多次。
   */
  private static void launchRestartScript() {
    File serverRoot = serverRoot();
    YamlConfiguration spigotConfig = YamlConfiguration.loadConfiguration(new File(serverRoot, "spigot.yml"));
    String scriptPath = spigotConfig.getString("settings.restart-script", "");
    if (scriptPath == null || scriptPath.isEmpty())
      return;

    File scriptFile = new File(scriptPath);
    if (!scriptFile.isAbsolute())
      scriptFile = new File(serverRoot, scriptPath);

    boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
    ProcessBuilder builder = windows
        ? new ProcessBuilder("cmd", "/c", scriptFile.getAbsolutePath())
        : new ProcessBuilder("sh", scriptFile.getAbsolutePath());
    builder.directory(serverRoot);
    builder.inheritIO();
    try {
      builder.start();
      jlogger.info("Restarter | 已通过 %s 启动重启脚本。", scriptFile.getAbsolutePath());
    } catch (IOException e) {
      jlogger.err("Restarter | 重启脚本启动失败（%s）：%s", scriptFile.getAbsolutePath(), e.getLocalizedMessage(), e);
    }
  }

  /** 服务器根目录（插件数据目录的上两级） */
  private static File serverRoot() {
    return Joyous.plugin.getDataFolder().getParentFile().getParentFile();
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
        jlogger.info("Restarter | 已从 fp.dat 迁移 %d 个假人到 dat.json", migrated.size());
      } catch (IOException e) {
        jlogger.err("Restarter | 迁移 fp.dat 失败: %s", e.getLocalizedMessage(), e);
      }
    }

    // 加载上次内存重启时间
    lastMemoryRestart = datStore.getLong("lastMemoryRestart", 0L);
  }

  // ------------------------------------------------------------------------
  // 计划重启/关闭
  // ------------------------------------------------------------------------

  /**
   * 计划重启服务器。
   * <p>
   * 两种模式下都可调用：
   * <ul>
   * <li>允许自行重启（{@code preventInterrupt=true}）：需要 spigot.yml 配置了重启脚本，
   * 否则拒绝并返回 false。</li>
   * <li>不自行重启（{@code preventInterrupt=false}）：本次"重启"退化为直接关闭服务器，
   * 由外部守护进程拉起，因此不要求重启脚本。</li>
   * </ul>
   *
   * @param seconds 倒计时秒数
   * @param reason  理由
   * @return true 如果计划成功；false 如果要求自行重启但重启脚本未配置
   */
  public static boolean scheduleRestart(int seconds, String reason) {
    if (!isNativeRestartAllowed()) {
      jlogger.info("Restarter | 当前未启用自行重启，本次“重启”将退化为直接关闭服务器（需配合外部重启）。");
      schedule(seconds, reason, true);
      return true;
    }
    if (!isRestartConfigured()) {
      jlogger.err("Restarter | 重启脚本未配置，拒绝执行重启。请在 spigot.yml 中设置 settings.restart-script。");
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
    schedule(seconds, reason, false);
  }

  private static void schedule(int seconds, String reason, boolean isRestart) {
    // 取消已有计划
    cancelCountdown();

    // 计划关闭意味着"这次关服是人为安排的"，preventInterrupt 不该再把它当异常；
    // 计划重启由 performRestart() 兜底置位。
    exitIntent = !isRestart;

    scheduled = true;
    restartMode = isRestart;
    countdownSeconds = seconds;
    totalSeconds = seconds;
    String effectiveReason = (reason != null && !reason.isEmpty())
        ? reason
        : i18n.tr("restarter.default-reason");
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
    Server.broadcast(LegacyComponentSerializer.legacySection().deserialize(i18n.tr(notifyKey, effectiveReason, seconds)));

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
        Server.broadcast(LegacyComponentSerializer.legacySection().deserialize(i18n.tr(notifyKey, RestarterMain.reason, countdownSeconds)));
      }
    }, 0L, 20L);
  }

  /**
   * 取消当前计划（只复位倒计时相关状态，<b>不动</b> {@link #exitIntent}）。
   * <p>
   * 刻意不在这里清 {@code exitIntent}：{@link #executeShutdown(boolean)} 会先取消倒计时、
   * 再执行重启/关闭，若此处清位，就会把已经确定好的"本次关服由本模块接管"标记抹掉，
   * 使关闭钩子误判为异常关闭。撤销意图请使用 {@link #cancelPlan()}。
   */
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

  /** 供 /jrestarter cancel 使用：取消计划，并撤销"本次关服已由本模块接管"的标记 */
  public static void cancelPlan() {
    cancelCountdown();
    exitIntent = false;
  }

  // ------------------------------------------------------------------------
  // 执行：语义化的重启/关闭方法
  // ------------------------------------------------------------------------

  /**
   * 保存状态并踢出所有在线玩家。
   * <p>
   * 顺序不可颠倒：假人名册是靠"扫描在线玩家"采集的，踢完人再采集只会得到空名单，
   * 进而把 dat.json 中的名册一并清掉。
   */
  private static void saveStateAndKickAllPlayers(String kickKey, Object... args) {
    saveStateBeforeRestart();
    for (Player p : new ArrayList<>(Server.getOnlinePlayers())) {
      p.kick(LegacyComponentSerializer.legacySection().deserialize(i18n.tr(kickKey, args)));
    }
  }

  /** 重启前保存状态（假人等） */
  private static void saveStateBeforeRestart() {
    if (isFpEnabled())
      saveFakePlayers();
  }

  /** 执行服务器重启（是否真的重启由 {@link #willRestartNatively()} 决定） */
  private static void performRestart() {
    performExit(true);
  }

  /** 执行服务器关闭 */
  private static void performShutdown() {
    performExit(false);
  }

  /**
   * 最终执行入口：延迟一拍后重启或关闭服务器。
   * <p>
   * {@code restartRequested} 为 true 且当前允许自行重启（{@code preventInterrupt=true}
   * 且 spigot.yml 已配置重启脚本）时才调用 {@link Server#restart()}；
   * 其余情况一律退化为 {@link Server#shutdown()}，由外部守护进程负责拉起。
   * <p>
   * 假人已在踢人前保存（见 {@link #saveStateAndKickAllPlayers(String, Object...)}）。
   */
  private static void performExit(boolean restartRequested) {
    // 本次关服由本模块接管：关闭钩子不得再把它当成"异常关闭"
    exitIntent = true;

    boolean nativeRestart = restartRequested && willRestartNatively();
    if (restartRequested && !nativeRestart) {
      jlogger.warn("Restarter | %s，本次改为直接关闭服务器（需配合外部重启）。",
          isNativeRestartAllowed() ? "重启脚本未配置" : "未启用自行重启（preventInterrupt=false）");
    }

    Server.getScheduler().runTaskLater(Joyous.plugin, () -> {
      if (nativeRestart) {
        jlogger.info("→\u200bJ\u200bo\u200by\u200bo\u200bu\u200bs\u200b←");
        Server.restart();
      } else {
        Server.shutdown();
      }
    }, 20L);
  }

  /** 倒计时结束后的执行入口 */
  private static void executeShutdown(boolean isRestart) {
    String bcKey = isRestart
        ? "restarter.shutting-down.restart-broadcast"
        : "restarter.shutting-down.stop-broadcast";
    Server.broadcast(LegacyComponentSerializer.legacySection().deserialize(i18n.tr(bcKey)));

    String kickKey = isRestart
        ? "restarter.shutting-down.restart-kick"
        : "restarter.shutting-down.stop-kick";
    saveStateAndKickAllPlayers(kickKey, reason);

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
    bossBar.setTitle(i18n.tr(bossKey, countdownSeconds));
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

      if (!checkTimeConditions())
        return;

      int timeout = getAutoRestartTimeout();
      // 配置可能被热重载为负值（关闭本功能），此时直接跳过，避免出现"倒数 -1 秒"这种怪象
      if (timeout < 0)
        return;

      String autoReason = i18n.tr("restarter.auto-restart.reason");

      // 要求自行重启但重启脚本没配：Server.restart() 只会停机不会重启，
      // 因此真正降级为"计划关闭"，而不是只打一条日志、实际什么都不做。
      // （未启用自行重启时不走这里——那种模式下"重启"本来就等于关闭，是预期行为）
      if (isNativeRestartAllowed() && !isRestartConfigured()) {
        jlogger.err("Restarter | 满足时间条件，但重启脚本未配置，改为关闭。请在 spigot.yml 中设置 settings.restart-script。");
        if (timeout == 0) {
          executeShutdown(false);
        } else {
          scheduleStop(timeout, autoReason);
        }
        return;
      }

      if (timeout == 0) {
        // 立即重启
        jlogger.info("Restarter | 满足时间条件，立即执行重启。");
        Server.broadcast(LegacyComponentSerializer.legacySection().deserialize(i18n.tr("restarter.auto-restart.immediate.broadcast")));
        saveStateAndKickAllPlayers("restarter.auto-restart.immediate.kick");
        performRestart();
      } else {
        scheduleRestart(timeout, autoReason);
      }
    }, 20L, 20L); // 每秒
  }

  /**
   * 检查时间条件。
   * <p>
   * 语义：
   * <ul>
   * <li><b>时刻</b>（{@code hour}/{@code min}/{@code sec}）之间是「与」关系，未配置或越界的字段视为通配。
   * 三者都配齐即表示"每天的那一秒"。</li>
   * <li><b>日期</b>（{@code weekday}/{@code days}/{@code dayOfM}）之间是「或」关系——它们只是
   * "哪些天"的不同表达；三者都未配置表示不限日期。</li>
   * <li>日期条件与时刻条件是「与」关系，即必须落在指定时刻、且落在指定日期。</li>
   * <li>完全没有任何有效条件时返回 false，避免"空配置等于每秒都满足"。</li>
   * </ul>
   * <p>
   * 取值一律走 {@link SConfig} 的类型化访问器：底层是 SnakeYAML，{@code 7.2} 会解析成
   * Double、{@code dayOfM} 里混排的整数会解析成 Integer，直接对 {@code getSection().get()}
   * 的结果做强转会在运行时抛 ClassCastException。
   */
  private static boolean checkTimeConditions() {
    final String base = "Restarter.autoRestart.when.";
    int hour = Joyous.conf.getInt(base + "hour", -1);
    int min = Joyous.conf.getInt(base + "min", -1);
    int sec = Joyous.conf.getInt(base + "sec", -1);
    int weekday = Joyous.conf.getInt(base + "weekday", -1);
    List<String> days = Joyous.conf.getListOfString(base + "days");
    List<String> dayOfM = Joyous.conf.getListOfString(base + "dayOfM");

    boolean hasHour = hour >= 0 && hour <= 23;
    boolean hasMin = min >= 0 && min <= 59;
    boolean hasSec = sec >= 0 && sec <= 59;
    boolean hasWeekday = weekday > 0;
    boolean hasDays = !days.isEmpty();
    boolean hasDayOfM = !dayOfM.isEmpty();

    if (!hasHour && !hasMin && !hasSec && !hasWeekday && !hasDays && !hasDayOfM)
      return false;

    LocalDateTime now = LocalDateTime.now();

    // 时刻：彼此为「与」，未配置即通配
    boolean timeMatched = (!hasHour || now.getHour() == hour)
        && (!hasMin || now.getMinute() == min)
        && (!hasSec || now.getSecond() == sec);
    if (!timeMatched)
      return false;

    // 日期：彼此为「或」，都未配置则不限日期
    if (!hasWeekday && !hasDays && !hasDayOfM)
      return true;
    return matchesWeekday(weekday, now)
        || matchesMonthDay(days, now)
        || matchesDayOfMonth(dayOfM, now.getDayOfMonth());
  }

  /**
   * 匹配星期：数字拼接，逐位取数字，例如 {@code 164} = 星期一、六、四。
   *
   * @param weekday 拼接值，<=0 视为未配置
   */
  private static boolean matchesWeekday(int weekday, LocalDateTime now) {
    if (weekday <= 0)
      return false;
    int today = now.getDayOfWeek().getValue(); // 1=Mon ... 7=Sun
    for (char c : String.valueOf(weekday).toCharArray()) {
      int day = c - '0';
      if (day >= 1 && day <= 7 && day == today)
        return true;
    }
    return false;
  }

  /** 匹配 {@code M.dd} 形式的指定日期，非法项忽略 */
  private static boolean matchesMonthDay(List<String> days, LocalDateTime now) {
    for (String day : days) {
      if (day == null)
        continue;
      String[] parts = day.trim().split("\\.");
      if (parts.length != 2)
        continue;
      try {
        int m = Integer.parseInt(parts[0].trim());
        int d = Integer.parseInt(parts[1].trim());
        if (m < 1 || m > 12 || d < 1)
          continue;
        // 按真实日历长度校验（2 月取 29），"2.30" 这类写错的日子会被忽略
        if (d > Month.of(m).maxLength())
          continue;
        if (m == now.getMonthValue() && d == now.getDayOfMonth())
          return true;
      } catch (NumberFormatException ignored) {
        // 非法值忽略
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
   * 除 {@code ?} 外的字符一律按字面量处理，避免配置里出现正则元字符时抛异常。
   */
  private static boolean matchesDayOfMonth(List<String> patterns, int day) {
    String dayStr = String.valueOf(day);
    for (String pattern : patterns) {
      if (pattern == null)
        continue;
      StringBuilder regex = new StringBuilder("^");
      String[] literals = pattern.split("\\?", -1);
      for (int i = 0; i < literals.length; i++) {
        if (i > 0)
          regex.append("\\d");
        regex.append(Pattern.quote(literals[i]));
      }
      regex.append("$");
      try {
        if (dayStr.matches(regex.toString()))
          return true;
      } catch (PatternSyntaxException ignored) {
        // 理论上不可能到达（已全部转义），留作兜底避免打死整个检查任务
      }
    }
    return false;
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

  /**
   * 泄漏检测的基线长度（分钟）。实际比较的是相邻两个等长的基线。
   * <p>
   * 需要权衡：基线太短则老年代的自然锯齿（两次回收之间的增长）会被误判为泄漏；
   * 基线越长噪声越小，但发现泄漏也越晚。
   */
  private static int getLeakBaselineMinutes() {
    return Math.max(1, Joyous.conf.getInt("Restarter.autoRestart.while.leakDetection.baselineMinutes", 10));
  }

  /**
   * 地板抬升阈值（百分比）。
   * <p>
   * 实测参考（JDK 24 + G1，常驻集固定的健康锯齿负载）：相邻基线地板抬升 0.0%～1.1%；
   * 每 tick 稳定泄漏的场景：抬升 25% 以上。默认 10% 有足够余量。
   */
  private static int getFloorGrowthPercent() {
    return Joyous.conf.getInt("Restarter.autoRestart.while.leakDetection.floorGrowthPercent", 10);
  }

  /** 判定地板是否有效所需的、跨度内的最小回落幅度（占上限的百分比） */
  private static final double LEAK_MIN_DROP_PERCENT = 3.0;

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

  /**
   * 获取老年代"GC 后地板"：最近一次 GC 之后的老年代占用。
   * <p>
   * 这是泄漏检测的核心信号——它天然是"回收后的水平"，不受采样滞后影响。
   * 拿不到时返回 -1（例如池不支持 collectionUsage、或退化为整体堆内存）。
   * <p>
   * 注意：<b>不要</b>改回去用 GarbageCollectorMXBean 的 Full GC 计数。
   * 现代 G1（JDK 14+）把并发周期拆到了独立的 {@code G1 Concurrent GC} bean，
   * 而 {@code G1 Old Generation} 只在真正的 Pause Full 时递增——实测老年代涨到 94%、
   * 跑了多次 Mixed GC 后它仍然是 0，任何以"发生过 Full GC"为前提的判断都会变成死分支。
   */
  private static long getOldGenFloor() {
    if (oldGenPool == null)
      return -1L;
    MemoryUsage postGc = oldGenPool.getCollectionUsage();
    return postGc == null ? -1L : postGc.getUsed();
  }

  /** 地板信号是否可用（不可用时泄漏检测无法工作） */
  private static boolean isFloorAvailable() {
    return getOldGenFloor() >= 0;
  }

  /** 启动内存检测（独立调度器，间隔由配置决定） */
  private void startMemoryCheck() {
    int interval = getMemoryCheckInterval();
    long intervalTicks = Math.max(1L, interval * 20L);

    jlogger.info("Restarter | 内存检测已启动，间隔 %d 秒，老年代阈值 %d%%", interval, getOldGenPercent());
    if (isLeakDetectionEnabled() && !isFloorAvailable()) {
      jlogger.warn("Restarter | 无法读取老年代 GC 后占用（collectionUsage 不可用），泄漏检测将不会生效。");
    }

    memoryCheckTask = Server.getScheduler().runTaskTimer(Joyous.plugin, () -> {
      if (scheduled)
        return;

      // 采样
      long now = System.currentTimeMillis();
      MemoryUsage usage = getOldGenUsage();

      if (usage.getMax() <= 0)
        return; // 无法检测

      MemorySample sample = new MemorySample(
          now, usage.getUsed(), usage.getMax(), getOldGenFloor());

      // 维护采样历史：泄漏检测要比较相邻两个基线，所以至少要留 2 个基线的长度
      synchronized (sampleWindow) {
        sampleWindow.add(sample);
        long keepMs = getLeakBaselineMinutes() * 2L * 60_000L + 60_000L;
        sampleWindow.removeIf(s -> (now - s.timestamp) > keepMs);
      }

      double oldGenRatio = (double) sample.oldGenUsed / sample.oldGenMax * 100;

      // 阈值检查
      if (oldGenRatio >= getOldGenPercent()) {
        consecutiveFailCount++;
        jlogger.debug("Restarter | %s",
            i18n.tr("restarter.auto-restart.memory.sample-failed",
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
   * 检查内存泄漏：比较相邻两个等长基线的「GC 后地板」中位数。
   * <p>
   * 判据（需同时成立）：
   * <ol>
   * <li>跨度内出现过明显回落（{@link #LEAK_MIN_DROP_PERCENT}），证明地板读数确实来自"回收之后"，
   * 否则（例如服务器空闲、根本没发生回收）直接不判；</li>
   * <li>两个基线各自的有效地板样本都足够多；</li>
   * <li>后一个基线的地板中位数相对前一个抬升 ≥ {@code floorGrowthPercent}。</li>
   * </ol>
   * <p>
   * 为什么比较"跨基线"而不是"窗内前后段"：老年代在两次回收之间的自然增长幅度很容易超过 10%，
   * 用同一个短窗口的前后段比较会把正常锯齿判成泄漏——实测健康负载下误报率极高。
   * 把比较对象换成相邻的等长基线后，锯齿的相位噪声会被中位数抹平：
   * 实测健康负载抬升 0.0%～1.1%，稳定泄漏抬升 25% 以上。
   * <p>
   * 已知局限：如果业务本身的常驻数据在基线跨度内显著增长（例如在线人数翻倍），
   * 地板也会抬升并被判为泄漏。这与"真泄漏"在信号上无法区分，
   * 由 {@code minRestartInterval} 的重启间隔下限兜底。
   *
   * @return true 如果检测到内存泄漏
   */
  private static boolean checkLeakDetection() {
    synchronized (sampleWindow) {
      long now = System.currentTimeMillis();
      long baselineMs = getLeakBaselineMinutes() * 60_000L;

      List<Long> prevFloors = new ArrayList<>();
      List<Long> curFloors = new ArrayList<>();
      long peak = 0, trough = Long.MAX_VALUE, max = 0;

      for (MemorySample s : sampleWindow) {
        long age = now - s.timestamp;
        if (age > baselineMs * 2)
          continue;
        peak = Math.max(peak, s.oldGenUsed);
        trough = Math.min(trough, s.oldGenUsed);
        max = Math.max(max, s.oldGenMax);
        if (s.floor <= 0)
          continue; // 地板不可用
        if (age <= baselineMs)
          curFloors.add(s.floor);
        else
          prevFloors.add(s.floor);
      }

      // 1. 跨度内必须出现过明显回落，地板读数才有意义
      if (max <= 0)
        return false;
      double drop = (double) (peak - trough) / max * 100.0;
      if (drop < LEAK_MIN_DROP_PERCENT)
        return false;

      // 2. 两个基线都要有足够的有效样本
      if (prevFloors.size() < 4 || curFloors.size() < 4)
        return false;

      // 3. 地板中位数抬升
      double prev = median(prevFloors);
      double cur = median(curFloors);
      if (prev <= 0)
        return false;
      double growth = (cur - prev) / prev * 100.0;

      if (growth >= getFloorGrowthPercent()) {
        jlogger.warn("Restarter | %s",
            i18n.tr("restarter.auto-restart.memory.leak-detected",
                prev / max * 100.0, cur / max * 100.0, growth, getFloorGrowthPercent()));
        return true;
      }

      return false;
    }
  }

  /** 中位数（入参不会被修改） */
  private static double median(List<Long> values) {
    List<Long> sorted = new ArrayList<>(values);
    sorted.sort(null);
    int n = sorted.size();
    if (n == 0)
      return -1;
    return n % 2 == 1
        ? sorted.get(n / 2)
        : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;
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
        jlogger.info("Restarter | %s",
            i18n.tr("restarter.auto-restart.memory.cooldown", minInterval));
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

    int timeout = getAutoRestartTimeout();

    // 确定重启理由
    String restartReason = isLeak
        ? i18n.tr("restarter.auto-restart.memory.leak-reason")
        : i18n.tr("restarter.auto-restart.memory.reason");

    // 内存泄漏时先生成堆转储，转储完成（或超时）后再走重启流程；
    // 否则重启会让 JVM 直接退出，写出一个截断的、无法分析的 .hprof。
    if (isLeak && isHeapDumpEnabled()) {
      jlogger.info("Restarter | 正在生成堆转储，完成后执行重启。");
      dumpHeapThen(getHeapDumpPath(), () -> applyMemoryRestart(timeout, restartReason, isLeak));
      return;
    }

    applyMemoryRestart(timeout, restartReason, isLeak);
  }

  /** 真正执行内存触发的重启（倒计时或立即） */
  private static void applyMemoryRestart(int timeout, String restartReason, boolean isLeak) {
    if (timeout == 0) {
      // 立即重启
      String bcKey = isLeak
          ? "restarter.auto-restart.memory.leak-broadcast"
          : "restarter.auto-restart.memory.broadcast";
      String kickKey = isLeak
          ? "restarter.auto-restart.memory.leak-kick"
          : "restarter.auto-restart.memory.kick";

      jlogger.info("Restarter | 内存触发立即重启（%s）。", isLeak ? "内存泄漏" : "内存压力");
      Server.broadcast(LegacyComponentSerializer.legacySection().deserialize(i18n.tr(bcKey)));
      saveStateAndKickAllPlayers(kickKey);
      performRestart();
    } else if (timeout > 0) {
      jlogger.info("Restarter | 内存触发计划重启（%s），倒计时 %d 秒。", isLeak ? "内存泄漏" : "内存压力", timeout);
      scheduleRestart(timeout, restartReason);
    }
  }

  /**
   * 在独立线程生成堆转储，完成后在主线程执行后续动作。
   * <p>
   * 堆转储（尤其 {@code live=true} 会先触发一次 Full GC）是重量级、会长时间停顿的操作，
   * 放在主线程会把整个服务器卡住；这里改为后台线程执行 + 主线程每秒轮询，
   * 超过 {@link #HEAP_DUMP_WAIT_LIMIT_MS} 仍未完成则不再等待，继续重启。
   *
   * @param dirPath 堆转储存放目录
   * @param proceed 转储完成或超时后执行的动作
   */
  private static void dumpHeapThen(String dirPath, Runnable proceed) {
    heapDumpFinished = false;
    Thread dumpThread = new Thread(() -> {
      try {
        dumpHeap(dirPath);
      } catch (Throwable t) {
        jlogger.err("Restarter | 堆转储线程异常：%s", t.getLocalizedMessage(), t);
      } finally {
        heapDumpFinished = true;
      }
    }, "Joyous-Restarter-HeapDump");
    dumpThread.setDaemon(true);
    dumpThread.start();

    long deadline = System.currentTimeMillis() + HEAP_DUMP_WAIT_LIMIT_MS;
    BukkitTask[] holder = new BukkitTask[1];
    holder[0] = Server.getScheduler().runTaskTimer(Joyous.plugin, () -> {
      if (!heapDumpFinished && System.currentTimeMillis() < deadline)
        return;
      holder[0].cancel();
      if (!heapDumpFinished)
        jlogger.warn("Restarter | 堆转储超时（%d 秒），继续执行重启。", HEAP_DUMP_WAIT_LIMIT_MS / 1000L);
      proceed.run();
    }, 20L, 20L);
  }

  /**
   * 生成堆转储文件
   * <p>
   * 使用 HotSpotDiagnosticMXBean 通过 JMX 调用，兼容所有 HotSpot JVM。
   * 可使用 VisualVM / JProfiler 分析生成的 .hprof 文件。
   * <p>
   * {@code live=true} 只输出可达对象、更贴近"真实占用"，代价是转储前会强制一次 Full GC。
   * 转储成功后按 {@code heapDump.keep} 清理旧文件，避免 dumps/ 撑满磁盘。
   *
   * @param dirPath 堆转储存放目录（相对于服务器根目录）
   */
  private static void dumpHeap(String dirPath) {
    try {
      MBeanServer server = ManagementFactory.getPlatformMBeanServer();
      ObjectName diagName = new ObjectName("com.sun.management:type=HotSpotDiagnostic");

      File serverRoot = serverRoot();
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

      jlogger.info("Restarter | %s",
          i18n.tr("restarter.auto-restart.memory.heap-dump-saved", dumpFile.getAbsolutePath()));

      pruneOldHeapDumps(dumpDir);
    } catch (Exception e) {
      jlogger.err("Restarter | %s",
          i18n.tr("restarter.auto-restart.memory.heap-dump-failed"), e);
    }
  }

  /** 只保留最近 {@code heapDump.keep} 份堆转储 */
  private static void pruneOldHeapDumps(File dumpDir) {
    int keep = Math.max(1, Joyous.conf.getInt("Restarter.autoRestart.while.heapDump.keep", 3));
    File[] dumps = dumpDir.listFiles((dir, name) -> name.endsWith(".hprof"));
    if (dumps == null || dumps.length <= keep)
      return;

    Arrays.sort(dumps, Comparator.comparingLong(File::lastModified).reversed());
    for (int i = keep; i < dumps.length; i++) {
      if (dumps[i].delete())
        jlogger.debug("Restarter | 已清理旧堆转储 %s", dumps[i].getName());
      else
        jlogger.warn("Restarter | 无法删除旧堆转储 %s", dumps[i].getAbsolutePath());
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

  /**
   * 通过命令添加假人，并<b>立即落盘</b>。
   * <p>
   * 名册是"重启后要恢复哪些假人"的唯一依据。只在关服流程里写盘是不够的：
   * 崩溃、强杀、插件重载都不会走到保存逻辑，新增条目会直接消失。
   */
  public static boolean addFakePlayer(String name) {
    boolean added = fakePlayers.add(name.toLowerCase());
    if (added)
      persistFakePlayers();
    return added;
  }

  /** 通过命令移除假人，并立即落盘 */
  public static boolean removeFakePlayer(String name) {
    boolean removed = fakePlayers.remove(name.toLowerCase());
    if (removed)
      persistFakePlayers();
    return removed;
  }

  /** 扫描在线玩家，将拥有假人权限的加入名册 */
  private static void collectFakePlayers() {
    String perm = getFpPerm();
    for (Player p : Server.getOnlinePlayers()) {
      if (p.hasPermission(perm)) {
        fakePlayers.add(p.getName().toLowerCase());
      }
    }
  }

  /**
   * 关服/重启前保存假人名册：先并入当前在线的假人，再落盘。
   * <p>
   * <b>必须在踢人之前调用</b>（见 {@link #saveStateAndKickAllPlayers(String, Object...)}），
   * 否则 {@link #collectFakePlayers()} 扫不到任何人，名册会被写成空。
   */
  static void saveFakePlayers() {
    collectFakePlayers();
    persistFakePlayers();
    jlogger.debug("Restarter | 已保存 %d 个假人到 dat.json", fakePlayers.size());
  }

  /** 把当前名册写入 dat.json（不扫描在线玩家） */
  private static void persistFakePlayers() {
    if (datStore == null)
      return;

    if (fakePlayers.isEmpty()) {
      datStore.remove("fakePlayers");
    } else {
      datStore.putListOfString("fakePlayers", new ArrayList<>(fakePlayers));
    }
    datStore.save();
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
    jlogger.debug("Restarter | 从 dat.json 加载了 %d 个假人", fakePlayers.size());
  }

  /**
   * 重启后恢复假人。
   * <p>
   * 恢复完成后<b>刻意不清空</b>名册，也不删除 dat.json 中的条目：名册是"重启后要恢复哪些假人"
   * 的常驻依据。若按"恢复即消费"处理，一旦服务器崩溃退出（{@code onDisable} 根本不会执行），
   * 名册已经在上一次启动时被抹掉，假人就再也回不来了。
   * 名册的增删只通过 {@code /jrestarter fp add|remove} 或踢人前的采集发生。
   */
  private static void recoverFakePlayers() {
    if (fakePlayers.isEmpty())
      return;

    long delayMs = Joyous.conf.getLong("Restarter.autoRestart.fp.delayOfJoin", 2000L);
    String spawnCmd = Joyous.conf.getString("Restarter.autoRestart.fp.spawn", "fp spawn %name%");
    String loginCmd = Joyous.conf.getString("Restarter.autoRestart.fp.login", "authme forcelogin %name%");
    long delayTicks = Math.max(1, delayMs / 50);

    List<String> toRecover = new ArrayList<>(fakePlayers);

    jlogger.info("Restarter | 将在 %d ms 后恢复 %d 个假人", delayMs, toRecover.size());

    Server.getScheduler().runTaskLater(Joyous.plugin, () -> {
      for (String name : toRecover) {
        // 生成假人
        String spawn = spawnCmd.replace("%name%", name);
        Server.dispatchCommand(Server.getConsoleSender(), spawn);
        jlogger.debug("Restarter | 已生成假人: %s", name);

        // 延迟登录
        Server.getScheduler().runTaskLater(Joyous.plugin, () -> {
          String login = loginCmd.replace("%name%", name);
          Server.dispatchCommand(Server.getConsoleSender(), login);
          jlogger.debug("Restarter | 假人已登录: %s", name);
        }, delayTicks);
      }
      jlogger.info("Restarter | 假人恢复完成（%d 个）", toRecover.size());
    }, delayTicks);
  }

  // ------------------------------------------------------------------------
  // 配置读取
  // ------------------------------------------------------------------------

  public static int getDefaultTimeout() {
    return Joyous.conf.getInt("Restarter.defaultTimeout", 60);
  }

  /**
   * 是否由本模块自行接管重启（配置项 {@code Restarter.preventInterrupt}）。
   * <p>
   * 它是本模块的"自行重启总开关"，同时决定两件事：
   * <ul>
   * <li>是否需要 {@code preventInterrupt} 保护——非 /jstop 的关闭会被转成重启；</li>
   * <li>下文所有"重启"是否真的重启。为 false 时它们一律退化为直接关闭服务器，
   * 需要外部守护进程（systemd / Docker / 面板）重新拉起。</li>
   * </ul>
   */
  public static boolean isPreventInterrupt() {
    return Joyous.conf.getBoolean("Restarter.preventInterrupt", false);
  }

  /** @see #isPreventInterrupt() */
  public static boolean isNativeRestartAllowed() {
    return isPreventInterrupt();
  }

  /** 本次"重启"最终是否会真的执行重启（而不是退化为关闭） */
  public static boolean willRestartNatively() {
    return isNativeRestartAllowed() && isRestartConfigured();
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
    File serverRoot = serverRoot();
    File spigotYml = new File(serverRoot, "spigot.yml");
    if (!spigotYml.exists()) {
      jlogger.warn("Restarter | 无法找到 spigot.yml，无法验证重启脚本配置。");
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
      jlogger.warn("Restarter | 重启脚本 %s 不存在。", scriptFile.getAbsolutePath());
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
