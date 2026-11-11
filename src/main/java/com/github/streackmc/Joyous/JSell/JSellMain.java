package com.github.streackmc.Joyous.JSell;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import com.github.streackmc.Joyous.Joyous;
import com.github.streackmc.Joyous.utils.i18n;
import com.github.streackmc.Joyous.utils.jlogger;
import com.github.streackmc.Joyous.utils.model_abstract.JoyousModel;

/**
 * JSell 模块主类
 * <p>
 * 「出售」：玩家把物品放进一个 6×9 的界面里，按可售列表逐件计价，确认后一次性结算。
 * <ul>
 *   <li>玩家命令 {@code /jsell}（别名 {@code /sell}）：{@code gui} / {@code hand} / {@code inv} / {@code all}</li>
 *   <li>管理员命令 {@code /jsellset}（别名 {@code /sellset}）：编辑可售列表、核查未完成事务</li>
 * </ul>
 *
 * <h3>数据放在哪</h3>
 * <ul>
 *   <li><b>可售列表</b>（配置性质、含命令原文）→ {@code models/JSell/items.yml}，见 {@link SellEntryStore}</li>
 *   <li><b>运行时状态</b>（托管物品、事务日志、销量）→ 数据库，见 {@link SellStore}</li>
 * </ul>
 *
 * @author kdxiaoyi
 * @since 0.8.0
 */
public class JSellMain extends JoyousModel {

  public static final String MODEL_NAME = "JSell";

  /** 命令根名 */
  public static final String COMMAND = "jsell";
  /** 玩家命令别名 */
  public static final List<String> ALIASES = List.of("sell");
  /** 管理员命令根名 */
  public static final String ADMIN_COMMAND = "jsellset";
  /** 管理员命令别名 */
  public static final List<String> ADMIN_ALIASES = List.of("sellset");

  private static volatile JSellMain instance;

  private SellEntryStore entryStore;
  private SellStore store;
  private SellSessionManager sessions;
  private SellService service;
  private JSellGui gui;
  private JSellEditorGui editor;
  private JSellChatInput chatInput;
  private JSellListener listener;
  private JSellCommand command;
  private JSellPHAPI phapi;
  private int sweepTaskId = -1;
  /** 存档错误提示的节流（毫秒时间戳） */
  private volatile long lastStorageNotice;

  @Override
  public String MODEL_NAME() {
    return MODEL_NAME;
  }

  @Override
  public void onEnable() throws Exception {
    instance = this;

    entryStore = new SellEntryStore(Joyous.dataPath);
    store = new SellStore(Joyous.database, CONF.escrowTable(), CONF.txTable(), CONF.usageTable());
    try {
      store.ensureTables();
    } catch (SellStore.SellStoreException e) {
      // 建表失败不该让整个模块停摆：界面与计价照常可用，只是不跨重启
      reportStorageError("准备数据表", e);
    }

    sessions = new SellSessionManager(this, store);
    sessions.recoverPending();

    service = new SellService(this, entryStore, store, sessions);
    chatInput = new JSellChatInput();
    gui = new JSellGui(this, entryStore, sessions, service);
    editor = new JSellEditorGui(this, entryStore, chatInput);
    chatInput.bind(editor);
    listener = new JSellListener(this, sessions, gui, editor, chatInput);
    command = new JSellCommand(this, entryStore, sessions, gui, editor, store);

    Joyous.pluginManager.registerEvents(listener, Joyous.plugin);
    command.register();

    if (Joyous.PlaceholderService != null) {
      phapi = new JSellPHAPI(entryStore);
      Joyous.PlaceholderService.registerParser(phapi);
    }

    long periodTicks = 20L * Math.max(60, CONF.sweepSeconds());
    sweepTaskId = Bukkit.getScheduler()
        .runTaskTimer(Joyous.plugin, this::sweep, periodTicks, periodTicks).getTaskId();

    jlogger.info("JSell 模块已启用（可售物品 %s 条，存档表 %s）",
        entryStore.size(), store.available() ? store.escrowTable() : "未配置数据库");
  }

  @Override
  public void onDisable() throws Exception {
    if (sweepTaskId != -1) {
      Bukkit.getScheduler().cancelTask(sweepTaskId);
      sweepTaskId = -1;
    }
    if (gui != null) {
      gui.cancelPendingUpdates();
    }
    if (entryStore != null) {
      entryStore.flush();
    }
    if (Joyous.PlaceholderService != null && phapi != null) {
      Joyous.PlaceholderService.unregisiterParser(phapi);
    }
    // ⚠️ 关服时不把界面里的物品塞回玩家背包：
    // 玩家数据与插件禁用的先后顺序不受我们控制，塞回去反而可能被随后的保存覆盖掉。
    // 托管记录已经在数据库里（每次放入都同步写入），下次上线时由 SellSessionManager 原样交付。
    if (sessions != null) {
      sessions.clearMemory();
    }
    instance = null;
    jlogger.info("JSell 模块已禁用");
  }

  /** 周期维护：清理过期销量流水（只影响限额统计，不碰任何物品） */
  private void sweep() {
    int days = CONF.usageRetentionDays();
    if (days <= 0 || store == null || !store.available()) {
      return;
    }
    long before = System.currentTimeMillis() - days * 86_400_000L;
    try {
      int removed = store.pruneUsage(before);
      if (removed > 0) {
        jlogger.debug("JSell 清理了 %s 条过期销量流水", removed);
      }
    } catch (SellStore.SellStoreException e) {
      reportStorageError("清理销量流水", e);
    }
  }

