package com.github.streackmc.Joyous.JwardChest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

import javax.annotation.Nullable;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * 一个自定义容器的运行时状态
 * <p>
 * <b>内容是全服共享的权威数据</b>（{@link #contents}），玩家看到的是各自的一份「视图」：
 * 每个槽位对某个玩家是「已揭开」还是「仍是占位」，由 {@link #revealed} 按玩家记录。
 * 于是「箱子里还剩什么」共享，「我搜到哪了」私有。
 * <p>
 * 内容只会因为三种原因变化：玩家领取（槽位置空）、玩家放入（槽位写入）、或会话被重置
 * （{@link #reset()}，对应命令重置与会话过期）。抽取只在这些时刻发生一次，之后内容就固定了。
 * <p>
 * <b>并发模型</b>：所有内容变更都走 {@link #transact(Function)}——加锁 → 在锁内校验并修改 →
 * 解锁 → 由调用方派发结果（发物品、刷新界面、落库）。锁是可重入的，且所有变更都在主线程发生，
 * 加锁只是为了让「校验—修改」成为一个不可分割的步骤（避免将来异步化时出现读后写覆盖）。
 *
 * @author kdxiaoyi
 * @since 0.7.0
 */
public final class WardSession {

  /** 一次内容操作的结果 */
  public enum OpResult {
    /** 成功 */
    OK,
    /** 槽位越界（容器尺寸变了等情况） */
    OUT_OF_RANGE,
    /** 槽位是空的 */
    EMPTY,
    /** 槽位已经被占用了（放入时） */
    OCCUPIED,
    /** 容器里没有空位了（放入时） */
    CONTAINER_FULL,
    /** 该玩家还没揭开这一格 */
    NOT_REVEALED,
    /** 玩家的背包放不下 */
    PLAYER_FULL
  }

  /**
   * 事务视图：只能在 {@link WardSession#transact(Function)} 内部使用
   * <p>
   * 校验与修改都必须通过它，这样「读到什么」与「改了什么」处于同一把锁内。
   */
  public interface Tx {
    /** 槽位总数 */
    int slotCount();

    /** 槽位是否在范围内 */
    boolean inRange(int slot);

    /** 该槽位是否装着东西 */
    boolean hasItem(int slot);

    /** 该槽位物品的副本；空槽返回 null */
    @Nullable
    ItemStack peek(int slot);

    /** 取走该槽位（置空）；空槽返回 null */
    @Nullable
    ItemStack take(int slot);

    /** 往该槽位放入物品（覆盖），并让所有玩家重新搜索这一格 */
    void put(int slot, ItemStack item);

    /** 该玩家是否已揭开这一格 */
    boolean revealedBy(UUID player, int slot);

    /** 标记该玩家已揭开这一格 */
    void markRevealed(UUID player, int slot);

    /** 容器里还有多少件东西 */
    int remaining();
  }

  private final String key;
  private final UUID profileId;
  private final String world;
  private final int x;
  private final int y;
  private final int z;
  /** 预览/模拟会话：内容不落库，也禁止领取 */
  private final boolean preview;

  /** 权威内容；null 表示空格。长度固定为容器槽位数 */
  private final ItemStack[] contents;

  /** 内容变更锁（见类注释的并发模型） */
  private final ReentrantLock lock = new ReentrantLock();

  /** 玩家 → 已揭开的槽位 */
  private final Map<UUID, Set<Integer>> revealed = new ConcurrentHashMap<>();

  /** 正在看这个容器的玩家 → 他们各自的那份界面 */
  private final Map<UUID, Inventory> viewers = new ConcurrentHashMap<>();

  /** 内容是否已生成（抽取或从存档恢复） */
  private volatile boolean generated;
  /** 是否已走过粗筛 */
  private volatile boolean prefilted;
  private volatile long lastAccess = System.currentTimeMillis();

  public WardSession(String key, UUID profileId, String world, int x, int y, int z,
      int slotCount, boolean preview) {
    this.key = key;
    this.profileId = profileId;
    this.world = world;
    this.x = x;
    this.y = y;
    this.z = z;
    this.preview = preview;
    this.contents = new ItemStack[Math.max(0, slotCount)];
  }

  public String key() {
    return key;
  }

  public UUID profileId() {
    return profileId;
  }

  public String world() {
    return world;
  }

  public int x() {
    return x;
  }

  public int y() {
    return y;
  }

  public int z() {
    return z;
  }

  public boolean preview() {
    return preview;
  }

  public boolean generated() {
    return generated;
  }

  public boolean prefilted() {
    return prefilted;
  }

  public void prefilted(boolean value) {
    this.prefilted = value;
  }

  /** 槽位总数 */
  public int slotCount() {
    return contents.length;
  }

  // ──────────────────────────────────────────────
  // 只读查询（视图渲染用；并发安全性由「主线程 + 只读」保证）
  // ──────────────────────────────────────────────

  /** 该槽位是否装着东西 */
  public boolean hasItem(int slot) {
    return inRange(slot) && contents[slot] != null;
  }

  /** 该槽位物品的副本；空槽或越界返回 null */
  @Nullable
  public ItemStack itemAt(int slot) {
    if (!inRange(slot) || contents[slot] == null) {
      return null;
    }
    return contents[slot].clone();
  }

  /** 该玩家是否已揭开这一格 */
  public boolean revealedBy(UUID player, int slot) {
    if (player == null) {
      return false;
    }
    Set<Integer> set = revealed.get(player);
    return set != null && set.contains(slot);
  }

  /**
   * 该玩家下一格需要搜索的槽位
   * <p>
   * 判据只有一条：<b>这一格装着东西，而该玩家还没揭开它</b>。
   * 空槽（被取走了 / 从来就是空）自然被跳过，所以「已取走」不需要单独的状态位。
   *
   * @return 槽位下标；没有可搜的返回 -1
   */
  public int nextSearchSlot(UUID player) {
    Set<Integer> set = revealed.get(player);
    for (int slot = 0; slot < contents.length; slot++) {
      if (contents[slot] != null && (set == null || !set.contains(slot))) {
        return slot;
      }
    }
    return -1;
  }

  /** 该玩家是否已经把能搜的都搜完了 */
  public boolean searchedAll(UUID player) {
    return nextSearchSlot(player) < 0;
  }

  /** 容器里还剩多少件东西 */
  public int remaining() {
    int count = 0;
    for (ItemStack item : contents) {
      if (item != null) {
        count++;
      }
    }
    return count;
  }

  private boolean inRange(int slot) {
    return slot >= 0 && slot < contents.length;
  }

  // ──────────────────────────────────────────────
  // 事务
  // ──────────────────────────────────────────────

  /**
   * 在锁内执行一次内容操作
   * <p>
   * 调用方负责在返回后派发结果（发物品 / 刷新界面 / 落库）；<b>不要在锁内做 IO 或发包</b>。
   *
   * @param body 事务体
   * @param <R>  返回类型
   * @return 事务体的返回值
   */
  public <R> R transact(Function<Tx, R> body) {
    lock.lock();
    try {
      return body.apply(new TxImpl());
    } finally {
      lock.unlock();
      touch();
    }
  }

  /** 标记某玩家已揭开某格 */
  public void markRevealed(UUID player, int slot) {
    transact(t -> {
      t.markRevealed(player, slot);
      return null;
    });
  }

  /** 事务实现 */
  private final class TxImpl implements Tx {
    @Override
    public int slotCount() {
      return contents.length;
    }

    @Override
    public boolean inRange(int slot) {
      return WardSession.this.inRange(slot);
    }

    @Override
    public boolean hasItem(int slot) {
      return WardSession.this.inRange(slot) && contents[slot] != null;
    }

    @Override
    public ItemStack peek(int slot) {
      return WardSession.this.inRange(slot) && contents[slot] != null ? contents[slot].clone() : null;
    }

    @Override
    public ItemStack take(int slot) {
      if (!hasItem(slot)) {
        return null;
      }
      ItemStack item = contents[slot];
      contents[slot] = null;
      return item;
    }

    @Override
    public void put(int slot, ItemStack item) {
      if (!WardSession.this.inRange(slot) || item == null || item.getType().isAir()) {
        return;
      }
      contents[slot] = item.clone();
      // 放了新东西 → 所有人（包括刚放的人之外）都要重新搜索这一格
      for (Set<Integer> set : revealed.values()) {
        set.remove(slot);
      }
    }

    @Override
    public boolean revealedBy(UUID player, int slot) {
      return WardSession.this.revealedBy(player, slot);
    }

    @Override
    public void markRevealed(UUID player, int slot) {
      if (player == null || !WardSession.this.inRange(slot)) {
        return;
      }
      revealed.computeIfAbsent(player, k -> ConcurrentHashMap.newKeySet()).add(slot);
    }

    @Override
    public int remaining() {
      return WardSession.this.remaining();
    }
  }

  // ──────────────────────────────────────────────
  // 内容装配
  // ──────────────────────────────────────────────

  /**
   * 用抽取结果填充内容：每件物品随机落在一个互不相同的槽位上
   *
   * @param items 抽取结果，超出槽位数的部分被丢弃
   * @param random 随机源
   * @return 实际放进去的件数
   */
  public int fill(List<ItemStack> items, Random random) {
    return transact(t -> {
      for (int i = 0; i < contents.length; i++) {
        contents[i] = null;
      }
      revealed.clear();
      if (items == null || items.isEmpty() || contents.length == 0) {
        return 0;
      }
      List<Integer> slots = new ArrayList<>(contents.length);
      for (int i = 0; i < contents.length; i++) {
        slots.add(i);
      }
      Collections.shuffle(slots, random);
      int count = Math.min(items.size(), contents.length);
      for (int i = 0; i < count; i++) {
        ItemStack item = items.get(i);
        if (item != null && !item.getType().isAir()) {
          contents[slots.get(i)] = item.clone();
        }
      }
      generated = true;
      return count;
    });
  }

  /**
   * 按槽位顺序恢复内容（从存档读取时用）
   *
   * @param ordered 槽位顺序，允许含 null，长度不足处视为空
   * @return 是否成功恢复（长度不匹配时返回 false 且不改动任何内容）
   */
  public boolean restore(List<ItemStack> ordered) {
    if (ordered == null || ordered.size() != contents.length) {
      return false;
    }
    return transact(t -> {
      for (int i = 0; i < contents.length; i++) {
        ItemStack item = ordered.get(i);
        contents[i] = (item == null || item.getType().isAir()) ? null : item.clone();
      }
      revealed.clear();
      generated = true;
      return true;
    });
  }

  /**
   * 槽位顺序的内容快照（含 null 占位），供落库使用
   *
   * @return 与槽位数等长的列表，元素为副本
   */
  public List<ItemStack> snapshot() {
    return transact(t -> {
      List<ItemStack> out = new ArrayList<>(contents.length);
      for (ItemStack item : contents) {
        out.add(item == null ? null : item.clone());
      }
      return out;
    });
  }

  /** 丢弃内容与进度（命令重置 / 会话过期前的收尾） */
  public void reset() {
    transact(t -> {
      for (int i = 0; i < contents.length; i++) {
        contents[i] = null;
      }
      revealed.clear();
      generated = false;
      prefilted = false;
      return null;
    });
  }

  // ──────────────────────────────────────────────
  // 视图（谁在看这个容器）
  // ──────────────────────────────────────────────

  /** 登记一个正在看这个容器的玩家 */
  public void bindViewer(UUID player, Inventory inventory) {
    if (player != null && inventory != null) {
      viewers.put(player, inventory);
    }
  }

  /** 注销一个玩家 */
  public void unbindViewer(UUID player) {
    if (player != null) {
      viewers.remove(player);
    }
  }

  /** 正在看这个容器的玩家（快照，键为玩家 UUID，值为他们各自那份界面） */
  public Map<UUID, Inventory> viewers() {
    return new ConcurrentHashMap<>(viewers);
  }

  // ──────────────────────────────────────────────
  // 访问时间
  // ──────────────────────────────────────────────

  public void touch() {
    this.lastAccess = System.currentTimeMillis();
  }

  public long lastAccess() {
    return lastAccess;
  }

  /** 空闲毫秒数 */
  public long idleMillis() {
    return System.currentTimeMillis() - lastAccess;
  }

  @Override
  public String toString() {
    return "WardSession[" + key + " profile=" + profileId + " slots=" + contents.length
        + " remaining=" + remaining() + " generated=" + generated + " viewers=" + viewers.size() + "]";
  }
}
