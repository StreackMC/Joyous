package com.github.streackmc.Joyous.utils.interact;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

import com.github.streackmc.Joyous.Joyous;
import com.github.streackmc.Joyous.utils.jlogger;

/**
 * 页面仲裁器：每玩家一份页面队列
 * <p>
 * 负责「给玩家看什么、剩下的怎么排队、被挤下去的怎么办」，不涉及界面绘制与事件路由。
 *
 * <h2>四种优先度</h2>
 * <table border="1">
 *   <caption>对当前页 / 队列 / 生命周期的影响</caption>
 *   <tr><th>优先度</th><th>当前页面</th><th>队列</th><th>钩子</th></tr>
 *   <tr><td>{@link PagePriority#INSTANT}</td><td>挂起并压回队头</td><td>当前页插到队头</td>
 *       <td>onSuspend</td></tr>
 *   <tr><td>{@link PagePriority#REPLACE}</td><td>终止</td><td>不变</td><td>onDetach(REPLACED)</td></tr>
 *   <tr><td>{@link PagePriority#NORMAL}</td><td>不变</td><td>追加队尾</td><td>无</td></tr>
 *   <tr><td>{@link PagePriority#INSERT}</td><td>不变</td><td>插到队头</td><td>无</td></tr>
 * </table>
 *
 * <h2>状态转移</h2>
 * <pre>
 * PENDING ──出队显示──▶ VISIBLE ──INSTANT(可保留)──▶ SUSPENDED
 *    │                    │                            │
 *    │                    │REPLACE/关闭/退出           │轮到它
 *    ▼                    ▼                            ▼
 * DETACHED ◀──────────────┴────────────────────── VISIBLE(经 onResume)
 * </pre>
 *
 * <h2>几条硬规则</h2>
 * <ul>
 *   <li><b>当前界面立即关闭</b>：{@code INSTANT}/{@code REPLACE} 对当前页的处置在调用时就执行；
 *       新页面的显示统一延后一 tick（避免在自己处理的点击事件里开界面）。</li>
 *   <li><b>同帧多条立即类请求按提交时刻由旧到新依次应用</b>，每条都完整走完自己的转移，
 *       先显示的可能只活 0 帧，但钩子照常成对执行。不做静默合并——那会让钩子配对不可预测。</li>
 *   <li><b>重入</b>：转移过程中再次 {@link #open} 只入待办，当前转移收尾后处理；
 *       单轮最多 32 次转移，超出则丢弃剩余并告警。</li>
 *   <li><b>去重</b>：同一玩家下 {@code (key, purpose)} 相同即视为重复，忽略新请求并返回既有句柄。</li>
 *   <li><b>不可保留</b>：{@code INSTANT} 遇到 {@link JoyousPage#keepable()} 为 false 的当前页时
 *       降级为 {@code REPLACE}，只写日志（含玩家与双方页面的 key/purpose），不打扰玩家。</li>
 * </ul>
 *
 * @author Neonai
 * @since 0.7.0
 */
public final class PageArbiter implements Listener {

  private static final PageArbiter INSTANCE = new PageArbiter();

  /** 清扫任务间隔（秒） */
  private static final long SWEEP_SECONDS = 60L;

  private final Map<UUID, PlayerPages> states = new ConcurrentHashMap<>();

  @Nullable
  private volatile Plugin plugin;
  private volatile boolean installed;

  private PageArbiter() {
  }

  /** 全局实例 */
  public static PageArbiter get() {
    return INSTANCE;
  }

  /**
   * 接入 Bukkit：注册退出监听与清扫任务
   * <p>
   * 建议在插件启用阶段调用一次；若忘记调用，首次 {@link #open} 时会尝试用
   * {@code Joyous.plugin} 自动接入，仍失败则抛出异常（早暴露胜过静默失效）。
   *
   * @param plugin 宿主插件
   */
  public synchronized void install(Plugin plugin) {
    if (installed) {
      return;
    }
    this.plugin = Objects.requireNonNull(plugin, "plugin");
    Bukkit.getPluginManager().registerEvents(this, plugin);
    long period = 20L * SWEEP_SECONDS;
    Bukkit.getScheduler().runTaskTimer(plugin, this::sweep, period, period);
    installed = true;
    jlogger.info("页面仲裁器已启用：队列上限 %s 条，排队过期 %s 分钟",
        PlayerPages.MAX_QUEUED, PlayerPages.PENDING_TTL_MS / 60000L);
  }

