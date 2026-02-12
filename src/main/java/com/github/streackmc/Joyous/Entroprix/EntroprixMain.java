package com.github.streackmc.Joyous.Entroprix;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;

import com.github.streackmc.Joyous.Joyous;
import com.github.streackmc.Joyous.logger;
import com.github.streackmc.StreackLib.StreackLib;
import com.github.streackmc.StreackLib.utils.SConfig;
import com.github.streackmc.StreackLib.utils.SFile;

/**
 * 熵流抽卡系统主类
 * <p>
 * 基于 Paper 1.20.6 的随机抽卡插件。
 * 所有概率计算均基于权重动态进行，支持多保底规则、独立状态持久化。
 *
 * @author KimiAI 编写
 * @author Deepseek 审计
 * @author kdxiaoyi 编写提示词与审计
 */
public class EntroprixMain {
  private static final Path CONF_PATH = Joyous.dataPath.toPath().resolve(NAMES.CONF_FILE);
  private static final Path LOG_DIR = Joyous.dataPath.toPath().resolve(NAMES.LOG_FILE);
  private static final Random RANDOM = ThreadLocalRandom.current();

  public static final class NAMES {
    public static final String CONF_FILE = "models/Entroprix.yml";
    public static final String LOG_FILE = "logs/Entroprix";
    public static final String PERMISSION_PREFIX = "joyous.entroprix.";
    public static final String PLAYER_GUARANTEE_PREFIX = "entroprix.guarantee.";

    public static String PERMISSION_PREFIX(String txt) {
      return PERMISSION_PREFIX + txt;
    }

    public static NamespacedKey getGuaranteeKey(String guaranteeName) {
      return new NamespacedKey(Joyous.plugin, PLAYER_GUARANTEE_PREFIX + guaranteeName);
    }
  }

  // 服务实例
  public static final EntroprixPHAPI PlaceholderService = new EntroprixPHAPI();
  public static final EntroprixCommand CommandService = new EntroprixCommand();

  /** 卡池主配置文件 */
  public static SConfig poolList;

  // ------------------------------------------------------------------------
  // 生命周期
  // ------------------------------------------------------------------------

  public static void onEnable() {
    if (Files.notExists(CONF_PATH)) {
      try {
        logger.debug("检查到 %s 不存在，自动新建默认文件", CONF_PATH);
        SFile.mv(Joyous.getResourceAsFile("/" + NAMES.CONF_FILE), CONF_PATH.toFile());
      } catch (Exception e) {
        logger.err("警告：无法写入 %s ： %s", NAMES.CONF_FILE, e.getLocalizedMessage(), e);
      }
    }
    poolList = new SConfig(CONF_PATH, "yml");
    try {
      Files.createDirectories(LOG_DIR);
    } catch (IOException e) {
      logger.err("无法创建日志目录: %s", e.getLocalizedMessage());
    }
    PlaceholderService.register();
    CommandService.register();
  }

  public static void onDisable() {
    PlaceholderService.unregister();
  }

  // ------------------------------------------------------------------------
  // 核心抽卡方法
  // ------------------------------------------------------------------------

