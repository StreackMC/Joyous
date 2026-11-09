package com.github.streackmc.Joyous.JwardChest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import com.github.streackmc.Joyous.Joyous;
import com.github.streackmc.Joyous.i18n;
import com.github.streackmc.Joyous.jlogger;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/**
 * 运行时容器的打开、粗筛、搜索动画与内容操作
 * <p>
 * <b>共享内容 + 私有视图</b>：容器内容（{@link WardSession#transact}）是全服唯一的一份，
 * 每个玩家开到的是一份只属于自己的界面；「这一格我揭开了没有」按玩家记录，
 * 所以两个人可以同时搜同一个箱子而互不影响进度，但谁把东西拿走，所有人都看得到。
 * <p>
 * <b>领取与放入</b>都遵守同一套原子模型：发起 → 加锁 → 校验与修改（同一个事务里完成）→
 * 解锁 → 落库 → 派发结果（发物品、刷新所有视图、提示）。落库失败时会把内存改动回滚，
 * 保证「玩家拿到了但库里还在」这种复制不会发生。
 * <p>
 * <b>刷新规则</b>：内容只会因为「命令重置」或「会话过期」而重新抽取，服务器重启不会补货；
 * 重新抽取时旧内容一律丢弃。
 *
 * @author kdxiaoyi
 * @since 0.7.0
 */
public final class WardSearchController {

  private final JwardChestMain module;

  /** 进行中的搜索任务：会话键 + 玩家 → 状态 */
  private final Map<String, Search> running = new ConcurrentHashMap<>();

  public WardSearchController(JwardChestMain module) {
    this.module = module;
  }

  // ──────────────────────────────────────────────
  // 打开
  // ──────────────────────────────────────────────

  /** 为玩家打开一个绑定方块的容器（带动画） */
  public void open(Player player, WardProfile profile, WardSession session) {
    open(player, profile, session, true);
  }

  /**
   * 为玩家打开一个绑定方块的容器
   *
   * @param animate 是否走粗筛与搜索动画；{@code false} 时直接把内容摆出来（编辑界面的 Shift 预览）
   */
  public void open(Player player, WardProfile profile, WardSession session, boolean animate) {
    ensureGenerated(profile, session);

    if (session.remaining() <= 0) {
      sendEmptyReason(player, profile, session);
      return;
    }

    WardGui.Kind kind = session.preview() ? WardGui.Kind.PREVIEW : WardGui.Kind.RUNTIME;
    WardGui.Holder holder = new WardGui.Holder(kind, profile.id(), session);
    Inventory inventory = profile.container().create(holder, profile.rows(),
        WardGui.title(player, profile.title()));
    holder.attach(inventory);
    // 不走动画的场合（编辑界面的 Shift 预览）直接把内容全摆出来
    buildView(inventory, session, player.getUniqueId(), !animate);

    player.openInventory(inventory);
    session.bindViewer(player.getUniqueId(), inventory);

    stop(player); // 清理可能残留的旧任务

    if (!animate) {
      return;
    }
    if (session.searchedAll(player.getUniqueId())) {
      jlogger.debug("玩家 %s 重开容器 %s，能搜的都搜过了，不再启动搜索",
          player.getName(), session.key());
      return;
    }
    start(player, profile, session);
  }

  /** 空内容时给出准确原因 */
  private static void sendEmptyReason(Player player, WardProfile profile, WardSession session) {
    boolean anyEntry = !profile.entries().isEmpty();
    boolean anyActive = profile.entries().stream().anyMatch(WardItemEntry::active);
    if (!anyEntry || !anyActive) {
      player.sendMessage(anyEntry
          ? i18n.tr("jwardchest.container.empty_zero_weight")
          : i18n.tr("jwardchest.container.empty"));
      return;
    }
    // 配置里有货、容器却是空的 → 已经被搜出来的人拿光了（共享箱的正常结局）
    player.sendMessage(session.generated()
        ? i18n.tr("jwardchest.container.depleted")
        : i18n.tr("jwardchest.container.empty"));
  }

