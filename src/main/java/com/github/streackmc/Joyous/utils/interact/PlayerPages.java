package com.github.streackmc.Joyous.utils.interact;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.BooleanSupplier;

import javax.annotation.Nullable;

import org.bukkit.entity.Player;

import com.github.streackmc.Joyous.utils.jlogger;

/**
 * 单个玩家的页面状态机
 * <p>
 * 不订阅任何 Bukkit 事件、不碰界面，只维护「谁在显示、谁在排队、下一步该做什么」。
 * 因此可以在纯 JVM 里用假的页面把整张转移矩阵跑一遍。
 *
 * <h2>数据结构</h2>
 * <pre>
 * visible  当前显示的那一页（最多一个）
 * queue    等待显示的条目，头 = 下一个；同时装着 PENDING（没显示过）与 SUSPENDED（显示过又被挤下去）
 * requests 本帧内提交、还没处理的打开请求（按提交时刻排序）
 * </pre>
 *
 * <h2>为什么「立即关闭」是同步的，而「显示」要延后一 tick</h2>
 * 关闭界面在任何时机都安全；打开界面若发生在自己正在处理的点击事件里，会与光标、后续事件打架。
 * 所以 {@link PagePriorityEnum#INSTANT}/{@link PagePriorityEnum#REPLACE} 对当前页的处理立即执行，
 * 新页面的 attach 统一排到下一 tick。
 *
 * @author Neonai
 * @since 0.7.0
 */
final class PlayerPages {

  /** 队列上限：超出后 {@code INSERT} 挤掉队尾，{@code NORMAL} 直接丢弃自己 */
  static final int MAX_QUEUED = 8;

  /** 排队过期：只作用于从未显示过的条目 */
  static final long PENDING_TTL_MS = 10L * 60L * 1000L;

  /** 单次 drain 的最大转移次数，防止两个页面互相打开造成活锁 */
  private static final int MAX_STEPS = 32;

  /** 把「下一 tick 执行」这件事抽出来，纯 JVM 自检里换成立即收集 */
  interface Ticker {
    void schedule(Runnable task);
  }

  private record Pending(PageEntry entry, PagePriorityEnum priority) {
  }

  @Nullable
  private final Player player;
  private final Ticker ticker;
  private final BooleanSupplier primaryThread;

  private final Deque<PageEntry> queue = new ArrayDeque<>();
  private final Deque<Pending> requests = new ArrayDeque<>();

  @Nullable
  private PageEntry visible;
  private long sequence;
  private boolean draining;
  private boolean scheduled;

  PlayerPages(@Nullable Player player, Ticker ticker, BooleanSupplier primaryThread) {
    this.player = player;
    this.ticker = ticker;
    this.primaryThread = primaryThread;
  }

  // ──────────────────────────────────────────────
  // 对外操作
  // ──────────────────────────────────────────────

  /**
   * 提交一个页面
   * <p>
   * 相同玩家下 {@code (key, purpose)} 已在显示或排队时视为重复，直接返回既有句柄。
   */
  IPageHandle open(IJoyousPage page, PagePriorityEnum priority) {
    PageEntry existing = find(page.key(), page.purpose());
    if (existing != null) {
      jlogger.debug("页面已在栈中，忽略重复请求：玩家 %s 的 %s（优先度 %s）",
          playerName(), existing.describe(), priority);
      return existing;
    }

    PageEntry entry = new PageEntry(this, player, page, System.currentTimeMillis(), ++sequence);
    requests.addLast(new Pending(entry, priority));

    // 「当前界面立即关闭」：只在主线程、且不在转移过程中做，避免与钩子互相踩
    if (priority.immediate() && !draining && visible != null && primaryThread.getAsBoolean()) {
      retireVisible(priority, entry);
    }
    requestDrain();
    return entry;
  }

  /**
   * 结束指定条目
   *
   * @return 是否真的结束了它
   */
  boolean close(PageEntry entry) {
    if (entry.state().terminal()) {
      return false;
    }
    if (entry == visible) {
      detach(entry, DetachReasonEnum.CLOSED);
      if (!draining) {
        requestDrain();
      }
      return true;
    }
    if (queue.remove(entry)) {
      detach(entry, DetachReasonEnum.CLOSED);
      return true;
    }
    requests.removeIf(p -> p.entry() == entry);
    detach(entry, DetachReasonEnum.CLOSED);
    return true;
  }

  /**
   * 结束某 key 的全部条目
   *
   * @param purpose 指定目的可精确匹配；null 表示该 key 的所有目的
   * @return 实际结束的条目数量
   */
  int closeAll(String key, @Nullable String purpose) {
    List<PageEntry> targets = new ArrayList<>();
    if (matches(visible, key, purpose)) {
      targets.add(visible);
    }
    for (PageEntry e : queue) {
      if (matches(e, key, purpose)) {
        targets.add(e);
      }
    }
    for (Pending p : requests) {
      if (matches(p.entry(), key, purpose)) {
        targets.add(p.entry());
      }
    }
    int closed = 0;
    for (PageEntry e : targets) {
      if (close(e)) {
        closed++;
      }
    }
    return closed;
  }

