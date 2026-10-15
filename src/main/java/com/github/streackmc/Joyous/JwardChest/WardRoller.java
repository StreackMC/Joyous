package com.github.streackmc.Joyous.JwardChest;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 结果组抽取
 * <p>
 * 按权重从「待选物品」里抽取。权重为 0 的条目保留在列表里但不参与抽取。
 * <p>
 * <b>数量规则（上/下限语义）</b>：
 * <ul>
 *   <li>{@link WardProfile#maxDraw(WardRarity)} 是该珍惜度<b>最多</b>能抽出几条，
 *       不再等于「一定抽这么多」；为 0 表示不设上限。</li>
 *   <li>{@link WardProfile#minDraw(WardRarity)} 是该珍惜度<b>至少</b>抽出几条（保底）；
 *       为 0 表示不设保底。</li>
 *   <li>结果组总条数在「各珍惜度下限之和 ~ 上限之和」之间按
 *       {@link WardProfile#countDistribution()}（默认{@link WardDistribution#UNIFORM}）取值，
 *       再被容器槽位数与配置上限夹一次；全部上/下限都是 0 时视为「不限量」，按容器容量抽满。
 *       因此「上限设 10」<b>不等于</b>每次都抽 10 条，而是最多 10 条。</li>
 * </ul>
 * 抽取分两步：先给每个珍惜度发满它的保底（保底优先，不受权重影响），
 * 再把剩余条数按权重分摊到各珍惜度、并受各自上限约束。
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

    // 按珍惜度分组（只保留参与抽取的条目），顺便算出各珍惜度的可用权重
    Map<WardRarity, List<WardItemEntry>> byRarity = new EnumMap<>(WardRarity.class);
    Map<WardRarity, Integer> weightOf = new EnumMap<>(WardRarity.class);
    for (WardRarity r : WardRarity.values()) {
      byRarity.put(r, new ArrayList<>());
      weightOf.put(r, 0);
    }
    for (WardItemEntry e : profile.entries()) {
      if (e.active()) {
        byRarity.get(e.rarity()).add(e);
        weightOf.merge(e.rarity(), Math.max(0, e.weight()), Integer::sum);
      }
    }
    boolean any = false;
    for (List<WardItemEntry> list : byRarity.values()) {
      if (!list.isEmpty()) {
        any = true;
        break;
      }
    }
    if (!any) {
      return out;
    }

    // 没有可抽条目的珍惜度，它的上/下限是无意义的，按 0 处理
    Map<WardRarity, Integer> floor = new EnumMap<>(WardRarity.class);
    Map<WardRarity, Integer> cap = new EnumMap<>(WardRarity.class);
    int sumFloor = 0;
    int sumCap = 0;
    for (WardRarity r : WardRarity.values()) {
      boolean usable = !byRarity.get(r).isEmpty();
      int mn = usable ? profile.minDraw(r) : 0;
      int mx = usable ? profile.maxDraw(r) : 0;
      floor.put(r, mn);
      cap.put(r, mx);
      sumFloor += mn;
      sumCap += mx;
    }

    int capacity = Math.max(1, Math.min(JwardChestMain.CONF.maxResults(), profile.slots()));
    int target;
    if (sumCap <= 0 && sumFloor <= 0) {
      // 完全没设上/下限 → 不限量，抽满容器
      target = capacity;
    } else {
      // 上限之和小于保底之和时以保底为准（minDraw/maxDraw 的联动一般不会让这种情况出现）
      int high = Math.min(Math.max(sumCap, sumFloor), capacity);
      // 下限没设时也不给出 0 条——那会是个空箱子（想要「可以为空」就把最小抽出数量设成 0 并接受它）
      int low = Math.min(Math.max(sumFloor, 1), high);
      target = profile.countDistribution().pick(random, low, high, profile.countCurve());
    }

    Map<WardRarity, Integer> used = new EnumMap<>(WardRarity.class);
    for (WardRarity r : WardRarity.values()) {
      used.put(r, 0);
    }

    // ① 保底：先把每个珍惜度的下限发满（与权重无关）
    for (WardRarity r : WardRarity.values()) {
      int need = floor.get(r);
      for (int i = 0; i < need && out.size() < target; i++) {
        WardItemEntry e = weightedPick(byRarity.get(r), random);
        if (e == null) {
          break;
        }
        out.add(loot(e, random, profile));
        used.merge(r, 1, Integer::sum);
      }
    }

    // ② 分摊：剩余条数按各珍惜度的可用权重随机分配，受各自上限约束（0 = 不限）
    int guard = Math.max(1, target) * 8 + 32;
    while (out.size() < target && guard-- > 0) {
      int totalWeight = 0;
      for (WardRarity r : WardRarity.values()) {
        if (!open(byRarity, weightOf, cap, used, r)) {
          continue;
        }
        totalWeight += weightOf.get(r);
      }
      if (totalWeight <= 0) {
        break;
      }

      int slot = random.nextInt(totalWeight);
      WardRarity picked = null;
      for (WardRarity r : WardRarity.values()) {
        if (!open(byRarity, weightOf, cap, used, r)) {
          continue;
        }
        slot -= weightOf.get(r);
        if (slot < 0) {
          picked = r;
          break;
        }
      }
      if (picked == null) {
        break;
      }

      WardItemEntry e = weightedPick(byRarity.get(picked), random);
      if (e == null) {
        break;
      }
      out.add(loot(e, random, profile));
      used.merge(picked, 1, Integer::sum);
    }

    return out;
  }

  /** 该珍惜度当前是否还能再分到条数（有可抽条目、有正权重、且未达上限） */
  private static boolean open(Map<WardRarity, List<WardItemEntry>> byRarity,
      Map<WardRarity, Integer> weightOf, Map<WardRarity, Integer> cap,
      Map<WardRarity, Integer> used, WardRarity r) {
    if (byRarity.get(r).isEmpty() || weightOf.getOrDefault(r, 0) <= 0) {
      return false;
    }
    int limit = cap.getOrDefault(r, 0);
    return limit <= 0 || used.getOrDefault(r, 0) < limit;
  }

  /** 抽一条结果：物品实例（数量按条目范围 + 容器的数量分布取值）+ 它的珍惜度 */
  private static WardSession.Loot loot(WardItemEntry entry, Random random, WardProfile profile) {
    int amount = entry.rollAmount(random, profile.amountDistribution(), profile.amountCurve());
    return new WardSession.Loot(entry.display(amount), entry.rarity());
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