  /**
   * 保证内容已就绪：先尝试从存档恢复，恢复不了才抽取
   * <p>
   * 「服务器重启不补货」靠的就是这一步——只要存档里有内容，就原样恢复，不重新抽。
   */
  private void ensureGenerated(WardProfile profile, WardSession session) {
    if (session.generated()) {
      return;
    }

    if (!session.preview() && module.contents() != null && module.contents().available()) {
      try {
        WardStore.Payload payload = module.contents().load(session.profileId());
        if (payload != null && payload.generated()
            && payload.contents().size() == session.slotCount()
            && session.restore(payload.contents())) {
          jlogger.debug("JwardChest 容器 %s 已从存档恢复 %s 件内容",
              session.key(), session.remaining());
          return;
        }
      } catch (WardStore.WardStoreException e) {
        // 读不到存档不该让箱子打不开：记一笔，继续走重新抽取
        module.reportStorageError("读取容器内容", e);
      }
    }

    Random random = new Random();
    List<ItemStack> items = WardRoller.roll(profile, random);
    int placed = session.fill(items, random);
    jlogger.debug("JwardChest 为容器 %s 生成了 %s 件内容（容器 %s 格）",
        session.key(), placed, session.slotCount());
    persist(session);
  }

  // ──────────────────────────────────────────────
  // 视图
  // ──────────────────────────────────────────────

  /**
   * 按「当前玩家的视角」重建整个界面
   *
   * @param showAll true 时无视揭开进度、把内容全部摆出来（只用于不走动画的预览）
   */
  private static void buildView(Inventory inventory, WardSession session, UUID player,
      boolean showAll) {
    inventory.clear();
    for (int slot = 0; slot < session.slotCount() && slot < inventory.getSize(); slot++) {
      if (showAll) {
        inventory.setItem(slot, session.itemAt(slot));
      } else {
        renderSlot(inventory, session, player, slot);
      }
    }
  }

  /**
   * 按「当前玩家的视角」重画一格
   * <p>
   * 三态只有一个判据：有东西且我揭开过 → 真物品；有东西但我没揭开 → 占位；没东西 → 空。
   * 「被别人取走了」自然落到「空」，不需要额外的状态位。
   */
  private static void renderSlot(Inventory inventory, WardSession session, UUID player, int slot) {
    if (inventory == null || slot < 0 || slot >= inventory.getSize()) {
      return;
    }
    ItemStack item = session.itemAt(slot);
    if (item == null) {
      inventory.setItem(slot, null);
    } else if (session.revealedBy(player, slot)) {
      inventory.setItem(slot, item);
    } else {
      inventory.setItem(slot, WardGui.unsearchedPane());
    }
  }

  /** 把某一格的变化推给所有正在看这个容器的人 */
  private void broadcast(WardSession session, int slot) {
    for (Map.Entry<UUID, Inventory> viewer : session.viewers().entrySet()) {
      if (Bukkit.getPlayer(viewer.getKey()) == null) {
        continue;
      }
      renderSlot(viewer.getValue(), session, viewer.getKey(), slot);
    }
  }

  // ──────────────────────────────────────────────
  // 领取
  // ──────────────────────────────────────────────

  /**
   * 领取某一格（点击即领取）
   * <p>
   * 事务内完成「校验 + 取出」，事务外落库、发放、广播。落库失败会把物品放回原格，
   * 因为「库里还有、玩家手里也有」就是一次复制。
   *
   * @param player  领取者
   * @param session 容器会话
   * @param slot    槽位
   * @return 是否真的领取成功
   */
  public boolean claim(Player player, WardSession session, int slot) {
    if (player == null || session == null) {
      return false;
    }
    if (session.preview()) {
      player.sendMessage(i18n.tr("jwardchest.container.preview_only"));
      return false;
    }
    UUID uuid = player.getUniqueId();
    ItemStack[] box = new ItemStack[1];

    WardSession.OpResult result = session.transact(t -> {
      if (!t.inRange(slot)) {
        return WardSession.OpResult.OUT_OF_RANGE;
      }
      if (!t.hasItem(slot)) {
        return WardSession.OpResult.EMPTY;
      }
      if (!t.revealedBy(uuid, slot)) {
        return WardSession.OpResult.NOT_REVEALED;
      }
      ItemStack peek = t.peek(slot);
      if (peek == null) {
        return WardSession.OpResult.EMPTY;
      }
      if (!canFit(player, peek)) {
        return WardSession.OpResult.PLAYER_FULL;
      }
      box[0] = t.take(slot);
      return WardSession.OpResult.OK;
    });

    if (result != WardSession.OpResult.OK) {
      reportOpFailure(player, result);
      return false;
    }

    if (!persist(session)) {
      ItemStack taken = box[0];
      session.transact(t -> {
        t.put(slot, taken);
        t.markRevealed(uuid, slot);
        return null;
      });
      player.sendMessage(i18n.tr("jwardchest.container.aborted"));
      return false;
    }

    give(player, box[0]);
    broadcast(session, slot);
    player.sendMessage(i18n.tr("jwardchest.container.claimed", displayName(box[0])));
    return true;
  }

