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

  /** 以默认参数包装一个物品 */
  public static WardItemEntry of(ItemStack template) {
    return new WardItemEntry(template, 1, WardRarity.COMMON, 1, 1);
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
   * 设置数量范围，自动夹取到 {@code 1..maxStackSize} 且保证 min &lt;= max
   *
   * @param min 最小数量
   * @param max 最大数量
   */
  public void setAmounts(int min, int max) {
    int cap = maxStackSize();
    int low = Math.max(1, Math.min(cap, min));
    int high = Math.max(1, Math.min(cap, max));
    if (low > high) {
      low = high;
    }
    this.minAmount = low;
    this.maxAmount = high;
  }

  /** 调整最小数量（相对增减后重新夹取） */
  public void adjustMin(int delta) {
    setAmounts(minAmount + delta, maxAmount);
  }

  /** 调整最大数量（相对增减后重新夹取） */
  public void adjustMax(int delta) {
    setAmounts(minAmount, maxAmount + delta);
  }

  /** 抽取时随机取一个数量（闭区间均匀分布） */
  public int rollAmount(Random random) {
    if (maxAmount <= minAmount)
      return minAmount;
    return minAmount + random.nextInt(maxAmount - minAmount + 1);
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
