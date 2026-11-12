package com.github.streackmc.Joyous.JwardChest;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import com.github.streackmc.Joyous.Joyous;
import com.github.streackmc.Joyous.utils.i18n;
import com.github.streackmc.Joyous.utils.jlogger;
import com.github.streackmc.Joyous.utils.model_abstract.JoyousModel;

/**
 * JwardChest 模块主类
 * <p>
 * 「战利品箱」：把任意方块绑定成一个带搜索动画的自定义容器。绑定关系写在区块 PDC，
 * 容器设置落在 {@code models/JwardChest/profiles.yml}，容器内容物与搜索进度只在内存里。
 *
 * @author kdxiaoyi
 * @since 0.7.0
 */
public class JwardChestMain extends JoyousModel {

  public static final String MODEL_NAME = "JwardChest";

  /** 命令根名 */
  public static final String COMMAND = "jwardchest";
  /** 命令别名 */
  public static final List<String> ALIASES = List.of("jc");

  private static volatile JwardChestMain instance;

  private WardProfileStore store;
  private WardStore contents;
  private WardSessionManager sessions;
  private WardSearchController search;
  private WardEditorGui editor;
  private WardListener listener;
  private JwardChestCommand command;
  private int cleanupTaskId = -1;
  /** 存档错误提示的节流（毫秒时间戳） */
  private volatile long lastStorageNotice;

  public JwardChestMain() {
  }

  @Override
  public String MODEL_NAME() {
    return MODEL_NAME;
  }

  @Override
  public void onEnable() throws Exception {
    instance = this;

    // 珍惜度是声明式的：先从配置读出来，后续构造容器设置时要用到档位列表
    WardRarity.reload();

    store = new WardProfileStore(Joyous.dataPath);
    sessions = new WardSessionManager();
    sessions.setEvictionListener(this::onSessionEvicted);
    search = new WardSearchController(this);
    editor = new WardEditorGui(this, store, sessions, search);
    listener = new WardListener(store, sessions, search, editor);
    command = new JwardChestCommand(this, store, sessions, editor);

    contents = new WardStore(Joyous.database, CONF.storageTable());
    try {
      contents.ensureTable();
    } catch (WardStore.WardStoreException e) {
      // 建表失败不该让整个模块停摆：容器照常能用，只是内容不跨重启
      reportStorageError("准备存档表", e);
    }

    Joyous.pluginManager.registerEvents(listener, Joyous.plugin);
    command.register();

    // 定期清理长期无人访问的会话，防止内存无限增长
    long periodTicks = 20L * Math.max(5, CONF.sessionSweepSeconds());
    cleanupTaskId = Bukkit.getScheduler()
        .runTaskTimer(Joyous.plugin, this::sweep, periodTicks, periodTicks).getTaskId();

    jlogger.info("JwardChest 模块已启用（设置文件：%s，存档表：%s）",
        store.file().getName(), contents.available() ? contents.table() : "未配置数据库");
  }

  @Override
  public void onDisable() throws Exception {
    if (cleanupTaskId != -1) {
      Bukkit.getScheduler().cancelTask(cleanupTaskId);
      cleanupTaskId = -1;
    }
    if (search != null) {
      search.stopAll();
    }
    if (sessions != null) {
      // 关服不等于「会话过期」：先摘掉回调，否则 clear() 会把全部存档删掉、重启后重新抽取
      sessions.setEvictionListener(null);
      sessions.clear();
    }
    if (store != null) {
      store.flush();
    }
    instance = null;
    jlogger.info("JwardChest 模块已禁用");
  }

  /** 周期清理：丢弃超时未访问的会话 */
  private void sweep() {
    int dropped = sessions.sweepIdle(CONF.sessionIdleTimeoutSeconds() * 1000L);
    if (dropped > 0) {
      jlogger.debug("JwardChest 清理了 %s 个超时会话", dropped);
    }
  }

  // ──────────────────────────────────────────────
  // 静态访问
  // ──────────────────────────────────────────────

  /** 模块实例；未启用时为 null */
  public static JwardChestMain instance() {
    return instance;
  }

  /** 插件实例（构造 NamespacedKey 用） */
  public static Plugin plugin() {
    return Joyous.plugin;
  }

  public WardProfileStore store() {
    return store;
  }

  /** 容器内容的存档层 */
  public WardStore contents() {
    return contents;
  }

  public WardSessionManager sessions() {
    return sessions;
  }

  public WardSearchController search() {
    return search;
  }

  public WardEditorGui editor() {
    return editor;
  }

  // ──────────────────────────────────────────────
  // 存档与失效
  // ──────────────────────────────────────────────