  // ──────────────────────────────────────────────
  // 放入
  // ──────────────────────────────────────────────

  /**
   * 把光标上的物品放进某一格
   * <p>
   * 放入的物品不一定来自本模块：允许的话，它没有品质标记也能放，读取方按
   * {@link WardRarity#fallback()} 算搜索时长与配色；不允许的话（配置
   * {@code session.allow-foreign-place: false}）只有带标记的物品能放进来。
   *
   * @param amount 放几个（右键 1 个，左键一整摞）
   */
  public boolean placeFromCursor(Player player, WardSession session, int slot, int amount) {
    if (player == null || session == null) {
      return false;
    }
    if (!checkPlaceAllowed(player, session)) {
      return false;
    }
    ItemStack cursor = player.getItemOnCursor();
    if (cursor == null || cursor.getType().isAir()) {
      return false;
    }
    int move = Math.max(1, Math.min(amount, cursor.getAmount()));
    ItemStack moving = cursor.clone();
    moving.setAmount(move);
    UUID uuid = player.getUniqueId();

    WardSession.OpResult result = session.transact(t -> {
      if (!t.inRange(slot)) {
        return WardSession.OpResult.OUT_OF_RANGE;
      }
      if (t.hasItem(slot)) {
        return WardSession.OpResult.OCCUPIED;
      }
      if (!acceptsPlacement(moving)) {
        return WardSession.OpResult.FOREIGN_ITEM;
      }
      t.put(slot, moving);
      t.markRevealed(uuid, slot);
      return WardSession.OpResult.OK;
    });

    if (result != WardSession.OpResult.OK) {
      reportOpFailure(player, result);
      return false;
    }

    if (!persist(session)) {
      session.transact(t -> {
        t.take(slot);
        return null;
      });
      player.sendMessage(i18n.tr("jwardchest.container.aborted"));
      return false;
    }

    if (move >= cursor.getAmount()) {
      player.setItemOnCursor(null);
    } else {
      ItemStack rest = cursor.clone();
      rest.setAmount(cursor.getAmount() - move);
      player.setItemOnCursor(rest);
    }
    broadcast(session, slot);
    player.sendMessage(i18n.tr("jwardchest.container.placed", move, displayName(moving)));
    return true;
  }

  /**
   * 把玩家背包里某一格的东西放进容器（Shift+左键）
   *
   * @param sourceSlot 背包槽位下标
   * @return 是否成功
   */
  public boolean placeFromInventory(Player player, WardSession session, int sourceSlot) {
    if (player == null || session == null) {
      return false;
    }
    if (!checkPlaceAllowed(player, session)) {
      return false;
    }
    ItemStack source = player.getInventory().getItem(sourceSlot);
    if (source == null || source.getType().isAir()) {
      return false;
    }
    ItemStack moving = source.clone();
    UUID uuid = player.getUniqueId();
    int[] target = { -1 };

    WardSession.OpResult result = session.transact(t -> {
      if (!acceptsPlacement(moving)) {
        return WardSession.OpResult.FOREIGN_ITEM;
      }
      int free = -1;
      for (int i = 0; i < t.slotCount(); i++) {
        if (!t.hasItem(i)) {
          free = i;
          break;
        }
      }
      if (free < 0) {
        return WardSession.OpResult.CONTAINER_FULL;
      }
      t.put(free, moving);
      t.markRevealed(uuid, free);
      target[0] = free;
      return WardSession.OpResult.OK;
    });

    if (result != WardSession.OpResult.OK) {
      reportOpFailure(player, result);
      return false;
    }

    if (!persist(session)) {
      session.transact(t -> {
        t.take(target[0]);
        return null;
      });
      player.sendMessage(i18n.tr("jwardchest.container.aborted"));
      return false;
    }

    player.getInventory().setItem(sourceSlot, null);
    broadcast(session, target[0]);
    player.sendMessage(i18n.tr("jwardchest.container.placed", moving.getAmount(), displayName(moving)));
    return true;
  }