  /**
   * 为指定玩家执行抽卡
   *
   * @param player   目标玩家
   * @param poolName 卡池标识
   * @param times    抽取次数
   * @throws IllegalArgumentException 卡池/保底配置错误，或指定了非法的抽取次数
   */
  public static void roll(Player player, String poolName, int times) throws IllegalArgumentException {
    if (times <= 0) throw new IllegalArgumentException("次数不能为非整数，但发现了" + times);

    // 1. 获取卡池配置
    if (!poolList.getSection("pools").containsKey(poolName)) {
      throw new IllegalArgumentException(Joyous.i18n.tr("entroprix.pool.unknown", poolName));
    }
    Map<String, Object> poolConfig = poolList.getSection("pools." + poolName);
    String guaranteeName = (String) poolConfig.get("guarantee");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> rewardsConfig = (List<Map<String, Object>>) poolConfig.get("rewards");
    
    if (guaranteeName == null || rewardsConfig == null || rewardsConfig.isEmpty()) {
      throw new IllegalArgumentException(Joyous.i18n.tr("entroprix.pool.invaild", poolName));
    }
    
    // 2. 获取保底配置
    Map<String, Object> guaranteeConfig = poolList.getSection("guarantee." + guaranteeName);
    if (guaranteeConfig == null || guaranteeConfig.isEmpty()) {
      throw new IllegalArgumentException(Joyous.i18n.tr("entroprix.pool.missing_guarantee", poolName));
    }

    // 3. 解析奖励列表
    RewardSet rewardSet = RewardSet.fromConfig(rewardsConfig);

    for (int i = 1; i <= times; i++) { //TODO: 连续读写PDC,性能还可优化
      // 4. 加载玩家当前保底状态
      Guarantee guarantee = new Guarantee(player, guaranteeName);

      // 5. 使用概率计算器执行抽卡
      RollResult result = RateCalculator.roll(
          guarantee,
          guaranteeConfig,
          rewardSet);

      // 6. 更新保底状态（原子操作）
      result.applyTo(guarantee);

      // 7. 执行奖励命令
      executeCommands(player, result.getSelectedReward().getCommands());

      // 8. 记录日志
      AsyncLogger.log(player.getName(), poolName,
          guarantee.getCounts(), guarantee.getTries(),
          result.getSelectedReward().name, result.getResultType());
    }
  }

  // ------------------------------------------------------------------------
  // 概率计算引擎（独立子类，完全基于权重）
  // ------------------------------------------------------------------------

  /**
   * 概率计算器：根据当前状态与卡池配置，决定本次抽卡的奖励与保底变更。
   * <p>
   * 严格遵循「米池」规则：
   * - 权重制概率，非硬编码100
   * - 动态概率提升：剩余抽数 ≤ left 时，普通奖励概率按剩余次数均分给保底奖励
   * - 大小保底独立计数，仅抽取到大保底奖励才重置大保底计数
   * - 大保底状态下触发保底时强制抽取大保底奖励
   */
  private static class RateCalculator {