  /**
   * 会话被回收（过期 / 命令重置）时：把它的存档一起删掉
   * <p>
   * 这就是「容器刷新」的实现——内容没了，下次打开自然重新抽取。
   */
  private void onSessionEvicted(WardSession session) {
    if (session == null || session.preview() || contents == null || !contents.available()) {
      return;
    }
    try {
      contents.delete(session.profileId());
      jlogger.debug("JwardChest 已清除容器 %s 的存档（会话回收）", session.profileId());
    } catch (WardStore.WardStoreException e) {
      reportStorageError("清除容器存档", e);
    }
  }

  /**
   * 让某个容器的内容失效（改完待选物品、解除绑定、命令重置时调用）
   * <p>
   * 先按容器摘掉所有会话（会顺带删存档），再补一次删除以覆盖「当前没有任何会话」的情况。
   *
   * @param profileId 容器 UUID
   */
  public void invalidate(UUID profileId) {
    if (profileId == null) {
      return;
    }
    sessions.removeByProfile(profileId);
    if (contents != null && contents.available()) {
      try {
        contents.delete(profileId);
      } catch (WardStore.WardStoreException e) {
        reportStorageError("清除容器存档", e);
      }
    }
  }

  /** 让所有容器的内容失效（{@code /jc reset all}） */
  public void invalidateAll() {
    sessions.clear();
    if (contents != null && contents.available()) {
      try {
        contents.deleteAll();
      } catch (WardStore.WardStoreException e) {
        reportStorageError("清空容器存档", e);
      }
    }
  }

  /**
   * 数据库行为出错时的统一反馈
   * <p>
   * 既不静默吞掉（否则就变成「箱子行为诡异」），也不刷屏：日志每次都记，
   * 在线管理员则按 {@code storage-notice-seconds} 节流提示。
   *
   * @param action 正在做的事（写进日志与提示）
   * @param error  异常
   */
  public void reportStorageError(String action, Throwable error) {
    String detail = error == null ? "" : String.valueOf(error.getMessage());
    jlogger.warn("JwardChest 存档失败（%s）：%s", action, detail);
    if (error != null) {
      jlogger.debug("JwardChest 存档异常堆栈：%s", error);
    }

    long now = System.currentTimeMillis();
    long interval = Math.max(5, CONF.storageNoticeSeconds()) * 1000L;
    if (now - lastStorageNotice < interval) {
      return;
    }
    lastStorageNotice = now;

    String message = i18n.tr("jwardchest.storage.error", action, detail);
    for (Player player : Bukkit.getOnlinePlayers()) {
      if (player.hasPermission(WardListener.Perm.EDIT)) {
        player.sendMessage(message);
      }
    }
    Bukkit.getConsoleSender().sendMessage(message);
  }

  // ──────────────────────────────────────────────
  // 配置
  // ──────────────────────────────────────────────

  /** 配置访问器，动态读取以支持热重载 */
  public static final class CONF {

    private static final String ROOT = "JwardChest.";

    private static String key(String sub) {
      return ROOT + sub;
    }

    // ── 搜索 ──
    // 注意：搜索基准时长、完成音效、音量、音高都是「每珍惜度」的，
    // 已经随珍惜度一起声明在 JwardChest.rarities 里了（见 WardRarity），
    // 这里只剩与档位无关的全局系数。

    /** 物品数量对搜索时长的系数（秒 / 个），影响很小 */
    public static double searchAmountFactor() {
      return Math.max(0.0, Joyous.conf.getDouble(key("search.amount-factor"), 0.05));
    }

    /**
     * 「紧急着色窗口」的时长（秒）
     * <p>
     * 不再是一个写死的绝对秒数，而是<b>按这次搜索自己有多长</b>算出来再夹取：
     * <pre>窗口 = clamp(本次时长 × urgency-ratio, urgency-min-seconds, urgency-seconds)</pre>
     * 这样长搜索（十几秒）与短搜索（一点几秒）都能拿到「最后一段」的比例感，
     * 而上下两个夹取避免短搜索的窗口短到看不见、长搜索的窗口长到整段都在闪颜色。
     *
     * @param durationSeconds 本次搜索的总时长（秒）
     * @return 窗口秒数；配置全为 0 时返回 0（即不切换颜色）
     */
    public static double urgentWindowSeconds(double durationSeconds) {
      double ratio = Math.max(0.0, Joyous.conf.getDouble(key("search.urgency-ratio"), 0.25));
      double max = Math.max(0.0, Joyous.conf.getDouble(key("search.urgency-seconds"), 2.0));
      double min = Math.min(max,
          Math.max(0.0, Joyous.conf.getDouble(key("search.urgency-min-seconds"), 0.5)));
      return Math.max(min, Math.min(max, Math.max(0.0, durationSeconds) * ratio));
    }

    /** 搜索动画刷新间隔（刻），2 刻 = 10fps */
    public static long updateIntervalTicks() {
      return Math.max(1L, Joyous.conf.getLong(key("search.update-interval-ticks"), 2L));
    }

    // ── 占位物品 ──

    public static String placeholderName() {
      return Joyous.conf.getString(key("placeholder.name"), "配置文件");
    }