  /** 放入前置检查（预览/开关） */
  private boolean checkPlaceAllowed(Player player, WardSession session) {
    if (session.preview()) {
      player.sendMessage(i18n.tr("jwardchest.container.preview_only"));
      return false;
    }
    if (!JwardChestMain.CONF.allowPlace()) {
      player.sendMessage(i18n.tr("jwardchest.container.place_disabled"));
      return false;
    }
    return true;
  }

  /**
   * 这件物品允不允许放进容器
   * <p>
   * 配置 {@code session.allow-foreign-place} 关掉时，只接受<b>带品质标记</b>的物品，
   * 也就是本模块抽出来的那些（见 {@link WardTag#hasTag(ItemStack)}）。
   *
   * @param item 待放入的物品
   */
  private static boolean acceptsPlacement(ItemStack item) {
    return JwardChestMain.CONF.allowForeignPlace() || WardTag.hasTag(item);
  }

  /** 把失败原因说清楚，别让玩家对着空气点 */
  private static void reportOpFailure(Player player, WardSession.OpResult result) {
    switch (result) {
      case EMPTY -> player.sendMessage(i18n.tr("jwardchest.container.slot_empty"));
      case OCCUPIED -> player.sendMessage(i18n.tr("jwardchest.container.slot_occupied"));
      case CONTAINER_FULL -> player.sendMessage(i18n.tr("jwardchest.container.container_full"));
      case NOT_REVEALED -> player.sendMessage(i18n.tr("jwardchest.container.not_revealed"));
      case PLAYER_FULL -> player.sendMessage(i18n.tr("jwardchest.container.full"));
      case FOREIGN_ITEM -> player.sendMessage(i18n.tr("jwardchest.container.foreign_denied"));
      case OUT_OF_RANGE -> player.sendMessage(i18n.tr("jwardchest.container.aborted"));
      default -> {
      }
    }
  }

  // ──────────────────────────────────────────────
  // 落库
  // ──────────────────────────────────────────────

  /**
   * 把当前内容写进存档
   *
   * @return true 表示已持久化（或本来就无需持久化）
   */
  private boolean persist(WardSession session) {
    if (session == null || session.preview()) {
      return true;
    }
    if (module.contents() == null || !module.contents().available()) {
      return true;
    }
    try {
      module.contents().save(session.profileId(), true, session.snapshot());
      return true;
    } catch (WardStore.WardStoreException e) {
      module.reportStorageError("保存容器内容", e);
      return false;
    }
  }

  /** 让某个玩家的内容立刻生效（供给外部在改设置后调用） */
  public void refresh(WardSession session) {
    if (session == null) {
      return;
    }
    for (Map.Entry<UUID, Inventory> viewer : session.viewers().entrySet()) {
      if (Bukkit.getPlayer(viewer.getKey()) == null) {
        continue;
      }
      buildView(viewer.getValue(), session, viewer.getKey(), false);
    }
  }

  // ──────────────────────────────────────────────
  // 任务生命周期
  // ──────────────────────────────────────────────

  private void start(Player player, WardProfile profile, WardSession session) {
    Search s = new Search(session.key(), player.getUniqueId());
    s.profile = profile;
    s.current = -1;
    s.prefilterDone = !profile.prefilterEnabled() || session.prefilted(player.getUniqueId());
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

    // 粗筛结束：回到「按自己的进度渲染」的视图
    session.prefilted(s.playerId, true);
    s.prefilterDone = true;
    s.forgetFrameCache();
    buildView(open, session, s.playerId, false);
  }