    /**
     * 执行一次概率判定
     *
     * @param guarantee       玩家保底状态
     * @param guaranteeConfig 保底规则配置
     * @param rewardSet       解析后的奖励集合
     * @return 包含选中奖励、结果类型及状态变更的完整结果
     */
    @SuppressWarnings("unused")
    public static RollResult roll(Guarantee guarantee,
        Map<String, Object> guaranteeConfig,
        RewardSet rewardSet) {
      /** 保底所需抽数 */
      int every = ((Number) guaranteeConfig.getOrDefault("every", 90)).intValue();
      /** 大保底是第几个保底 */
      int max = ((Number) guaranteeConfig.getOrDefault("max", 2)).intValue();
      /** 概率提升所需剩余抽数 */
      int left = ((Number) guaranteeConfig.getOrDefault("left", 0)).intValue();
      /** 已经抽了多少发 */
      int tries = guarantee.getTries();
      /** 已触发几个保底 */
      int counts = guarantee.getCounts();
      /** 是否在大保底内 */
      boolean isNextUp = guarantee.isNextUpGuaranteed();

      // 硬保底：已达保底上限，强制触发保底
      if (tries + 1 >= every) {
        return forcePity(rewardSet, isNextUp, true);
      }

      /** 剩余抽数（当前这次之后）*/
      int remaining = every - tries - 1;

      // 动态概率提升
      double boost = 0.0;
      if (remaining <= left/* 达到标准 */
          && remaining >= 0 /* 防止除0错误 */
          && rewardSet.commonTotalWeight > 0) {
        // 普通奖励的总概率 = 普通总权重 / 总权重
        double commonProb = rewardSet.commonTotalWeight / (double) rewardSet.totalWeight;
        // 每抽提升概率 = 普通总概率 / (剩余+1)
        boost = commonProb / (remaining + 1);
      }

      // 调整后的权重
      double adjustedUpWeight = rewardSet.upTotalWeight;
      double adjustedNormalWeight = rewardSet.normalTotalWeight;
      double adjustedCommonWeight = rewardSet.commonTotalWeight;

      if (boost > 0 && rewardSet.specialTotalWeight > 0) {
        // 将提升的概率转换为权重增量
        double boostWeight = boost * rewardSet.totalWeight; // 提升部分对应的权重值
        // 按原有权重比例分配给大小保底
        double upRatio = rewardSet.upTotalWeight / (double) rewardSet.specialTotalWeight;
        double normalRatio = rewardSet.normalTotalWeight / (double) rewardSet.specialTotalWeight;

        adjustedUpWeight = rewardSet.upTotalWeight + boostWeight * upRatio;
        adjustedNormalWeight = rewardSet.normalTotalWeight + boostWeight * normalRatio;
        // 普通奖励权重等比压缩
        adjustedCommonWeight = rewardSet.totalWeight - adjustedUpWeight - adjustedNormalWeight;
        if (adjustedCommonWeight < 0)
          adjustedCommonWeight = 0;
      }

      // 大保底状态强制：若下一次保底必为大保底，则本次抽卡一旦触发保底类别，只能抽取大保底奖励
      if (isNextUp
          && adjustedUpWeight != 0.0/* 需要有大保底项 */) {
        // 小保底概率合并至大保底
        adjustedUpWeight += adjustedNormalWeight;
        adjustedNormalWeight = 0;
      }

      // 第一步：决定抽卡类别（大保底/小保底/普通）
      double roll = RANDOM.nextDouble() * rewardSet.totalWeight;
      Reward selected;
      int resultType; // 2=大保底, 1=小保底, 0=普通
      boolean resetTries; // 是否重置抽数计数器
      boolean resetCounts; // 是否重置大保底计数器
      boolean incrementCounts; // 是否增加大保底计数（仅小保底且非大保底状态）

      if (roll < adjustedUpWeight) {
        // 命中大保底类别
        selected = selectRewardByWeight(rewardSet.upRewards);
        resultType = 2;
        resetTries = true;
        resetCounts = true; // 抽到大保底奖励，重置大保底计数
        incrementCounts = false;
      } else if (roll < adjustedUpWeight + adjustedNormalWeight) {
        // 命中保底类别，但此时只有小保底（若大保底状态，此处不会执行）
        selected = selectRewardByWeight(rewardSet.normalRewards);
        resultType = 1;
        resetTries = true;
        // 小保底：如果本次是大保底状态，理论上不应进入此分支，但防御性处理
        if (isNextUp) {
          // 异常情况：配置错误或概率溢出，强制重置大保底并警告
          logger.severe("玩家 %s 处于大保底状态却抽到了小保底奖励，强制重置保底计数", guarantee.player.getName());
          resetCounts = true;
          incrementCounts = false;
        } else {
          resetCounts = false;
          incrementCounts = true; // 普通小保底，增加大保底计数
        }
      } else {
        // 普通奖励
        selected = selectRewardByWeight(rewardSet.commonRewards);
        resultType = 0;
        resetTries = false;
        resetCounts = false;
        incrementCounts = false;
      }

      return new RollResult(selected, resultType, resetTries, resetCounts, incrementCounts);
    }

    /**
     * 强制触发保底（硬保底或概率提升至100%时的后备）
     *
     * @param rewardSet  奖励集
     * @param forceUp    是否强制为大保底
     * @param isHardPity 是否为硬保底（日志警告用）
     * @return 保底抽取结果
     */
    private static RollResult forcePity(RewardSet rewardSet, boolean forceUp, boolean isHardPity) {
      if (isHardPity) {
        logger.debug("强制保底触发");
      }

      if (forceUp) {
        if (rewardSet.upRewards.isEmpty()) {
          throw new IllegalStateException(Joyous.i18n.tr("entroprix.pool.missing_up"));
        }
        Reward selected = selectRewardByWeight(rewardSet.upRewards);
        return new RollResult(selected, 2, true, true, false);
      } else {
        if (rewardSet.normalRewards.isEmpty()) {
          throw new IllegalStateException(Joyous.i18n.tr("entroprix.pool.missing_normal"));
        }
        Reward selected = selectRewardByWeight(rewardSet.normalRewards);
        return new RollResult(selected, 1, true, false, true);
      }
    }