  // ──────────────────────────────────────────────
  // 静态访问
  // ──────────────────────────────────────────────

  /** 模块实例；未启用时为 null */
  public static JSellMain instance() {
    return instance;
  }

  public SellEntryStore entries() {
    return entryStore;
  }

  public SellStore store() {
    return store;
  }

  public SellSessionManager sessions() {
    return sessions;
  }

  public SellService service() {
    return service;
  }

  public JSellGui gui() {
    return gui;
  }

  public JSellEditorGui editor() {
    return editor;
  }

  public JSellChatInput chatInput() {
    return chatInput;
  }

  // ──────────────────────────────────────────────
  // 存档错误反馈
  // ──────────────────────────────────────────────

  /**
   * 数据库行为出错时的统一反馈
   * <p>
   * 既不静默吞掉（否则就变成「东西没了也不知道为什么」），也不刷屏：
   * 日志每次都记，在线管理员按 {@code storage.notice-seconds} 节流提示。
   */
  public void reportStorageError(String action, Throwable error) {
    String detail = error == null ? "" : String.valueOf(error.getMessage());
    jlogger.warn("JSell 存档失败（%s）：%s", action, detail);
    if (error != null) {
      jlogger.debug("JSell 存档异常堆栈：%s", error);
    }

    long now = System.currentTimeMillis();
    long interval = Math.max(5, CONF.storageNoticeSeconds()) * 1000L;
    if (now - lastStorageNotice < interval) {
      return;
    }
    lastStorageNotice = now;

    String message = i18n.tr("jsell.storage.error", action, detail);
    for (Player player : Bukkit.getOnlinePlayers()) {
      if (player.hasPermission(JSellListener.Perm.ADMIN)) {
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

    private static final String ROOT = "JSell.";

    private static String key(String sub) {
      return ROOT + sub;
    }

    /** 货币单位名（"确认以 1200 金币 出售"里的那两个字） */
    public static String unitName() {
      String raw = Joyous.conf.getString(key("unit.name"), "金币");
      return raw == null || raw.isBlank() ? "金币" : raw.trim();
    }

    /** 给钱要执行的命令（支持 %player% / %amount% / %uuid%） */
    public static List<String> economyCommands() {
      List<String> raw = Joyous.conf.getListOfString(key("economy.commands"),
          List.of("eco give %player% %amount%"));
      List<String> result = new ArrayList<>();
      for (String line : raw) {
        if (line != null && !line.isBlank()) {
          result.add(line.trim());
        }
      }
      return result;
    }

    /** 金额保留几位小数 */
    public static int decimals() {
      return Math.max(0, Math.min(6, Joyous.conf.getInt(key("economy.decimals"), 2)));
    }

    /** 是否在界面物品上追加「售出价值」说明行（不影响被卖掉的实体物品） */
    public static boolean appendLore() {
      return Joyous.conf.getBoolean(key("gui.append-lore"), true);
    }

    /** 确认按钮的防抖更新间隔（刻） */
    public static long debounceTicks() {
      return Math.max(1L, Math.min(40L, Joyous.conf.getLong(key("gui.debounce-ticks"), 2L)));
    }

    /** 界面最多能放多少格物品（6 行界面里前 5 行 = 45 格） */
    public static int maxSlots() {
      return Math.max(9, Math.min(45, Joyous.conf.getInt(key("gui.max-slots"), 45)));
    }

    /** 是否允许把不可售物品放进界面（放进来的会在结算时原样退回） */
    public static boolean allowUnsellableInGui() {
      return Joyous.conf.getBoolean(key("gui.allow-unsellable"), true);
    }

    /** 单次出售里逐件命令的执行上限（防止一次卖出上千件把主线程卡死） */
    public static int perItemCommandLimit() {
      return Math.max(0, Joyous.conf.getInt(key("command.per-item-limit"), 512));
    }

    /** 聊天栏输入的等待超时（秒） */
    public static int chatInputTimeoutSeconds() {
      return Math.max(5, Joyous.conf.getInt(key("command.chat-input-timeout"), 60));
    }

    /** 清理过期销量流水的间隔（秒） */
    public static int sweepSeconds() {
      return Math.max(60, Joyous.conf.getInt(key("storage.sweep-seconds"), 600));
    }

    /** 销量流水保留天数（{@code <= 0} 表示永久保留） */
    public static int usageRetentionDays() {
      return Joyous.conf.getInt(key("storage.usage-retention-days"), 30);
    }

    /** 存放托管物品的数据表名 */
    public static String escrowTable() {
      return Joyous.conf.getString(key("storage.escrow-table"), "joyous_jsell_escrow");
    }

    /** 存放出售事务日志的数据表名 */
    public static String txTable() {
      return Joyous.conf.getString(key("storage.tx-table"), "joyous_jsell_tx");
    }

    /** 存放销量流水的数据表名 */
    public static String usageTable() {
      return Joyous.conf.getString(key("storage.usage-table"), "joyous_jsell_usage");
    }

    /** 存档错误提示的节流间隔（秒） */
    public static int storageNoticeSeconds() {
      return Math.max(5, Joyous.conf.getInt(key("storage.notice-seconds"), 60));
    }

    private CONF() {
    }
  }

  public JSellMain() {
  }
}
