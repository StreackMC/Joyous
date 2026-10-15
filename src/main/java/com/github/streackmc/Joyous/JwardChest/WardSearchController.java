package com.github.streackmc.Joyous.JwardChest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;

import com.github.streackmc.Joyous.Joyous;
import com.github.streackmc.Joyous.i18n;
import com.github.streackmc.Joyous.jlogger;

/**
 * 运行时容器的打开、粗筛与搜索动画
 * <p>
 * 每位玩家各自持有一份界面与一条搜索动画任务，因此进度互不影响；结果组存在
 * {@link WardSession} 里共享。玩家关闭容器时任务被取消（进行中的倒计时重置），
 * 但已搜索出的槽位记录被保留，重开时直接呈现、无需重搜。
 * <p>
 * 搜索顺序为「先左到右、再上到下」，即结果下标递增；每个槽位的搜索时长由
 * 结果珍惜度与物品数量共同决定（见 {@link WardRarity#searchSeconds(int)}）。
 *
 * @author kdxiaoyi
 * @since 0.7.0
 */
public final class WardSearchController {

  private final JwardChestMain module;
  private final WardSessionManager sessions;

  /** 进行中的搜索任务：会话键 + 玩家 → 状态 */
  private final Map<String, Search> running = new ConcurrentHashMap<>();

  public WardSearchController(JwardChestMain module, WardSessionManager sessions) {
    this.module = module;
    this.sessions = sessions;
  }

  // ──────────────────────────────────────────────
  // 打开
  // ──────────────────────────────────────────────

  /**
   * 为玩家打开一个绑定方块的容器
   *
   * @param player 玩家
   * @param profile 生效设置
   * @param session 会话
   */
  public void open(Player player, WardProfile profile, WardSession session) {
    open(player, profile, session, true);
  }

  /**
   * 为玩家打开一个绑定方块的容器
   *
   * @param player  玩家
   * @param profile 生效设置
   * @param session 会话
   * @param animate 是否走粗筛与搜索动画；{@code false} 时直接揭示全部结果（编辑界面的 Shift 预览）
   */
  public void open(Player player, WardProfile profile, WardSession session, boolean animate) {
    ensureGenerated(profile, session);

    if (session.empty()) {
      sendEmptyReason(player, profile);
      return;
    }

    WardGui.Holder holder = new WardGui.Holder(WardGui.Kind.RUNTIME, profile.id(), session);
    Inventory inventory = profile.container().create(holder, profile.rows(),
        WardGui.title(player, profile.title()));
    holder.attach(inventory);
    buildInitial(inventory, profile, session, player);

    // 先打开再登记，避免 InventoryCloseEvent 把旧记录清掉
    player.openInventory(inventory);

    stop(player); // 清理可能残留的旧任务

    if (!animate) {
      revealAll(session, inventory);
      return;
    }

    if (session.allRevealed(player.getUniqueId())) {
      jlogger.debug("玩家 %s 重开容器，结果已全部搜出，不再启动搜索", player.getName());
      return;
    }
    start(player, profile, session);
  }

  /** 跳过动画：直接把全部结果摆出来（预览用，不写进度，关闭即丢弃） */
  private void revealAll(WardSession session, Inventory inventory) {
    List<WardSession.Loot> loot = session.loot();
    for (int i = 0; i < loot.size(); i++) {
      int slot = session.slotOf(i);
      if (slot < 0 || slot >= inventory.getSize()) {
        continue;
      }
      inventory.setItem(slot, loot.get(i).item());
    }
  }

  /** 空结果时给出准确原因（没配置物品 / 权重全为 0 / 容器装不下） */
  private static void sendEmptyReason(Player player, WardProfile profile) {
    boolean anyEntry = !profile.entries().isEmpty();
    boolean anyActive = profile.entries().stream().anyMatch(WardItemEntry::active);
    if (anyEntry && !anyActive) {
      player.sendMessage(i18n.tr("jwardchest.container.empty_zero_weight"));
      return;
    }
    player.sendMessage(i18n.tr("jwardchest.container.empty"));
  }