  private static boolean matches(@Nullable PageEntry entry, String key, @Nullable String purpose) {
    if (entry == null || !entry.key().equals(key)) {
      return false;
    }
    return purpose == null || entry.purpose().equals(purpose);
  }

  /** 把排队中的条目提到队头 */
  void toFront(PageEntry entry) {
    if (entry.state().terminal() || entry == visible) {
      return;
    }
    if (queue.remove(entry)) {
      queue.addFirst(entry);
    }
  }

  /** 清空一切（退出 / 停服） */
  void clear(DetachReasonEnum why) {
    List<PageEntry> all = new ArrayList<>();
    if (visible != null) {
      all.add(visible);
    }
    all.addAll(queue);
    for (Pending p : requests) {
      all.add(p.entry());
    }
    visible = null;
    queue.clear();
    requests.clear();
    for (PageEntry e : all) {
      detach(e, why);
    }
  }

  /** 定期清扫：丢掉排队过久的条目 */
  void sweep() {
    if (queue.isEmpty()) {
      return;
    }
    List<PageEntry> expired = new ArrayList<>();
    for (PageEntry e : queue) {
      if (e.expired()) {
        expired.add(e);
      }
    }
    for (PageEntry e : expired) {
      jlogger.debug("页面排队超时：玩家 %s 的 %s", playerName(), e.describe());
      detach(e, DetachReasonEnum.TIMEOUT);
    }
  }

  /** 当前显示的条目（没有则 null） */
  @Nullable
  PageEntry visibleEntry() {
    return visible;
  }

  /** 诊断用的一行描述 */
  String describe() {
    StringBuilder sb = new StringBuilder();
    sb.append("visible=").append(visible == null ? "-" : visible.describe());
    sb.append(" queue=[");
    boolean first = true;
    for (PageEntry e : queue) {
      if (!first) {
        sb.append(", ");
      }
      first = false;
      sb.append(e.describe());
    }
    sb.append("] pending=").append(requests.size());
    return sb.toString();
  }

  // ──────────────────────────────────────────────
  // 转移
  // ──────────────────────────────────────────────

  /**
   * 处理待办请求，并在必要时把队列头推上前台
   * <p>
   * 请求按 {@code (submittedAt, sequence)} 顺序逐条应用；每条都完整走完自己的转移，
   * 所以同帧的多条 REPLACE 会「由旧到新依次展示」——先显示的那个可能只活 0 帧，但钩子照常成对执行。
   */
  void drain() {
    if (draining) {
      return;
    }
    draining = true;
    boolean hitLimit = false;
    int steps = 0;
    try {
      while (true) {
        if (steps++ >= MAX_STEPS) {
          hitLimit = true;
          break;
        }
        Pending req = requests.pollFirst();
        if (req != null) {
          apply(req);
          continue;
        }
        if (visible == null && !queue.isEmpty()) {
          promote(queue.pollFirst());
          continue;
        }
        break;
      }
    } finally {
      draining = false;
    }

    if (hitLimit) {
      jlogger.warning("页面转移次数超过上限（%s），丢弃剩余请求：玩家 %s 的 %s",
          MAX_STEPS, playerName(), describe());
      while (!requests.isEmpty()) {
        Pending p = requests.pollFirst();
        detach(p.entry(), DetachReasonEnum.DISCARDED);
      }
    }
    if (!requests.isEmpty() || (visible == null && !queue.isEmpty())) {
      requestDrain();
    }
  }

  /** 应用一条打开请求 */
  private void apply(Pending req) {
    PageEntry entry = req.entry();
    if (entry.state().terminal()) {
      return;
    }
    switch (req.priority()) {
      case INSTANT -> {
        retireVisible(PagePriorityEnum.INSTANT, entry);
        promote(entry);
      }
      case REPLACE -> {
        retireVisible(PagePriorityEnum.REPLACE, entry);
        promote(entry);
      }
      case INSERT -> enqueueHead(entry);
      case NORMAL -> enqueueTail(entry);
    }
  }

  /**
   * 让当前可见页让位
   * <p>
   * {@code INSTANT} + 可保留 → 挂起（压回队头，等新页结束再恢复）；
   * 其余情况 → 正常终止。不可保留时的降级只写日志，不打扰玩家。
   */
  private void retireVisible(PagePriorityEnum priority, @Nullable PageEntry incoming) {
    PageEntry current = visible;
    if (current == null) {
      return;
    }
    if (priority == PagePriorityEnum.INSTANT && current.page().keepable()) {
      visible = null;
      current.state(PageStateEnum.SUSPENDED);
      queue.addFirst(current);
      invokeSuspend(current);
      return;
    }
    if (priority == PagePriorityEnum.INSTANT) {
      jlogger.warning("页面降级：玩家 %s(%s) 请求 INSTANT 显示 [%s]，但当前页 [%s] 不可保留，按 REPLACE 处理",
          playerName(), playerId(),
          incoming == null ? "-" : incoming.label(),
          current.describe());
    }
    detach(current, DetachReasonEnum.REPLACED);
  }

