package com.github.streackmc.Joyous.JwardChest;

import java.util.Locale;
import java.util.Random;

import com.github.streackmc.Joyous.jlogger;

/**
 * 数量取值分布
 * <p>
 * 凡「在 {@code 下限 ~ 上限} 之间取一个整数」的地方都用它——目前两处：
 * <ul>
 *   <li>结果组总条数（各品质下限之和 ~ 上限之和）</li>
 *   <li>单条结果里的物品数量（条目的 {@code 最小数量 ~ 最大数量}）</li>
 * </ul>
 * <b>默认 {@link #UNIFORM}（离散均匀）</b>：区间内每个整数等概率，也就是「设上限 5
 * 不代表一定抽 5 条」。想要偏大/偏小就换 {@link #LOW} / {@link #HIGH} 并调曲线指数。
 *
 * @author kdxiaoyi
 * @since 0.7.0
 */
public enum WardDistribution {

  /** 均匀：区间内每个整数等概率 */
  UNIFORM("uniform"),
  /** 偏小：更容易取到靠下限的值（曲线指数越大越集中） */
  LOW("low"),
  /** 偏大：更容易取到靠上限的值（曲线指数越大越集中） */
  HIGH("high");

  private final String id;

  WardDistribution(String id) {
    this.id = id;
  }

  /** 稳定标识，用于配置 */
  public String id() {
    return id;
  }

  /**
   * 在闭区间 {@code [min, max]} 内按本分布取一个整数
   * <p>
   * 用 {@code floor(shaped * (span + 1))} 而不是 {@code round(shaped * span)}：
   * 后者会让两端各只拿到半个桶的概率，导致最小值和最大值出现的概率只有中间值的一半。
   *
   * @param random 随机源
   * @param min    下限（含）
   * @param max    上限（含）
   * @param curve  曲线指数，仅 {@link #LOW} / {@link #HIGH} 使用，1.0 = 线性
   * @return 区间内的整数；区间为空时返回较小的一端
   */
  public int pick(Random random, int min, int max, double curve) {
    int lo = Math.min(min, max);
    int hi = Math.max(min, max);
    if (hi <= lo) {
      return lo;
    }
    double u = random.nextDouble();
    double exp = Math.max(1.0, curve);
    double shaped = switch (this) {
      case LOW -> Math.pow(u, exp);
      case HIGH -> 1.0 - Math.pow(1.0 - u, exp);
      case UNIFORM -> u;
    };
    int span = hi - lo;
    int offset = (int) Math.floor(shaped * (span + 1));
    if (offset < 0) {
      offset = 0;
    }
    if (offset > span) {
      offset = span;
    }
    return lo + offset;
  }

  /**
   * 按 id 解析分布（大小写不敏感）
   *
   * @param id 标识，可为 null
   * @return 对应分布；无法识别时返回 {@link #UNIFORM} 并记一条调试日志
   */
  public static WardDistribution byId(String id) {
    if (id == null || id.isBlank()) {
      return UNIFORM;
    }
    String needle = id.trim().toLowerCase(Locale.ROOT);
    for (WardDistribution d : values()) {
      if (d.id.equals(needle)) {
        return d;
      }
    }
    jlogger.debug("JwardChest 无法识别的数量分布 [%s]，已按 uniform 处理", id);
    return UNIFORM;
  }

  /** 全部分布 id，斜杠分隔（用于命令提示与文档） */
  public static String ids() {
    StringBuilder sb = new StringBuilder();
    for (WardDistribution d : values()) {
      if (sb.length() > 0) {
        sb.append('/');
      }
      sb.append(d.id);
    }
    return sb.toString();
  }
}