  /** 保证结果组已生成（按设置抽取一次，并随机排布到容器槽位） */
  private void ensureGenerated(WardProfile profile, WardSession session) {
    if (session.generated()) {
      return;
    }
    Random random = new Random();
    List<WardSession.Loot> loot = WardRoller.roll(profile, random);
    int[] slotOf = placeRandomly(loot, profile.slots(), random);
    session.fill(loot, slotOf);
    jlogger.debug("JwardChest 为容器 %s 生成了 %s 条结果（容器 %s 格）",
        session.key(), loot.size(), profile.slots());
  }

  /**
   * 为结果组随机挑选互不相同的槽位，并把结果按槽位升序重排
   * <p>
   * 于是「下标递增」天然等于「先左到右、再上到下」的搜索顺序，而结果落在容器里的位置是随机的。
   * 容器装不下的多余结果会在这里被舍弃。
   *
   * @param loot  结果列表（会被按槽位升序就地重排，必要时截断）
   * @param slots 容器槽位数
   * @param random 随机源
   * @return 与重排后的结果等长的槽位映射
   */
  private static int[] placeRandomly(List<WardSession.Loot> loot, int slots, Random random) {
    if (loot.isEmpty() || slots <= 0) {
      loot.clear();
      return new int[0];
    }

    List<Integer> all = new ArrayList<>(slots);
    for (int i = 0; i < slots; i++) {
      all.add(i);
    }
    Collections.shuffle(all, random);
    int count = Math.min(loot.size(), slots);
    List<Integer> chosen = new ArrayList<>(all.subList(0, count));
    Collections.sort(chosen);

    List<WardSession.Loot> pool = new ArrayList<>(loot);
    List<WardSession.Loot> sorted = new ArrayList<>(count);
    int[] slotOf = new int[count];
    for (int i = 0; i < count; i++) {
      slotOf[i] = chosen.get(i);
      sorted.add(pool.remove(random.nextInt(pool.size())));
    }
    loot.clear();
    loot.addAll(sorted);
    return slotOf;
  }

  /** 按「共享结果组 + 本玩家进度」重建界面（结果散落在各自随机槽位上） */
  private void buildInitial(Inventory inventory, WardProfile profile, WardSession session,
      Player player) {
    inventory.clear();
    List<WardSession.Loot> loot = session.loot();
    java.util.Set<Integer> revealed = session.revealed(player.getUniqueId());
    for (int i = 0; i < loot.size(); i++) {
      int slot = session.slotOf(i);
      if (slot < 0 || slot >= inventory.getSize()) {
        continue;
      }
      inventory.setItem(slot, revealed.contains(i) ? loot.get(i).item() : WardGui.unsearchedPane());
    }
  }

  // ──────────────────────────────────────────────
  // 任务生命周期
  // ──────────────────────────────────────────────

  private void start(Player player, WardProfile profile, WardSession session) {
    Search s = new Search(session.key(), player.getUniqueId());
    s.profile = profile;
    s.current = -1;
    s.prefilterDone = !profile.prefilterEnabled() || session.prefilted();
    running.put(s.taskKey(), s);

    long interval = JwardChestMain.CONF.updateIntervalTicks();
    s.taskId = Bukkit.getScheduler()
        .runTaskTimer(Joyous.plugin, () -> tick(s), interval, interval)
        .getTaskId();
  }

  /** 取消某玩家当前的任务（关闭容器时调用） */
  public void stop(Player player) {
    for (Search s : new ArrayList<>(running.values())) {
      if (s.playerId.equals(player.getUniqueId())) {
        cancel(s);
      }
    }
  }

  /** 取消全部任务（模块禁用时调用） */
  public void stopAll() {
    for (Search s : new ArrayList<>(running.values())) {
      cancelInternal(s);
    }
    running.clear();
  }

  private void cancel(Search s) {
    cancelInternal(s);
    running.remove(s.taskKey());
  }

  private void cancelInternal(Search s) {
    if (s.taskId != -1) {
      Bukkit.getScheduler().cancelTask(s.taskId);
      s.taskId = -1;
    }
  }

  // ──────────────────────────────────────────────
  // 每帧
  // ──────────────────────────────────────────────

