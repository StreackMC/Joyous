package com.github.streackmc.Joyous.JwardChest;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;

import com.github.streackmc.Joyous.jlogger;

/**
 * 运行时容器会话的注册表
 * <p>
 * 会话是临时数据：玩家离开、长时间无人查看、或执行重置命令时都会被移除。
 * 为避免内存膨胀，这里同时提供空闲回收与总数上限裁剪。
 *
 * @author kdxiaoyi
 * @since 0.7.0
 */
public final class WardSessionManager {

  private final Map<String, WardSession> sessions = new ConcurrentHashMap<>();

  // ──────────────────────────────────────────────
  // 键
  // ──────────────────────────────────────────────

  /** 方块级会话键：维度 + 坐标 */
  public static String blockKey(World world, int x, int y, int z) {
    return world.getName() + "@" + x + "," + y + "," + z;
  }

  /** 预览会话键：在方块级键后追加观看者，使模拟结果只对该玩家生效 */
  public static String previewKey(World world, int x, int y, int z, UUID viewer) {
    return blockKey(world, x, y, z) + "#preview:" + viewer;
  }

  // ──────────────────────────────────────────────
  // 增删查
  // ──────────────────────────────────────────────

  /** 取（或新建）一个会话 */
  public WardSession getOrCreate(String key, UUID profileId, World world, int x, int y, int z,
      boolean preview) {
    WardSession session = sessions.computeIfAbsent(key,
        k -> new WardSession(k, profileId, world.getName(), x, y, z, preview));
    session.touch();
    trimIfNeeded();
    return session;
  }

  /** 按方块取会话 */
  public WardSession block(World world, int x, int y, int z) {
    return sessions.get(blockKey(world, x, y, z));
  }

  /** 按方块取预览会话 */
  public WardSession preview(World world, int x, int y, int z, UUID viewer) {
    return sessions.get(previewKey(world, x, y, z, viewer));
  }

  /** 按玩家当前打开的界面定位会话 */
  public WardSession of(Player player) {
    WardGui.Holder holder = WardGui.holderOf(player.getOpenInventory().getTopInventory());
    if (holder != null && holder.payload() instanceof WardSession session) {
      return session;
    }
    return null;
  }

  public WardSession get(String key) {
    return sessions.get(key);
  }

  public void remove(String key) {
    sessions.remove(key);
  }

  /** 移除全部方块级会话（不看预览） */
  public int removeBlockSessions() {
    int count = 0;
    for (String key : new ArrayList<>(sessions.keySet())) {
      if (!key.contains("#preview:")) {
        sessions.remove(key);
        count++;
      }
    }
    return count;
  }

  public int removeByProfile(UUID profileId) {
    int count = 0;
    for (Map.Entry<String, WardSession> e : new ArrayList<>(sessions.entrySet())) {
      if (e.getValue().profileId().equals(profileId)) {
        sessions.remove(e.getKey());
        count++;
      }
    }
    return count;
  }

  /**
   * 按坐标范围移除
   *
   * @param world 维度，null 表示全部维度
   * @param min    各轴下界
   * @param max    各轴上界
   * @return 移除数量
   */
  public int removeInRange(World world, int[] min, int[] max) {
    int count = 0;
    for (Map.Entry<String, WardSession> e : new ArrayList<>(sessions.entrySet())) {
      WardSession s = e.getValue();
      if (world != null && !world.getName().equals(s.world()))
        continue;
      if (s.x() >= min[0] && s.x() <= max[0]
          && s.y() >= min[1] && s.y() <= max[1]
          && s.z() >= min[2] && s.z() <= max[2]) {
        sessions.remove(e.getKey());
        count++;
      }
    }
    return count;
  }

  /** 全部会话（快照） */
  public Collection<WardSession> all() {
    return new ArrayList<>(sessions.values());
  }

  public int size() {
    return sessions.size();
  }

  public void clear() {
    sessions.clear();
  }

  // ──────────────────────────────────────────────
  // 回收
  // ──────────────────────────────────────────────

  /**
   * 回收空闲会话
   *
   * @param idleMillis 空闲阈值
   * @return 回收数量
   */
  public int sweepIdle(long idleMillis) {
    int count = 0;
    for (Map.Entry<String, WardSession> e : new ArrayList<>(sessions.entrySet())) {
      WardSession s = e.getValue();
      if (s.idleMillis() < idleMillis) {
        continue;
      }
      if (isBeingViewed(s)) {
        s.touch();
        continue;
      }
      sessions.remove(e.getKey());
      count++;
    }
    return count;
  }

  /** 超过总数上限时按最久未访问裁剪（正在被查看的跳过） */
  private void trimIfNeeded() {
    int cap = JwardChestMain.CONF.maxSessions();
    if (sessions.size() <= cap) {
      return;
    }
    List<WardSession> sorted = new ArrayList<>(sessions.values());
    sorted.sort(Comparator.comparingLong(WardSession::lastAccess));
    int overflow = sessions.size() - cap;
    int dropped = 0;
    for (WardSession s : sorted) {
      if (dropped >= overflow) {
        break;
      }
      if (isBeingViewed(s)) {
        continue;
      }
      sessions.remove(s.key());
      dropped++;
    }
    if (dropped > 0) {
      jlogger.debug("JwardChest 会话数超过上限，已裁剪 %s 个", dropped);
    }
  }

  /** 是否有玩家正打开着这个会话的界面 */
  private static boolean isBeingViewed(WardSession session) {
    for (Player player : Bukkit.getOnlinePlayers()) {
      WardGui.Holder holder = WardGui.holderOf(player.getOpenInventory().getTopInventory());
      if (holder != null && holder.payload() instanceof WardSession other
          && other.key().equals(session.key())) {
        return true;
      }
    }
    return false;
  }
}