    /**
     * 按权重从列表中随机选择一个奖励
     */
    private static Reward selectRewardByWeight(List<Reward> rewards) {
      if (rewards.isEmpty()) {
        return new Reward(0, Collections.emptyList(), 0, "");
      }
      double total = rewards.stream().mapToInt(Reward::getRate).sum();
      double roll = RANDOM.nextDouble() * total;
      double current = 0;
      for (Reward r : rewards) {
        current += r.getRate();
        if (roll < current) {
          return r;
        }
      }
      return rewards.get(rewards.size() - 1);
    }
  }

  // ------------------------------------------------------------------------
  // 数据容器：奖励集、抽卡结果
  // ------------------------------------------------------------------------

  /**
   * 按保底类型分类的奖励集合，并预计算各类权重总和
   */
  private static class RewardSet {
    final List<Reward> upRewards = new ArrayList<>();
    final List<Reward> normalRewards = new ArrayList<>();
    final List<Reward> commonRewards = new ArrayList<>();

    double upTotalWeight;
    double normalTotalWeight;
    double commonTotalWeight;
    double specialTotalWeight; // up+normal
    double totalWeight;

    static RewardSet fromConfig(List<Map<String, Object>> configList) {
      RewardSet rs = new RewardSet();
      for (Map<String, Object> map : configList) {
        int rate = ((Number) map.getOrDefault("rate", 0)).intValue();
        @SuppressWarnings("unchecked")
        List<String> commands = (List<String>) map.getOrDefault("commands", Collections.emptyList());
        String name = (String) map.getOrDefault("name", "");
        int gType = ((Number) map.getOrDefault("guarantee", 0)).intValue();

        Reward reward = new Reward(rate, commands, gType, name);
        if (gType == 2) {
          rs.upRewards.add(reward);
          rs.upTotalWeight += rate;
        } else if (gType == 1) {
          rs.normalRewards.add(reward);
          rs.normalTotalWeight += rate;
        } else {
          rs.commonRewards.add(reward);
          rs.commonTotalWeight += rate;
        }
      }
      rs.specialTotalWeight = rs.upTotalWeight + rs.normalTotalWeight;
      rs.totalWeight = rs.specialTotalWeight + rs.commonTotalWeight;
      return rs;
    }
  }

  /**
   * 单次抽卡的完整决策结果
   */
  private static class RollResult {
    private final Reward selectedReward;
    private final int resultType; // 0普通 1小保底 2大保底
    private final boolean resetTries;
    private final boolean resetCounts;
    private final boolean incrementCounts;

    /**
     * 新建一个结果
     * 
     * @param selected 抽到的奖励
     * @param type     结果类型，0普通 1小保底 2大保底
     * @param resetT   要重置保底内抽数吗（出货了）
     * @param resetC   要重置保底计数吗（大保底或者小保底没歪）
     * @param incC     要增加保底计数吗（小保底歪了）
     */
    RollResult(Reward selected, int type, boolean resetT, boolean resetC, boolean incC) {
      this.selectedReward = selected;
      this.resultType = type;
      this.resetTries = resetT;
      this.resetCounts = resetC;
      this.incrementCounts = incC;
    }

    void applyTo(Guarantee g) {
      if (resetTries) {
        g.resetTries();
      } else {
        g.incrementTries();
      }
      if (resetCounts) {
        g.resetCounts();
      } else if (incrementCounts) {
        g.incrementCounts();
      }
    }

    Reward getSelectedReward() {
      return selectedReward;
    }

    int getResultType() {
      return resultType;
    }
  }

  // ------------------------------------------------------------------------
  // 奖励数据类
  // ------------------------------------------------------------------------

  private static class Reward {
    private final int rate;
    private final List<String> commands;
    private final String name;
    private final int guaranteeType;