  private void tick(Search s) {
    Player player = Bukkit.getPlayer(s.playerId);
    if (player == null || !player.isOnline()) {
      cancel(s);
      return;
    }

    Inventory open = player.getOpenInventory().getTopInventory();
    WardGui.Holder holder = WardGui.holderOf(open);
    if (holder == null || !(holder.payload() instanceof WardSession session)
        || !session.key().equals(s.sessionKey)) {
      // 玩家已经切走界面：取消任务（倒计时随之重置），进度保留
      cancel(s);
      return;
    }

    long now = System.currentTimeMillis();

    if (!s.prefilterDone) {
      tickPrefilter(s, session, open, now);
      return;
    }

    tickSearch(s, session, open, player, now);
  }

  /** 粗筛：整箱黑玻璃板，中间 行×列 区域用搜索中占位 */
  private void tickPrefilter(Search s, WardSession session, Inventory open, long now) {
    if (!s.prefilterStarted) {
      s.prefilterStarted = true;
      s.prefilterEndsAt = now + s.profile.prefilterSeconds() * 1000L;
      for (int i = 0; i < open.getSize(); i++) {
        open.setItem(i, WardGui.unsearchedPane());
      }
      s.forgetFrameCache();
    }

    long remain = s.prefilterEndsAt - now;
    WardRarity highest = highestRarity(session);
    // 帧内文本按整秒量化，所以每秒只变一次；帧没变就不发包
    push(s, open, prefilterSlots(open.getSize()),
        WardGui.prefilterFrame(highest, Math.max(0, remain), s.profile.prefilterSeconds()));

    if (remain > 0) {
      return;
    }

    // 粗筛结束：进入正常搜索流程，界面重置为「结果槽黑玻璃板」
    session.prefilted(true);
    s.prefilterDone = true;
    s.forgetFrameCache();
    Player player = Bukkit.getPlayer(s.playerId);
    if (player != null) {
      buildInitial(open, s.profile, session, player);
    }
  }

  /** 正常搜索：逐槽位倒计时，完成后换成结果并播放音效 */
  private void tickSearch(Search s, WardSession session, Inventory open, Player player, long now) {
    List<WardSession.Loot> loot = session.loot();

    if (s.current < 0) {
      s.current = nextUnrevealed(session, player, loot);
      if (s.current < 0) {
        cancel(s);
        return;
      }
      int slot = session.slotOf(s.current);
      if (slot < 0 || slot >= open.getSize()) {
        // 槽位映射缺失（例如容器被改成更小的类型）：跳过这一条，别把整条流程卡死
        s.current = -1;
        return;
      }
      WardSession.Loot target = loot.get(s.current);
      s.slotSeconds = target.rarity().searchSeconds(target.item().getAmount());
      s.slotEndsAt = now + (long) (s.slotSeconds * 1000.0);
      push(s, open, slot, WardGui.slotFrame(target.rarity(), s.slotEndsAt - now, s.slotSeconds));
      return;
    }

    if (s.current >= loot.size()) {
      s.current = -1;
      return;
    }

    int slot = session.slotOf(s.current);
    if (slot < 0 || slot >= open.getSize()) {
      s.current = -1;
      return;
    }

    WardSession.Loot target = loot.get(s.current);
    long remain = s.slotEndsAt - now;
    if (remain > 0) {
      // 逐结果阶段帧内文本恒定，只有「进入紧急窗口」那一刻会变（材质与名字一起变），
      // 所以整格搜索过程中通常只有两次更新
      push(s, open, slot, WardGui.slotFrame(target.rarity(), remain, s.slotSeconds));
      return;
    }

    // 完成：替换为结果、记录进度、按珍惜度播放音效
    open.setItem(slot, target.item());
    session.markRevealed(player.getUniqueId(), s.current);
    target.rarity().playCompletion(player);
    s.current = -1;

    if (session.allRevealed(player.getUniqueId())) {
      player.sendMessage(i18n.tr("jwardchest.container.searched_all"));
      cancel(s);
    }
  }

  /** 下一个尚未搜出的结果下标（结果已按槽位升序排列，故下标递增 = 先左到右、再上到下） */
  private int nextUnrevealed(WardSession session, Player player, List<WardSession.Loot> loot) {
    java.util.Set<Integer> revealed = session.revealed(player.getUniqueId());
    for (int i = 0; i < loot.size(); i++) {
      if (!revealed.contains(i)) {
        return i;
      }
    }
    return -1;
  }