  /** 把条目推上前台（首次 attach 或从挂起恢复） */
  private void promote(PageEntry entry) {
    if (entry.state().terminal()) {
      return;
    }
    if (visible != null) {
      // 理论上不该发生；发生就退化成排队，保证不丢页面
      queue.addFirst(entry);
      return;
    }
    if (entry.expired()) {
      detach(entry, DetachReasonEnum.TIMEOUT);
      return;
    }
    if (!entry.stillValid()) {
      detach(entry, DetachReasonEnum.INVALID);
      return;
    }
    boolean resume = entry.state() == PageStateEnum.SUSPENDED;
    entry.state(PageStateEnum.VISIBLE);
    visible = entry;
    if (resume) {
      invokeResume(entry);
    } else {
      invokeAttach(entry);
    }
  }

  private void enqueueHead(PageEntry entry) {
    if (queue.size() >= MAX_QUEUED) {
      PageEntry dropped = queue.pollLast();
      if (dropped != null) {
        discard(dropped, "队列已满（" + MAX_QUEUED + "），为插队腾位而丢弃队尾");
      }
    }
    queue.addFirst(entry);
  }

  private void enqueueTail(PageEntry entry) {
    if (queue.size() >= MAX_QUEUED) {
      discard(entry, "队列已满（" + MAX_QUEUED + "），丢弃新请求");
      return;
    }
    queue.addLast(entry);
  }

  /** 终止一个条目：状态、队列位置、钩子一次做完 */
  private void detach(PageEntry entry, DetachReasonEnum why) {
    if (entry.state().terminal()) {
      return;
    }
    entry.state(PageStateEnum.DETACHED);
    if (visible == entry) {
      visible = null;
    }
    queue.remove(entry);
    requests.removeIf(p -> p.entry() == entry);
    try {
      entry.page().onDetach(entry, why);
    } catch (Exception e) {
      jlogger.warning("页面 onDetach 出错（%s）：%s", why, entry.describe(), e);
    }
  }

  private void discard(PageEntry entry, String reason) {
    jlogger.debug("丢弃页面（%s）：玩家 %s 的 %s", reason, playerName(), entry.describe());
    detach(entry, DetachReasonEnum.DISCARDED);
  }

  private void requestDrain() {
    if (draining || scheduled || ticker == null) {
      return;
    }
    scheduled = true;
    ticker.schedule(() -> {
      scheduled = false;
      drain();
    });
  }

  // ──────────────────────────────────────────────
  // 钩子调用（统一兜底，单个页面炸掉不影响仲裁）
  // ──────────────────────────────────────────────

  private void invokeAttach(PageEntry entry) {
    if (entry.state() != PageStateEnum.VISIBLE) {
      return;
    }
    try {
      entry.page().onAttach(entry);
    } catch (Exception e) {
      jlogger.warning("页面 onAttach 出错：%s", entry.describe(), e);
    }
  }

  private void invokeSuspend(PageEntry entry) {
    try {
      entry.page().onSuspend(entry);
    } catch (Exception e) {
      jlogger.warning("页面 onSuspend 出错：%s", entry.describe(), e);
    }
  }

  private void invokeResume(PageEntry entry) {
    if (entry.state() != PageStateEnum.VISIBLE) {
      return;
    }
    try {
      entry.page().onResume(entry);
    } catch (Exception e) {
      jlogger.warning("页面 onResume 出错：%s", entry.describe(), e);
    }
  }

  // ──────────────────────────────────────────────
  // 查找
  // ──────────────────────────────────────────────

  /** 按 (key, purpose) 找既有条目 */
  @Nullable
  private PageEntry find(String key, String purpose) {
    String needle = purpose == null ? "" : purpose;
    PageEntry cur = visible;
    if (cur != null && cur.key().equals(key) && cur.purpose().equals(needle)) {
      return cur;
    }
    for (PageEntry e : queue) {
      if (e.key().equals(key) && e.purpose().equals(needle)) {
        return e;
      }
    }
    for (Pending p : requests) {
      if (p.entry().key().equals(key) && p.entry().purpose().equals(needle)) {
        return p.entry();
      }
    }
    return null;
  }

  private String playerName() {
    return player == null ? "?" : player.getName();
  }

  private String playerId() {
    return player == null ? "?" : player.getUniqueId().toString();
  }
}