  /**
   * 请求显示一个页面
   *
   * @param player   目标玩家
   * @param page     页面
   * @param priority 优先度
   * @return 句柄；若 {@code (key, purpose)} 已在栈中，返回既有的那一个
   */
  public PageHandle open(Player player, JoyousPage page, PagePriority priority) {
    Objects.requireNonNull(player, "player");
    Objects.requireNonNull(page, "page");
    Objects.requireNonNull(priority, "priority");
    ensureInstalled();

    if (!Bukkit.isPrimaryThread()) {
      // 异步调用（例如聊天事件里）统一转主线程，句柄先占位再回填
      DeferredHandle deferred = new DeferredHandle(page);
      Bukkit.getScheduler().runTask(plugin, () -> {
        PageHandle real = stateOf(player).open(page, priority);
        deferred.bind(real);
      });
      return deferred;
    }
    return stateOf(player).open(page, priority);
  }

  /**
   * 结束某玩家指定 key 的页面（含排队中的）
   *
   * @param purpose 指定目的可精确定位；null 表示该 key 的全部目的
   * @return 结束的条目数量
   */
  public int close(Player player, String key, @Nullable String purpose) {
    ensureInstalled();
    PlayerPages state = states.get(player.getUniqueId());
    return state == null ? 0 : state.closeAll(key, purpose);
  }

  /** 结束该玩家所有页面 */
  public void clear(Player player, DetachReason why) {
    PlayerPages state = states.remove(player.getUniqueId());
    if (state != null) {
      state.clear(why);
    }
  }

  /** 诊断用：当前玩家的页面栈快照 */
  public String describe(Player player) {
    PlayerPages state = states.get(player.getUniqueId());
    return state == null ? "（无页面）" : state.describe();
  }

  /** 停服：清空所有玩家并停止清扫 */
  public synchronized void shutdown() {
    for (PlayerPages state : states.values()) {
      state.clear(DetachReason.SHUTDOWN);
    }
    states.clear();
    installed = false;
    plugin = null;
  }

  // ──────────────────────────────────────────────
  // 内部
  // ──────────────────────────────────────────────

  private PlayerPages stateOf(Player player) {
    return states.computeIfAbsent(player.getUniqueId(),
        id -> new PlayerPages(player, this::schedule, Bukkit::isPrimaryThread));
  }

  private void schedule(Runnable task) {
    Plugin host = plugin;
    if (host == null || !host.isEnabled()) {
      // 插件正在停用（或尚未接入）：就地执行，避免「已标记 scheduled 但任务永不运行」把队列卡死
      task.run();
      return;
    }
    Bukkit.getScheduler().runTask(host, task);
  }

  private void sweep() {
    for (Map.Entry<UUID, PlayerPages> e : states.entrySet()) {
      PlayerPages state = e.getValue();
      if (Bukkit.getPlayer(e.getKey()) == null) {
        // 退出事件没赶上（例如热重载）时的兜底
        state.clear(DetachReason.QUIT);
        states.remove(e.getKey(), state);
        continue;
      }
      state.sweep();
    }
  }

  private void ensureInstalled() {
    if (installed) {
      return;
    }
    if (Joyous.plugin != null) {
      install(Joyous.plugin);
      return;
    }
    throw new IllegalStateException(
        "PageArbiter 尚未安装：请在插件启用阶段调用 PageArbiter.get().install(plugin)");
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void onQuit(PlayerQuitEvent event) {
    PlayerPages state = states.remove(event.getPlayer().getUniqueId());
    if (state != null) {
      state.clear(DetachReason.QUIT);
    }
  }

  /**
   * 异步提交时的占位句柄
   * <p>
   * 在真正的条目建立之前，状态一律按 {@link PageState#PENDING} 报告；
   * 若在此之前就被 {@link #close()}，等条目建好后立刻收尾。
   */
  private static final class DeferredHandle implements PageHandle {

    private final String key;
    private final String purpose;
    private volatile PageHandle delegate;
    private volatile boolean cancelled;

    DeferredHandle(JoyousPage page) {
      this.key = page.key();
      String p = page.purpose();
      this.purpose = p == null ? "" : p;
    }

    void bind(PageHandle handle) {
      this.delegate = handle;
      if (cancelled) {
        handle.close();
      }
    }

    @Override
    public String key() {
      return key;
    }

    @Override
    public String purpose() {
      return purpose;
    }

    @Override
    public PageState state() {
      PageHandle handle = delegate;
      if (handle != null) {
        return handle.state();
      }
      return cancelled ? PageState.DETACHED : PageState.PENDING;
    }

    @Override
    public boolean close() {
      PageHandle handle = delegate;
      if (handle != null) {
        return handle.close();
      }
      cancelled = true;
      return true;
    }
  }
}
