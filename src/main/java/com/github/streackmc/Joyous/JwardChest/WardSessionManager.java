package com.github.streackmc.Joyous.JwardChest;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import org.bukkit.Bukkit;
import org.bukkit.World;

import com.github.streackmc.Joyous.utils.jlogger;

/**
 * 运行时容器会话的注册表
 * <p>
 * 会话是临时对象：玩家不看了、长时间无人访问、或执行重置命令时都会被移除。
 * 为避免内存膨胀，这里同时提供空闲回收与总数上限裁剪。
 * <p>
 * <b>「会话过期 = 容器刷新」</b>：空闲回收与显式移除会把容器交给 {@link #evictionListener}
 * （由模块负责删掉它的存档行），于是下次打开时重新抽取，旧内容一律丢弃。
 * 因总数超限而做的裁剪<b>不</b>触发这一点——那只是把缓存挤出去，内容仍会从存档恢复。
 *
 * @author kdxiaoyi
 * @since 0.7.0
 */
public final class WardSessionManager {

  private final Map<String, WardSession> sessions = new ConcurrentHashMap<>();

  /** 会话被「过期/重置」时的回调（用于删除存档，让下次打开重新抽取） */
  private volatile Consumer<WardSession> evictionListener;

  public void setEvictionListener(@Nullable Consumer<WardSession> listener) {
    this.evictionListener = listener;
  }

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

  /**
   * 取（或新建）一个会话
   * <p>
   * 如果同一个方块上的旧会话槽位数与现在对不上（管理员改了容器类型或行数），
   * 旧内容无处安放，直接把旧会话丢弃（等于让容器刷新）再新建。
   *
   * @param slotCount 该容器的槽位数（内容数组的长度）
   */
  public WardSession getOrCreate(String key, UUID profileId, World world, int x, int y, int z,
      int slotCount, boolean preview) {
    WardSession existing = sessions.get(key);
    if (existing != null && existing.slotCount() != slotCount) {
      sessions.remove(key);
      notifyEvicted(existing);
      existing = null;
    }
    if (existing == null) {
      existing = new WardSession(key, profileId, world.getName(), x, y, z, slotCount, preview);
      WardSession previous = sessions.putIfAbsent(key, existing);
      if (previous != null) {
        existing = previous;
      }
    }
    existing.touch();
    trimIfNeeded();
    return existing;
  }

  /** 按方块取会话 */
  public WardSession block(World world, int x, int y, int z) {
    return sessions.get(blockKey(world, x, y, z));
  }

  /** 按方块取预览会话 */
  public WardSession preview(World world, int x, int y, int z, UUID viewer) {
    return sessions.get(previewKey(world, x, y, z, viewer));
  }

  /** 按会话 key 取 */
  public WardSession get(String key) {
    return sessions.get(key);
  }

  /** 移除单个会话（视为「刷新」） */
  public void remove(String key) {
    WardSession removed = sessions.remove(key);
    if (removed != null) {
      notifyEvicted(removed);
    }
  }

  /** 移除全部方块级会话（不看预览） */
  public int removeBlockSessions() {
    int count = 0;
    for (String key : new ArrayList<>(sessions.keySet())) {
      if (!key.contains("#preview:")) {
        WardSession removed = sessions.remove(key);
        if (removed != null) {
          notifyEvicted(removed);
          count++;
        }
      }
    }
    return count;
  }

  /** 按容器移除（解除绑定、重置预设组时用） */
  public int removeByProfile(UUID profileId) {
    int count = 0;
    for (Map.Entry<String, WardSession> e : new ArrayList<>(sessions.entrySet())) {
      if (e.getValue().profileId().equals(profileId)) {
        sessions.remove(e.getKey());
        notifyEvicted(e.getValue());
        count++;
      }
    }
    return count;
  }

  /**
   * 按坐标范围移除
   *
   * @param world 维度，null 表示全部维度
   * @param min   各轴下界
   * @param max   各轴上界
   * @return 移除数量
   */
  public int removeInRange(World world, int[] min, int[] max) {
    int count = 0;
    for (Map.Entry<String, WardSession> e : new ArrayList<>(sessions.entrySet())) {
      WardSession s = e.getValue();
      if (world != null && !world.getName().equals(s.world())) {
        continue;
      }
      if (s.x() >= min[0] && s.x() <= max[0]
          && s.y() >= min[1] && s.y() <= max[1]
          && s.z() >= min[2] && s.z() <= max[2]) {
        sessions.remove(e.getKey());
        notifyEvicted(s);
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

  /** 清空全部会话（视为「刷新」） */
  public void clear() {
    for (WardSession session : new ArrayList<>(sessions.values())) {
      notifyEvicted(session);
    }
    sessions.clear();
  }

  // ──────────────────────────────────────────────
  // 回收
  // ──────────────────────────────────────────────

  /**
   * 回收空闲会话（<b>等于让容器刷新</b>）
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
      notifyEvicted(s);
      count++;
    }
    return count;
  }

  /** 超过总数上限时按最久未访问裁剪（只挤缓存，不算过期，内容仍从存档恢复） */
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
      jlogger.debug("JwardChest 会话数超过上限，已裁剪 %s 个（内容仍在存档里）", dropped);
    }
  }

  /** 是否有玩家正打开着这个会话的界面 */
  private static boolean isBeingViewed(WardSession session) {
    for (UUID viewer : session.viewers().keySet()) {
      if (Bukkit.getPlayer(viewer) != null) {
        return true;
      }
    }
    return false;
  }

  private void notifyEvicted(WardSession session) {
    Consumer<WardSession> listener = this.evictionListener;
    if (listener == null || session == null || session.preview()) {
      return;
    }
    try {
      listener.accept(session);
    } catch (Exception e) {
      jlogger.warn("JwardChest 处理会话回收回调时出错：%s", e.getLocalizedMessage(), e);
    }
  }
}
