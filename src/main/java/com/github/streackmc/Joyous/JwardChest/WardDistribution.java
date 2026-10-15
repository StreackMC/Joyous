package com.github.streackmc.Joyous.JwardChest;

import java.util.Locale;
import java.util.Random;

import javax.annotation.Nullable;

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
 * 不代表一定抽 5 条」。想要偏大/偏小就换 {@link #LOW} / {@link #HIGH} 并调曲线指数；
 * 想要「中间常见、两端罕见」就换 {@link #NORMAL}。
 * <p>
 * 分布属于「容器设置」的一部分（{@link WardProfile}），在编辑界面的「数量分布」子界面里改，
 * 不再走全局 config——同一个服务器上不同箱子可以有不同手感。
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
  HIGH("high"),
  /**
   * 正态：中间（区间中点）最常见，越靠近两端越罕见
   * <p>
   * 用<b>截断离散正态</b>实现——按 {@code exp(-½((i-μ)/σ)²)} 给区间内每个整数算权重再抽取，
   * 而不是「取正态再夹到区间」：后者会在两端堆出一块被压扁的概率，看起来像端点突变。
   * {@code σ = 跨度 / (2 × curve)}，所以曲线指数越大越集中在中间
   * （{@code curve≈3} 时 ±3σ 恰好铺满整个区间）。
   */
  NORMAL("normal");

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
   * @param curve  曲线指数，{@link #UNIFORM} 不用；其余 1.0 = 最平缓，越大越向该分布的重点集中
   * @return 区间内的整数；区间为空时返回较小的一端
   */
  public int pick(Random random, int min, int max, double curve) {
    int lo = Math.min(min, max);
    int hi = Math.max(min, max);
    if (hi <= lo) {
      return lo;
    }
    int span = hi - lo;
    double exp = Math.max(1.0, curve);

    if (this == NORMAL) {
      return pickNormal(random, lo, span, exp);
    }

    double u = random.nextDouble();
    double shaped = switch (this) {
      case LOW -> Math.pow(u, exp);
      case HIGH -> 1.0 - Math.pow(1.0 - u, exp);
      case UNIFORM, NORMAL -> u;
    };
    int offset = (int) Math.floor(shaped * (span + 1));
    if (offset < 0) {
      offset = 0;
    }
    if (offset > span) {
      offset = span;
    }
    return lo + offset;
  }

  /** 截断离散正态：按密度给每个整数加权后抽取，两端自然衰减而不是被夹断 */
  private static int pickNormal(Random random, int lo, int span, double curve) {
    double center = span / 2.0;
    double sigma = Math.max(1e-9, span / (2.0 * curve));
    double total = 0.0;
    double[] weights = new double[span + 1];
    for (int i = 0; i <= span; i++) {
      double z = (i - center) / sigma;
      weights[i] = Math.exp(-0.5 * z * z);
      total += weights[i];
    }
    double roll = random.nextDouble() * total;
    double acc = 0.0;
    for (int i = 0; i <= span; i++) {
      acc += weights[i];
      if (roll < acc) {
        return lo + i;
      }
    }
    return lo + span;
  }

  /**
   * 按 id 解析分布（大小写不敏感）
   *
   * @param id 标识，可为 null
   * @return 对应分布；无法识别时返回 {@link #UNIFORM} 并记一条调试日志
   */
  public static WardDistribution byId(String id) {
    WardDistribution parsed = parse(id);
    if (parsed == null) {
      jlogger.debug("JwardChest 无法识别的数量分布 [%s]，已按 uniform 处理", id);
      return UNIFORM;
    }
    return parsed;
  }

  /**
   * 严格解析分布 id（不做兜底）
   *
   * @param id 标识，可为 null
   * @return 对应分布；无法识别返回 null
   */
  @Nullable
  public static WardDistribution parse(@Nullable String id) {
    if (id == null || id.isBlank()) {
      return null;
    }
    String needle = id.trim().toLowerCase(Locale.ROOT);
    for (WardDistribution d : values()) {
      if (d.id.equals(needle)) {
        return d;
      }
    }
    return null;
  }

  /** 循环取下一个（编辑界面左键） */
  public WardDistribution next() {
    WardDistribution[] all = values();
    return all[(ordinal() + 1) % all.length];
  }

  /** 循环取上一个（编辑界面右键） */
  public WardDistribution previous() {
    WardDistribution[] all = values();
    return all[Math.floorMod(ordinal() - 1, all.length)];
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
