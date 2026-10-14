package com.github.streackmc.Joyous.JwardChest;

import java.util.Locale;

import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import com.github.streackmc.Joyous.jlogger;

/**
 * 珍惜度（五档）
 * <p>
 * 顺序即强度：{@code ordinal} 越大越珍贵。搜索时长、抽取上限、音效默认参数
 * 都以珍惜度为维度配置。颜色使用原版遗留颜色码，便于直接拼进物品名。
 *
 * @author kdxiaoyi
 * @since 0.7.0
 */
public enum WardRarity {
  /** 普通 · 白 */
  COMMON("common", "普通", "§f"),
  /** 稀有 · 绿 */
  RARE("rare", "稀有", "§a"),
  /** 典藏 · 紫 */
  COLLECT("collect", "典藏", "§d"),
  /** 珍贵 · 金 */
  PRECIOUS("precious", "珍贵", "§6"),
  /** 绝世 · 红 */
  MYTHIC("mythic", "绝世", "§c");

  private final String id;
  private final String displayName;
  private final String color;

  WardRarity(String id, String displayName, String color) {
    this.id = id;
    this.displayName = displayName;
    this.color = color;
  }

  /** 稳定标识，用于配置键与持久化 */
  public String id() {
    return id;
  }

  /** 中文显示名 */
  public String displayName() {
    return displayName;
  }

  /** 原版遗留颜色码（形如 {@code §a}） */
  public String color() {
    return color;
  }

  /**
   * 单个结果的搜索时长（秒）
   * <p>
   * 由「珍惜度基准时长」与「物品数量」共同决定，后者影响很小：
   * {@code 时长 = 基准(珍惜度) + 数量 * 数量系数}。
   *
   * @param amount 该结果的物品数量
   * @return 搜索秒数，至少为 0
   */
  public double searchSeconds(int amount) {
    double base = JwardChestMain.CONF.searchSeconds(this);
    double per = JwardChestMain.CONF.searchAmountFactor();
    double seconds = base + Math.max(0, amount) * per;
    return Math.max(0.0, seconds);
  }

  /** 播放「搜索完成」音效（音高/传播距离按珍惜度递增，均可配置） */
  public void playCompletion(Player player) {
    if (player == null || !player.isOnline())
      return;
    Sound sound = resolveSound(JwardChestMain.CONF.completionSound(this));
    if (sound == null)
      return;
    float volume = JwardChestMain.CONF.completionVolume(this);
    float pitch = JwardChestMain.CONF.completionPitch(this);
    player.playSound(player.getLocation(), sound, volume, pitch);
  }

  /** 按原版命名空间解析音效；无法解析时返回 null 并记一条调试日志 */
  private static Sound resolveSound(String key) {
    if (key == null || key.isBlank())
      return null;
    String normalized = key.trim().toLowerCase(Locale.ROOT);
    NamespacedKey nk = normalized.contains(":")
        ? NamespacedKey.fromString(normalized)
        : NamespacedKey.minecraft(normalized);
    if (nk == null)
      return null;
    try {
      return Registry.SOUNDS.get(nk);
    } catch (Exception e) {
      jlogger.debug("无法解析音效 [%s]：%s", key, e.getLocalizedMessage());
      return null;
    }
  }

  /**
   * 按 id 解析珍惜度（大小写不敏感）
   *
   * @param id 标识，可为 null
   * @return 对应珍惜度；无法识别时返回 {@link #COMMON}
   */
  public static WardRarity byId(String id) {
    if (id == null || id.isBlank())
      return COMMON;
    String needle = id.trim().toLowerCase(Locale.ROOT);
    for (WardRarity r : values()) {
      if (r.id.equals(needle))
        return r;
    }
    return COMMON;
  }

  /** 全部珍惜度 id，逗号分隔（用于命令提示） */
  public static String ids() {
    StringBuilder sb = new StringBuilder();
    for (WardRarity r : values()) {
      if (sb.length() > 0)
        sb.append('/');
      sb.append(r.id);
    }
    return sb.toString();
  }
}
