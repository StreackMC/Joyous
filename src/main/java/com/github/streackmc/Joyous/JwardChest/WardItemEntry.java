package com.github.streackmc.Joyous.JwardChest;

import java.util.Base64;
import java.util.Random;

import javax.annotation.Nullable;

import org.bukkit.inventory.ItemStack;

import com.github.streackmc.Joyous.jlogger;

/**
 * 待选物品条目
 * <p>
 * 编辑界面「待选物品」子菜单里的一格物品格所代表的数据：物品模板 + 权重 + 珍惜度 + 数量范围。
 * 权重为 0 表示「保留在列表里但不参与抽取」。数量范围遵守物品自身的
 * {@code max_stack_size} 组件。
 *
 * @author kdxiaoyi
 * @since 0.7.0
 */
public final class WardItemEntry {

  /** 物品模板，数量恒为 1 */
  private final ItemStack template;
  private int weight;
  private WardRarity rarity;
  private int minAmount;
  private int maxAmount;

  public WardItemEntry(ItemStack template, int weight, WardRarity rarity, int minAmount, int maxAmount) {
    ItemStack copy = template.clone();
    copy.setAmount(1);
    this.template = copy;
    this.weight = Math.max(0, weight);
    this.rarity = rarity == null ? WardRarity.COMMON : rarity;
    setAmounts(minAmount, maxAmount);
  }

  /**
   * 以默认参数包装一个物品
   * <p>
   * <b>数量会从物品堆叠继承</b>：放进去 32 个，最小/最大数量就都是 32（上限受该物品的
   * {@code max_stack_size} 组件约束）。原来的实现恒为 1，等于把玩家的数量悄悄丢了。
   */
  public static WardItemEntry of(ItemStack template) {
    int amount = Math.max(1, template.getAmount());
    return new WardItemEntry(template, 1, WardRarity.COMMON, amount, amount);
  }

  /** 物品模板（数量恒为 1），调用方需要自行 clone */
  public ItemStack template() {
    return template.clone();
  }

  /**
   * 生成用于展示的物品副本
   *
   * @param amount 数量，会被夹取到 1..maxStackSize
   */
  public ItemStack display(int amount) {
    ItemStack item = template.clone();
    item.setAmount(Math.max(1, Math.min(maxStackSize(), amount)));
    return item;
  }

  /** 该物品允许的最大堆叠数（读 max_stack_size 组件，缺失则取材质默认值） */
  public int maxStackSize() {
    int max = template.getMaxStackSize();
    return max <= 0 ? 1 : max;
  }

  public int weight() {
    return weight;
  }

  /** 设置权重，允许 0，负数按 0 处理 */
  public void weight(int weight) {
    this.weight = Math.max(0, weight);
  }

  public WardRarity rarity() {
    return rarity;
  }

  public void rarity(WardRarity rarity) {
    this.rarity = rarity == null ? WardRarity.COMMON : rarity;
  }

  public int minAmount() {
    return minAmount;
  }

  public int maxAmount() {
    return maxAmount;
  }

  /**
   * 设置数量范围，自动夹取到 {@code 1..maxStackSize}
   * <p>
   * 用于「一次给两个值」的场合（构造、反序列化）：两个值都夹取后若 min &gt; max，
   * 则让 <b>max 抬到 min</b>（以最小值为准）。交互式修改请用
   * {@link #setMin(int)} / {@link #setMax(int)}，那两个会让「对方」跟着动。
   *
   * @param min 最小数量
   * @param max 最大数量
   */
  public void setAmounts(int min, int max) {
    int cap = maxStackSize();
    int low = Math.max(1, Math.min(cap, min));
    int high = Math.max(1, Math.min(cap, max));
    if (high < low) {
      high = low;
    }
    this.minAmount = low;
    this.maxAmount = high;
  }

  /**
   * 设置最小数量
   * <p>
   * 若因此超过当前最大值，<b>最大值同步抬到最小值</b>（而不是把最小值压回去）——
   * 操作者刚输入的那个数字应当被尊重。
   */
  public void setMin(int min) {
    int cap = maxStackSize();
    int low = Math.max(1, Math.min(cap, min));
    if (low > maxAmount) {
      maxAmount = low;
    }
    minAmount = low;
  }

  /**
   * 设置最大数量
   * <p>
   * 若因此低于当前最小值，<b>最小值同步降到最大值</b>（与 {@link #setMin(int)} 对称）。
   */
  public void setMax(int max) {
    int cap = maxStackSize();
    int high = Math.max(1, Math.min(cap, max));
    if (high < minAmount) {
      minAmount = high;
    }
    maxAmount = high;
  }

  /** 调整最小数量（相对增减，最大值会跟着联动） */
  public void adjustMin(int delta) {
    setMin(minAmount + delta);
  }

  /** 调整最大数量（相对增减，最小值会跟着联动） */
  public void adjustMax(int delta) {
    setMax(maxAmount + delta);
  }

  /**
   * 抽取时随机取一个数量
   * <p>
   * 在 {@code [最小数量, 最大数量]} 闭区间内按 {@link WardDistribution}
   * （默认 {@link WardDistribution#UNIFORM} 离散均匀）取值，两端也不例外。
   */
  public int rollAmount(Random random) {
    return JwardChestMain.CONF.amountDistribution()
        .pick(random, minAmount, maxAmount, JwardChestMain.CONF.amountCurve());
  }

  /** 是否参与抽取 */
  public boolean active() {
    return weight > 0;
  }

  // ──────────────────────────────────────────────
  // 持久化：Base64(item) : weight : rarity : min : max
  // ──────────────────────────────────────────────

  /** 序列化为单行字符串 */
  public String serialize() {
    String item = Base64.getEncoder().encodeToString(template.serializeAsBytes());
    return item + ":" + weight + ":" + rarity.id() + ":" + minAmount + ":" + maxAmount;
  }

  /**
   * 从单行字符串还原
   *
   * @param raw 序列化结果
   * @return 条目；无法解析时返回 null
   */
  @Nullable
  public static WardItemEntry deserialize(String raw) {
    if (raw == null || raw.isBlank())
      return null;
    String[] parts = raw.split(":");
    if (parts.length < 5) {
      jlogger.debug("JwardChest 物品条目格式不正确，已忽略：%s", raw);
      return null;
    }
    try {
      ItemStack item = ItemStack.deserializeBytes(Base64.getDecoder().decode(parts[0]));
      int weight = Integer.parseInt(parts[1]);
      WardRarity rarity = WardRarity.byId(parts[2]);
      int min = Integer.parseInt(parts[3]);
      int max = Integer.parseInt(parts[4]);
      return new WardItemEntry(item, weight, rarity, min, max);
    } catch (Exception e) {
      jlogger.debug("JwardChest 无法解析物品条目 [%s]：%s", raw, e.getLocalizedMessage());
      return null;
    }
  }
}
