package com.github.streackmc.Joyous.JwardChest;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.inventory.ItemStack;

/**
 * 一个自定义容器的运行时状态（仅内存）
 * <p>
 * 携带维度与坐标。结果组一旦生成对全部玩家生效；每个玩家的「已搜索」进度相互独立，
 * 玩家关闭容器时会保留其已搜索记录，因此重开无需重搜（进行中的倒计时由
 * {@link WardSearchController} 另行重置）。
 *
 * @author kdxiaoyi
 * @since 0.7.0
 */
public final class WardSession {

  /** 一条结果 */
  public record Loot(ItemStack item, WardRarity rarity) {
  }

  private final String key;
  private final UUID profileId;
  private final String world;
  private final int x;
  private final int y;
  private final int z;
  /** 预览/模拟会话：结果组只对该玩家生效 */
  private final boolean preview;

  private final List<Loot> loot = new ArrayList<>();
  /**
   * 结果下标 → 容器槽位
   * <p>
   * 结果在容器内是<b>随机排布</b>的：抽取后为每条结果随机挑一个互不相同的槽位，
   * 再把结果按槽位升序排列，于是「下标递增」天然等于「先左到右、再上到下」的搜索顺序。
   */
  private int[] slots = new int[0];
  private final Map<UUID, Set<Integer>> revealed = new ConcurrentHashMap<>();

  /** 结果组是否已生成 */
  private volatile boolean generated;
  /** 是否已走过粗筛 */
  private volatile boolean prefilted;
  private volatile long lastAccess = System.currentTimeMillis();

  public WardSession(String key, UUID profileId, String world, int x, int y, int z, boolean preview) {
    this.key = key;
    this.profileId = profileId;
    this.world = world;
    this.x = x;
    this.y = y;
    this.z = z;
    this.preview = preview;
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

  /** 结果组（共享，勿直接改动） */
  public List<Loot> loot() {
    return loot;
  }

  /** 结果条数 */
  public int size() {
    return loot.size();
  }

  /** 结果组是否为空 */
  public boolean empty() {
    return loot.isEmpty();
  }

  /**
   * 用抽取结果填充结果组
   *
   * @param results 结果（须已按槽位升序排列）
   * @param slotOf  与 {@code results} 等长的槽位映射
   */
  public void fill(List<Loot> results, int[] slotOf) {
    loot.clear();
    loot.addAll(results);
    slots = (slotOf == null) ? new int[0] : slotOf.clone();
    generated = true;
    touch();
  }

  /**
   * 第 {@code index} 条结果所在的容器槽位
   *
   * @return 槽位下标；越界返回 -1
   */
  public int slotOf(int index) {
    if (index < 0 || index >= slots.length) {
      return -1;
    }
    return slots[index];
  }

  /** 槽位映射是否与结果数一致（结果组为空时也算一致） */
  public boolean placed() {
    return slots.length == loot.size();
  }

  /** 丢弃结果组（重置命令用） */
  public void reset() {
    loot.clear();
    slots = new int[0];
    generated = false;
    prefilted = false;
    revealed.clear();
    touch();
  }

  /** 某玩家已搜索出的槽位下标 */
  public Set<Integer> revealed(UUID player) {
    return revealed.computeIfAbsent(player, k -> ConcurrentHashMap.newKeySet());
  }

  /** 标记某槽位对该玩家已搜索 */
  public void markRevealed(UUID player, int index) {
    revealed(player).add(index);
  }

  /** 该玩家是否已经搜出全部结果 */
  public boolean allRevealed(UUID player) {
    Set<Integer> set = revealed.get(player);
    return set != null && set.size() >= loot.size();
  }

  /** 清空某玩家的进度（重置命令用） */
  public void clearProgress(UUID player) {
    revealed.remove(player);
  }

  /** 清空全部玩家进度 */
  public void clearAllProgress() {
    revealed.clear();
  }

  /** 还在看这个容器的玩家数（粗筛/搜索动画的活跃度参考） */
  public int trackedPlayers() {
    return revealed.size();
  }

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

  /** 移除某玩家的进度记录，避免玩家离线后残留 */
  public void forgetPlayer(UUID player) {
    revealed.remove(player);
  }

  /** 快照一份已搜索集合，供关闭容器时保存 */
  public Set<Integer> snapshotRevealed(UUID player) {
    return new HashSet<>(revealed(player));
  }

  @Override
  public String toString() {
    return "WardSession[" + key + " profile=" + profileId + " loot=" + loot.size()
        + " generated=" + generated + " viewers=" + revealed.size() + "]";
  }
}