  /** 正常搜索：逐槽位倒计时，完成后揭开并显示该格当前内容 */
  private void tickSearch(Search s, WardSession session, Inventory open, Player player, long now) {
    if (s.current < 0) {
      int slot = session.nextSearchSlot(s.playerId);
      if (slot < 0) {
        player.sendMessage(i18n.tr("jwardchest.container.searched_all"));
        cancel(s);
        return;
      }
      s.current = slot;
      WardRarity rarity = WardTag.rarityOf(session.itemAt(slot));
      int amount = Math.max(1, itemAmount(session, slot));
      s.slotSeconds = rarity.searchSeconds(amount);
      s.slotEndsAt = now + (long) (s.slotSeconds * 1000.0);
      push(s, open, slot, WardGui.slotFrame(rarity, s.slotEndsAt - now, s.slotSeconds));
      return;
    }

    int slot = s.current;
    if (slot >= open.getSize() || slot >= session.slotCount()) {
      // 尺寸对不上（会话本该在打开时被换掉，这里只是兜底）：
      // 这一格画不出来，直接收工——**绝不能把 current 置回 -1 再重选同一格**，那会变成死循环
      jlogger.debug("JwardChest 容器 %s 的槽位 %s 超出界面范围，已停止本次搜索",
          session.key(), slot);
      cancel(s);
      return;
    }

    if (!session.hasItem(slot)) {
      // 倒计时途中被别人取走了：不必再等，直接揭过
      session.markSearched(s.playerId, slot);
      renderSlot(open, session, s.playerId, slot);
      s.current = -1;
      return;
    }

    long remain = s.slotEndsAt - now;
    ItemStack target = session.itemAt(slot);
    WardRarity rarity = WardTag.rarityOf(target);
    if (remain > 0) {
      // 逐结果阶段帧内文本恒定，只有「进入紧急窗口」那一刻会变（材质与名字一起变）
      push(s, open, slot, WardGui.slotFrame(rarity, remain, s.slotSeconds));
      return;
    }

    // 完成：揭开这一格（内容仍在容器里，谁点谁拿走）
    session.markSearched(s.playerId, slot);
    renderSlot(open, session, s.playerId, slot);
    rarity.playCompletion(player);
    s.current = -1;

    if (session.searchedAll(s.playerId)) {
      player.sendMessage(i18n.tr("jwardchest.container.searched_all"));
      cancel(s);
    }
  }

  /** 某格的物品数量（用于搜索时长里的一点点加成） */
  private static int itemAmount(WardSession session, int slot) {
    ItemStack item = session.itemAt(slot);
    return item == null ? 1 : item.getAmount();
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
   * 标准容器（槽位数是 9 的整数倍）把 {@code 行×列} 的区域<b>正中心</b>摆放：
   * 水平居中；垂直方向用整除向下取整，所以行数为偶数时偏上。
   * 非标准容器（漏斗、熔炉、酿造台等）不做居中，统一从 0 号槽位起顺序铺开。
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

  /** 容器里现有物品中最高的珍惜度（粗筛阶段用它着色）；空箱回落到兜底档 */
  private static WardRarity highestRarity(WardSession session) {
    WardRarity highest = null;
    for (int slot = 0; slot < session.slotCount(); slot++) {
      ItemStack item = session.itemAt(slot);
      if (item == null) {
        continue;
      }
      WardRarity rarity = WardTag.rarityOf(item);
      if (highest == null || rarity.compareTo(highest) > 0) {
        highest = rarity;
      }
    }
    return highest == null ? WardRarity.fallback() : highest;
  }

  // ──────────────────────────────────────────────
  // 发放
  // ──────────────────────────────────────────────

  /** 背包放不放得下（只读判断，不动背包） */
  private static boolean canFit(Player player, ItemStack item) {
    if (item == null) {
      return false;
    }
    if (player.getInventory().firstEmpty() >= 0) {
      return true;
    }
    int room = 0;
    int maxStack = Math.max(1, item.getMaxStackSize());
    for (ItemStack stack : player.getInventory().getStorageContents()) {
      if (stack != null && stack.isSimilar(item)) {
        room += Math.max(0, maxStack - stack.getAmount());
      }
      if (room >= item.getAmount()) {
        return true;
      }
    }
    return room >= item.getAmount();
  }

  /** 发放物品；背包塞不下的部分掉在玩家脚下 */
  private static void give(Player player, ItemStack item) {
    if (item == null || item.getType().isAir()) {
      return;
    }
    for (ItemStack rest : player.getInventory().addItem(item).values()) {
      player.getWorld().dropItemNaturally(player.getLocation(), rest);
    }
  }

  /** 物品的显示名（用于领取提示） */
  private static String displayName(ItemStack item) {
    if (item == null) {
      return "";
    }
    ItemMeta meta = item.getItemMeta();
    if (meta != null) {
      Component name = meta.displayName();
      if (name != null) {
        String plain = PlainTextComponentSerializer.plainText().serialize(name);
        if (!plain.isBlank()) {
          return plain;
        }
      }
    }
    return item.getType().name().toLowerCase(java.util.Locale.ROOT);
  }

  // ──────────────────────────────────────────────
  // 状态
  // ──────────────────────────────────────────────

  private static final class Search {
    final String sessionKey;
    final UUID playerId;
    WardProfile profile;
    int taskId = -1;
    /** 当前正在搜索的槽位；-1 表示尚未开始下一格 */
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