    Reward(int rate, List<String> commands, int guaranteeType, String name) {
      this.rate = rate;
      this.commands = new ArrayList<>(commands);
      this.guaranteeType = guaranteeType;
      this.name = Objects.requireNonNullElse(name, "");
    }

    int getRate() {
      return rate;
    }

    List<String> getCommands() {
      return Collections.unmodifiableList(commands);
    }

    int getGuaranteeType() {
      return guaranteeType;
    }
  }

  // ------------------------------------------------------------------------
  // 保底管理器
  // ------------------------------------------------------------------------

  /**
   * 玩家保底状态管理器
   * <p>
   * 每个保底规则对应一个独立实例，通过 PDC 持久化两个计数器：
   * - tries : 距离保底已抽次数（0 ~ every-1）
   * - counts : 已连续触发小保底的次数（0 ~ max-1）
   */
  public static class Guarantee {
    private final Player player;
    private final String name;
    private final NamespacedKey triesKey;
    private final NamespacedKey countsKey;

    // --------------------------------------------------------------------
    // 构造与静态工厂
    // --------------------------------------------------------------------

    public Guarantee(Player player, String name) {
      this.player = player;
      this.name = name;
      this.triesKey = NAMES.getGuaranteeKey(name + ".tries");
      this.countsKey = NAMES.getGuaranteeKey(name + ".counts");
    }

    /** 获取指定玩家指定保底规则的实例（静态工具方法） */
    public static Guarantee of(Player player, String guaranteeName) {
      return new Guarantee(player, guaranteeName);
    }

    // --------------------------------------------------------------------
    // 查询方法
    // --------------------------------------------------------------------

    /**
     * 获取玩家的保底内抽数
     * 
     * @return
     */
    public int getTries() {
      return player.getPersistentDataContainer()
          .getOrDefault(triesKey, PersistentDataType.INTEGER, 0);
    }

    /**
     * 获取玩家的保底计数
     * @return
     */
    public int getCounts() {
      return player.getPersistentDataContainer()
          .getOrDefault(countsKey, PersistentDataType.INTEGER, 0);
    }

    /** 判断下一次保底是否为大保底 */
    public boolean isNextUpGuaranteed() {
      Map<String, Object> cfg = poolList.getSection("guarantee." + name);
      int max = ((Number) cfg.getOrDefault("max", 2)).intValue();
      return getCounts() >= max - 1;
    }

    // --------------------------------------------------------------------
    // 修改方法（包内可见，仅供 EntroprixMain 调用）
    // --------------------------------------------------------------------

    void incrementTries() {
      setTries(getTries() + 1);
    }

    void resetTries() {
      setTries(0);
    }

    void incrementCounts() {
      setCounts(getCounts() + 1);
    }

    void resetCounts() {
      setCounts(0);
    }

    // --------------------------------------------------------------------
    // 公开修改接口（原 GuaranteeManager 静态方法迁移至此）
    // --------------------------------------------------------------------

    /**
     * 设置保底内抽数
     * @param value
     */
    public void setTries(int value) {
      player.getPersistentDataContainer().set(triesKey, PersistentDataType.INTEGER, value);
    }

    /**
     * 设置保底计数
     * @param value
     */
    public void setCounts(int value) {
      player.getPersistentDataContainer().set(countsKey, PersistentDataType.INTEGER, value);
    }

    /**
     * 重置全部保底
     */
    public void reset() {
      setTries(0);
      setCounts(0);
    }

    // 静态便捷方法（完全替代原 GuaranteeManager）
    public static int getTries(Player player, String name) {
      return new Guarantee(player, name).getTries();
    }

    public static int getCounts(Player player, String name) {
      return new Guarantee(player, name).getCounts();
    }

    public static boolean isNextUpGuaranteed(Player player, String name) {
      return new Guarantee(player, name).isNextUpGuaranteed();
    }

    public static void addTries(Player player, String name, int delta) {
      Guarantee g = new Guarantee(player, name);
      g.setTries(g.getTries() + delta);
    }

