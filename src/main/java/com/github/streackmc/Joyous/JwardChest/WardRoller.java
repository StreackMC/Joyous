package com.github.streackmc.Joyous.JwardChest;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 结果组抽取
 * <p>
 * 按权重从「待选物品」里抽取，珍惜度决定每个品质最多能抽出几条
 * （{@link WardProfile#maxDraw(WardRarity)}）。权重为 0 的条目保留在列表里但不参与抽取。
 * 结果组总条数 = 各品质上限之和；抽取顺序即结果在容器内的排布顺序。
 *
 * @author kdxiaoyi
 * @since 0.7.0
 */
public final class WardRoller {

  private WardRoller() {
  }

  /**
   * 抽取结果组
   *
   * @param profile 生效设置
   * @param random  随机源
   * @return 结果组，可能为空
   */
  public static List<WardSession.Loot> roll(WardProfile profile, Random random) {
    List<WardSession.Loot> out = new ArrayList<>();

    List<WardItemEntry> pool = new ArrayList<>();
    for (WardItemEntry e : profile.entries()) {
      if (e.active()) {
        pool.add(e);
      }
    }
    if (pool.isEmpty()) {
      return out;
    }

    // 各品质的剩余配额
    Map<WardRarity, Integer> remaining = new EnumMap<>(WardRarity.class);
    for (WardRarity r : WardRarity.values()) {
      remaining.put(r, profile.maxDraw(r));
    }

    int capacity = Math.min(JwardChestMain.CONF.maxResults(), profile.slots());
    int target = Math.min(profile.totalDrawCount(), capacity);
    // 防御：配额之和可能远大于可用条目，用次数上限兜底避免死循环
    int guard = Math.max(1, target) * 8 + 32;
    int attempts = 0;

    while (out.size() < target && attempts < guard) {
      attempts++;

      List<WardItemEntry> candidates = new ArrayList<>();
      for (WardItemEntry e : pool) {
        if (remaining.getOrDefault(e.rarity(), 0) > 0) {
          candidates.add(e);
        }
      }
      if (candidates.isEmpty()) {
        break;
      }

      WardItemEntry picked = weightedPick(candidates, random);
      if (picked == null) {
        break;
      }

      remaining.merge(picked.rarity(), -1, Integer::sum);
      out.add(new WardSession.Loot(picked.display(picked.rollAmount(random)), picked.rarity()));
    }

    return out;
  }

  /** 按权重挑一个；全部权重为 0 时返回 null */
  private static WardItemEntry weightedPick(List<WardItemEntry> candidates, Random random) {
    int total = 0;
    for (WardItemEntry e : candidates) {
      total += e.weight();
    }
    if (total <= 0) {
      return null;
    }
    int roll = random.nextInt(total);
    int cursor = 0;
    for (WardItemEntry e : candidates) {
      cursor += e.weight();
      if (roll < cursor) {
        return e;
      }
    }
    return candidates.get(candidates.size() - 1);
  }

  /**
   * 某条目在给定候选集合中的分摊概率（用于编辑器实时显示）
   *
   * @param entry 条目
   * @param total 全部条目总权重
   * @return 0-1 之间
   */
  public static double share(WardItemEntry entry, int total) {
    if (total <= 0) {
      return 0.0;
    }
    return (double) entry.weight() / total;
  }
}