  /**
   * 按需刷新「搜索中」占位：只有这一帧与上次真正发出去的不同时才 setItem
   * <p>
   * 这是压低发包量的关键。帧把「材质 + 文本」打包在一起，所以比较一帧就等于比较整格
   * 画出来的样子：粗筛阶段每秒变一次（文本按整秒量化），逐结果阶段通常每格只变两次
   * （开局、进入紧急窗口）。
   */
  private static void push(Search s, Inventory open, int slot, WardGui.Frame frame) {
    if (slot < 0 || slot >= open.getSize()) {
      return;
    }
    if (s.frameCache == null || s.frameCache.length != open.getSize()) {
      s.frameCache = new WardGui.Frame[open.getSize()];
    }
    if (frame.equals(s.frameCache[slot])) {
      return;
    }
    s.frameCache[slot] = frame;
    open.setItem(slot, WardGui.searchingPane(frame));
  }

  /** 一次刷新多个槽位（粗筛的 行×列 区域，共用同一帧） */
  private static void push(Search s, Inventory open, List<Integer> slots, WardGui.Frame frame) {
    for (int slot : slots) {
      push(s, open, slot, frame);
    }
  }

  /**
   * 粗筛区域内「搜索中」占位的槽位下标
   * <p>
   * 标准容器（槽位数是 9 的整数倍，即箱子/木桶/潜影盒那一类）把 {@code 行×列} 的区域
   * <b>正中心</b>摆放：水平居中；垂直方向用整除向下取整，所以行数为偶数时偏上。
   * 非标准容器（漏斗、熔炉、酿造台等）不做居中，统一从 0 号槽位起顺序铺开。
   *
   * @param inventorySize 容器槽位数
   * @return 槽位下标列表
   */
  private static List<Integer> prefilterSlots(int inventorySize) {
    int rows = JwardChestMain.CONF.prefilterRows();
    int cols = JwardChestMain.CONF.prefilterCols();
    List<Integer> slots = new ArrayList<>();

    boolean standard = inventorySize >= 9 && inventorySize % 9 == 0;
    if (!standard) {
      int limit = Math.min(inventorySize, rows * cols);
      for (int i = 0; i < limit; i++) {
        slots.add(i);
      }
      return slots;
    }

    int totalRows = inventorySize / 9;
    int startCol = Math.max(0, (9 - Math.min(cols, 9)) / 2);
    // 整除向下取整 → (总行数 - 占位行数) 为偶数时偏上
    int startRow = Math.max(0, (totalRows - rows) / 2);

    for (int r = 0; r < rows; r++) {
      for (int c = 0; c < cols; c++) {
        int slot = (startRow + r) * 9 + startCol + c;
        if (slot >= 0 && slot < inventorySize) {
          slots.add(slot);
        }
      }
    }
    return slots;
  }

  /** 结果组里最高的珍惜度 */
  private static WardRarity highestRarity(WardSession session) {
    return session.loot().stream()
        .map(WardSession.Loot::rarity)
        .max(Comparator.naturalOrder())
        .orElse(WardRarity.first());
  }

  // ──────────────────────────────────────────────
  // 状态
  // ──────────────────────────────────────────────

  private static final class Search {
    final String sessionKey;
    final UUID playerId;
    WardProfile profile;
    int taskId = -1;
    /** 当前正在搜索的结果下标；-1 表示尚未开始下一格 */
    int current = -1;
    long slotEndsAt;
    /** 当前这一格的搜索总时长（秒），用于按比例算紧急着色窗口 */
    double slotSeconds;
    boolean prefilterDone;
    boolean prefilterStarted;
    long prefilterEndsAt;
    /** 每个槽上一次真正发送出去的帧（材质 + 文本），用于抑制无变化的刷新 */
    WardGui.Frame[] frameCache;

    Search(String sessionKey, UUID playerId) {
      this.sessionKey = sessionKey;
      this.playerId = playerId;
    }

    /** 界面被重建（clear/setItem 全量重写）后必须丢弃帧缓存 */
    void forgetFrameCache() {
      frameCache = null;
    }

    String taskKey() {
      return sessionKey + "#" + playerId;
    }
  }
}