    public static List<String> placeholderLore() {
      List<String> def = new ArrayList<>();
      def.add("");
      def.add("配置文件");
      return Joyous.conf.getListOfString(key("placeholder.lore"), def);
    }

    /**
     * 占位物品的物品模型（{@code item_model} 组件）——<b>所有占位物品共用这一个键</b>
     * <p>
     * 1.21.4+ 的模型烘焙系统里，{@code item_model} 指向一个模型定义；那个定义可以用
     * {@code minecraft:select} 读 {@code custom_model_data.strings} 来决定最终画哪张贴图。
     * 于是「一档珍惜度 × 两种状态」的贴图矩阵全部由资源包侧声明，插件只负责写这两个字段。
     * <p>
     * 留空表示不设置模型（客户端显示玻璃板本色，占位保护不受影响）。
     *
     * @return 模型键；空串表示不设置
     */
    public static String placeholderItemModel() {
      String raw = Joyous.conf.getString(key("placeholder.item-model"), "");
      return raw == null ? "" : raw.trim();
    }

    /**
     * 「未搜索」阶段写在 {@code custom_model_data.strings[0]} 里的品质值
     * <p>
     * 未搜索的占位是盖住结果的黑玻璃板，此时不该暴露结果品质，所以填一个约定值。
     *
     * @return 品质占位值，默认 {@code none}
     */
    public static String placeholderNoneRarity() {
      String raw = Joyous.conf.getString(key("placeholder.none-rarity"), "none");
      return raw == null || raw.isBlank() ? "none" : raw.trim();
    }

    // ── 粗筛 ──

    public static int prefilterRows() {
      return Math.max(1, Math.min(6, Joyous.conf.getInt(key("prefilter.rows"), 2)));
    }

    public static int prefilterCols() {
      return Math.max(1, Math.min(9, Joyous.conf.getInt(key("prefilter.cols"), 5)));
    }

    // ── 会话 ──

    /** 会话空闲多久后回收（秒） */
    public static int sessionIdleTimeoutSeconds() {
      return Math.max(30, Joyous.conf.getInt(key("session.idle-timeout-seconds"), 300));
    }

    /** 清理任务间隔（秒） */
    public static int sessionSweepSeconds() {
      return Math.max(5, Joyous.conf.getInt(key("session.sweep-seconds"), 60));
    }

    /** 同时存在的会话上限，超出时丢弃最久未访问的 */
    public static int maxSessions() {
      return Math.max(16, Joyous.conf.getInt(key("session.max-sessions"), 512));
    }

    /** 单个容器最多展示多少条结果（超出部分直接丢弃，防止界面溢出） */
    public static int maxResults() {
      return Math.max(1, Joyous.conf.getInt(key("session.max-results"), 54));
    }

    /** 聊天栏输入的等待超时（秒） */
    public static int chatInputTimeoutSeconds() {
      return Math.max(5, Joyous.conf.getInt(key("session.chat-input-timeout"), 60));
    }

    /** 是否允许玩家把物品放进容器（关闭后容器只出不进） */
    public static boolean allowPlace() {
      return Joyous.conf.getBoolean(key("session.allow-place"), true);
    }

    /**
     * 是否允许放入「不是本模块产出」的物品
     * <p>
     * 本模块抽出的物品带有 {@code joyous:jc_rarity} 标记；关掉这一项后，只有带标记的物品能放进容器，
     * 玩家往里塞自己的东西会被拒绝。
     */
    public static boolean allowForeignPlace() {
      return Joyous.conf.getBoolean(key("session.allow-foreign-place"), true);
    }

    // ── 存档 ──

    /** 存放容器内容的数据表名 */
    public static String storageTable() {
      String raw = Joyous.conf.getString(key("storage.table"), "joyous_jwardchest");
      return raw == null || raw.isBlank() ? "joyous_jwardchest" : raw.trim();
    }

    /** 存档错误提示的节流间隔（秒） */
    public static int storageNoticeSeconds() {
      return Math.max(5, Joyous.conf.getInt(key("storage.notice-seconds"), 60));
    }

    /** 命令里「坐标范围」模式的单边最大半径 */
    public static int resetRadiusLimit() {
      return Math.max(1, Joyous.conf.getInt(key("command.reset-radius-limit"), 256));
    }

    /** /jwardchest new 选取方块的最大距离 */
    public static int reach() {
      return Math.max(1, Math.min(64, Joyous.conf.getInt(key("command.reach"), 6)));
    }

    private CONF() {
    }
  }

  /** 便捷：把字符串转成可比较的小写形式 */
  static String lower(String s) {
    return s == null ? "" : s.toLowerCase(Locale.ROOT);
  }

  /** 便捷：数据目录下的子路径 */
  static File dataFile(String sub) {
    return new File(Joyous.dataPath, sub);
  }
}
