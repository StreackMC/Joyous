package com.github.streackmc.Joyous.JwardChest;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import com.github.streackmc.Joyous.Joyous;
import com.github.streackmc.Joyous.jlogger;
import com.github.streackmc.Joyous._Model.JoyousModel;

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
  private WardSessionManager sessions;
  private WardSearchController search;
  private WardEditorGui editor;
  private WardChatInput chatInput;
  private WardListener listener;
  private JwardChestCommand command;
  private int cleanupTaskId = -1;

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
    search = new WardSearchController(this, sessions);
    editor = new WardEditorGui(this, store, sessions, search);
    chatInput = new WardChatInput(store, editor);
    listener = new WardListener(this, store, sessions, search, editor, chatInput);
    command = new JwardChestCommand(this, store, sessions, editor);

    Joyous.pluginManager.registerEvents(listener, Joyous.plugin);
    command.register();

    // 定期清理长期无人访问的会话，防止内存无限增长
    long periodTicks = 20L * Math.max(5, CONF.sessionSweepSeconds());
    cleanupTaskId = Bukkit.getScheduler()
        .runTaskTimer(Joyous.plugin, this::sweep, periodTicks, periodTicks).getTaskId();

    jlogger.info("JwardChest 模块已启用（设置文件：%s）", store.file().getName());
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

  public WardSessionManager sessions() {
    return sessions;
  }

  public WardSearchController search() {
    return search;
  }

  public WardEditorGui editor() {
    return editor;
  }

  public WardChatInput chatInput() {
    return chatInput;
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
     * 占位物品的资源包模型键（custom_model）
     * <p>
     * 资源包靠它识别占位物品；PDC 里的 {@code custom_data} 只负责标识「由 Joyous 产生」，
     * 两者构成双重校验（见 {@code WardGui.isPlaceholder}）。
     * <p>
     * 取值优先级：{@code placeholder.model.<状态>} → {@code placeholder.item-model}（旧的单键写法）
     * → 空（不设置模型）。
     *
     * @param state 状态名：unsearched / searching
     * @return 模型键；空串表示不设置
     */
    public static String placeholderModel(String state) {
      String perState = Joyous.conf.getString(key("placeholder.model." + state), "");
      if (perState != null && !perState.isBlank()) {
        return perState.trim();
      }
      String single = Joyous.conf.getString(key("placeholder.item-model"), "");
      return single == null ? "" : single.trim();
    }

    public static int placeholderCustomModelData() {
      return Joyous.conf.getInt(key("placeholder.custom-model-data"), 0);
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
