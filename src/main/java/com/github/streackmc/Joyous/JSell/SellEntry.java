package com.github.streackmc.Joyous.JSell;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import javax.annotation.Nullable;

import org.bukkit.inventory.ItemStack;

import com.github.streackmc.Joyous.utils.jlogger;

/**
 * 一条「可售物品」定义
 * <p>
 * 由管理员在编辑界面里登记：物品模板（含完整 NBT）＋ 单价 ＋ 限额（数量 / 时长）＋ 匹配模式 ＋
 * 卖出后要执行的命令。玩家的物品命中某条定义时，按它的单价计价，并受它的限额约束。
 * <p>
 * 「从价值列表移除」不是真的删掉这条记录，而是把 {@link #removed()} 打开：
 * 条目留在列表里（显示成灰色、带说明），但不再参与匹配，玩家也就卖不掉它。
 * 这样管理员随时能恢复，也不会因为误删丢掉已经调好的单价与命令。
 *
 * @author kdxiaoyi
 * @since 0.8.0
 */
public final class SellEntry {

  /** 存档里代表「不限」的数量 */
  public static final int UNLIMITED_COUNT = 0;
  /** 存档里代表「无限时长」的秒数 */
  public static final long UNLIMITED_WINDOW = 0L;

  private final UUID id;
  private final ItemStack template;
  private final long createdAt;

  private double unitValue;
  private int limitCount = UNLIMITED_COUNT;
  private long limitSeconds = UNLIMITED_WINDOW;
  private SellMatch match = SellMatch.STRICT;
  /** 卖出该物品后执行（支持 %player% / %count% / %amount%） */
  private List<String> commands = new ArrayList<>();
  /** 逐件依次执行（支持同样的占位符，%count% 为该件的数量） */
  private List<String> perItemCommands = new ArrayList<>();
  private boolean removed;

  public SellEntry(UUID id, ItemStack template, double unitValue, long createdAt) {
    this.id = id;
    this.template = normalizeTemplate(template);
    this.unitValue = Math.max(0.0, unitValue);
    this.createdAt = createdAt;
  }

  /**
   * 模板统一成「1 个」：数量不参与匹配也不参与计价（数量由实例决定），
   * 留着原始堆叠数只会在界面上误导管理员。
   */
  private static ItemStack normalizeTemplate(ItemStack raw) {
    ItemStack item = raw.clone();
    item.setAmount(1);
    return item;
  }

  // ──────────────────────────────────────────────
  // 基础属性
  // ──────────────────────────────────────────────

  public UUID id() {
    return id;
  }

  /** 模板的副本（外部随意改，不会影响定义） */
  public ItemStack template() {
    return template.clone();
  }

  public long createdAt() {
    return createdAt;
  }

  /** 单件价值 */
  public double unitValue() {
    return unitValue;
  }

  public void unitValue(double value) {
    this.unitValue = Math.max(0.0, value);
  }

  /** 窗口内最大卖出数量；{@code <= 0} 表示不限 */
  public int limitCount() {
    return limitCount;
  }

  public void limitCount(int count) {
    this.limitCount = Math.max(0, count);
  }

  /** 限额窗口（秒）；{@code <= 0} 表示无限时长（即终身累计） */
  public long limitSeconds() {
    return limitSeconds;
  }

  public void limitSeconds(long seconds) {
    this.limitSeconds = seconds;
  }

  public SellMatch match() {
    return match;
  }

  public void match(SellMatch mode) {
    this.match = mode == null ? SellMatch.STRICT : mode;
  }

  public List<String> commands() {
    return commands;
  }

  public void commands(@Nullable List<String> value) {
    this.commands = clean(value);
  }

  public List<String> perItemCommands() {
    return perItemCommands;
  }

  public void perItemCommands(@Nullable List<String> value) {
    this.perItemCommands = clean(value);
  }

  /** 是否已「从价值列表移除」（移除后不可售） */
  public boolean removed() {
    return removed;
  }

  public void removed(boolean value) {
    this.removed = value;
  }

  private static List<String> clean(@Nullable List<String> value) {
    List<String> result = new ArrayList<>();
    if (value != null) {
      for (String line : value) {
        if (line != null && !line.isBlank()) {
          result.add(line.trim());
        }
      }
    }
    return result;
  }

  // ──────────────────────────────────────────────
  // 匹配与展示
  // ──────────────────────────────────────────────

  /** 该实例是否命中本条目（不看 {@link #removed()}，调用方自行判断） */
  public boolean matches(@Nullable ItemStack item) {
    return match.matches(item, template);
  }

  /** 界面展示用的模板物品（副本，可随意加 Lore） */
  public ItemStack display() {
    return template.clone();
  }

  // ──────────────────────────────────────────────
  // 编解码（供 {@link SellEntryStore} 使用）
  // ──────────────────────────────────────────────

  /** Base64 → ItemStack；空串或损坏返回 null */
  @Nullable
  static ItemStack decodeItem(@Nullable String encoded) {
    if (encoded == null || encoded.isBlank()) {
      return null;
    }
    try {
      return ItemStack.deserializeBytes(Base64.getDecoder().decode(encoded));
    } catch (Exception e) {
      jlogger.warn("JSell 无法解析存档里的物品模板：%s", e.getLocalizedMessage());
      return null;
    }
  }

  /** ItemStack → Base64（存档、WAL 通用） */
  static String encodeItem(ItemStack item) {
    return Base64.getEncoder().encodeToString(item.serializeAsBytes());
  }

  @Override
  public String toString() {
    return "SellEntry(" + id + ", " + template.getType() + ", value=" + unitValue + ")";
  }
}