    public static void setTries(Player player, String name, int value) {
      new Guarantee(player, name).setTries(value);
    }

    public static void setCounts(Player player, String name, int value) {
      new Guarantee(player, name).setCounts(value);
    }

    public static void reset(Player player, String name) {
      new Guarantee(player, name).reset();
    }
  }

  // ------------------------------------------------------------------------
  // 命令执行与日志
  // ------------------------------------------------------------------------

  /**
   * 执行奖励命令，返回命令摘要用于日志
   */
  private static void executeCommands(Player player, List<String> commands) {
    for (int i = 0; i < commands.size(); i++) {
      String cmd = commands.get(i)
          .replace("[player]", player.getName());
      // PlaceholderAPI 已在插件启用时检查，直接调用
      if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
        cmd = me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(player, cmd);
      }
      try {
        if (!Bukkit.dispatchCommand(Joyous.plugin.getServer().getConsoleSender(), cmd)) {
          logger.warn(Joyous.i18n.tr("system.command.failed"), cmd);
        }
      } catch (Exception e) {
        logger.err(Joyous.i18n.tr("system.command.unexpected"), cmd, e.getLocalizedMessage());
      }
      String clean = cmd.replaceAll("\\s+", " ").trim();
    }
    return;
  }

  class AsyncLogger {
    // 单线程已保证顺序，无需 synchronized
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
      Thread t = new Thread(r, "gacha-log");
      t.setDaemon(true);
      return t;
    });

    // 缓存当天的 Writer，避免重复打开文件
    private static volatile BufferedWriter currentWriter;
    private static volatile LocalDate currentDate;

    static {
      Runtime.getRuntime().addShutdownHook(new Thread(() -> {
        closeWriter();
        EXECUTOR.shutdown();
        try {
          if (!EXECUTOR.awaitTermination(5, TimeUnit.SECONDS)) {
            EXECUTOR.shutdownNow();
          }
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt(); // ✅ 恢复中断标志
        }
      }));
    }

    public static void log(String player, String pool, int count, int tries,
        String reward, int type) {
      EXECUTOR.submit(() -> doLog(player, pool, count, tries, reward, type));
    }

    private static void doLog(String player, String pool, int count, int tries,
        String reward, int type) {
      try {
        BufferedWriter writer = getWriter(LocalDate.now());
        String time = StreackLib.formatTime(null, "HH:mm:ss");
        String typeStr = switch (type) {
          case 2 -> "[UP]";
          case 1 -> "[NORMAL]";
          default -> "[COMMON]";
        };

        String line = String.format("%s | %s | %s | %d/%d | %s %s",
            time, player, pool, count, tries, typeStr, reward);

        writer.write(line);
        writer.newLine();
        writer.flush(); // 确保落盘，或改用定时批量刷盘

      } catch (Exception e) {
        // 降级：控制台输出，避免递归调用日志
        logger.err("无法保存抽卡日志：" + e.getLocalizedMessage(), e);
      }
    }

    // 单线程内操作，无需 synchronized，volatile 保证可见性即可
    private static BufferedWriter getWriter(LocalDate date) throws IOException {
      if (!date.equals(currentDate) || currentWriter == null) {
        closeWriter(); // 关闭旧日期文件

        Path dir = LOG_DIR.resolve(date.format(DateTimeFormatter.ofPattern("yyyy-MM")));
        Files.createDirectories(dir);

        Path file = dir.resolve(date.format(DateTimeFormatter.ofPattern("dd")) + ".txt");
        currentWriter = Files.newBufferedWriter(file,
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND,
            StandardOpenOption.WRITE);
        currentDate = date;
      }
      return currentWriter;
    }

    private static void closeWriter() {
      try {
        if (currentWriter != null) {
          currentWriter.flush();
          currentWriter.close();
          currentWriter = null;
          currentDate = null;
        }
      } catch (IOException e) {
        logger.err("无法关闭抽卡日志保存线程: " + e.getMessage(), e);
      }
    }
  }
}