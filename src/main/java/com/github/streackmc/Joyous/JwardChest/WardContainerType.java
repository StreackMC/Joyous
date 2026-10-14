package com.github.streackmc.Joyous.JwardChest;

import java.util.Locale;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import net.kyori.adventure.text.Component;

/**
 * 自定义容器类型
 * <p>
 * 决定运行时「自定义容器界面」的外观与槽位数量。绝大多数类型尺寸固定，
 * 只有 {@link #CHEST} 支持自定义行数（1-6，见 {@link #variableRows()}）。
 * <p>
 * 木桶 / 末影箱 / 潜影盒本质上都是 27 格、9 宽的「箱子」，因此合并进 {@link #CHEST}
 * （差异用行数表达）；旧的 {@code barrel} / {@code ender_chest} / {@code shulker_box}
 * 写法由 {@link #byId(String)} 兼容映射到箱子。
 *
 * @author kdxiaoyi
 * @since 0.7.0
 */
public enum WardContainerType {
  CHEST("chest", "箱子", Material.CHEST, InventoryType.CHEST, true),
  DISPENSER("dispenser", "发射器", Material.DISPENSER, InventoryType.DISPENSER, false),
  DROPPER("dropper", "投掷器", Material.DROPPER, InventoryType.DROPPER, false),
  HOPPER("hopper", "漏斗", Material.HOPPER, InventoryType.HOPPER, false),
  FURNACE("furnace", "熔炉", Material.FURNACE, InventoryType.FURNACE, false),
  BLAST_FURNACE("blast_furnace", "高炉", Material.BLAST_FURNACE, InventoryType.BLAST_FURNACE, false),
  SMOKER("smoker", "烟熏炉", Material.SMOKER, InventoryType.SMOKER, false),
  BREWING("brewing", "酿造台", Material.BREWING_STAND, InventoryType.BREWING, false),
  CRAFTER("crafter", "合成器", Material.CRAFTER, InventoryType.CRAFTER, false);

  private final String id;
  private final String displayName;
  private final Material icon;
  private final InventoryType type;
  private final boolean variableRows;

  WardContainerType(String id, String displayName, Material icon, InventoryType type, boolean variableRows) {
    this.id = id;
    this.displayName = displayName;
    this.icon = icon;
    this.type = type;
    this.variableRows = variableRows;
  }

  public String id() {
    return id;
  }

  public String displayName() {
    return displayName;
  }

  /** 子菜单里展示用的方块外观 */
  public Material icon() {
    return icon;
  }

  public InventoryType type() {
    return type;
  }

  /** 是否支持自定义行数（目前只有箱子） */
  public boolean variableRows() {
    return variableRows;
  }

  /**
   * 实际槽位数
   *
   * @param rows 行数，仅对 {@link #variableRows()} 为 true 的类型有效
   * @return 槽位数量
   */
  public int slots(int rows) {
    if (variableRows) {
      return clampRows(rows) * 9;
    }
    return type.getDefaultSize();
  }

  /**
   * 创建容器界面
   *
   * @param holder 持有者（用于识别我们的界面）
   * @param rows   行数，仅对箱子有效
   * @param title  界面标题
   * @return 已创建的 {@link Inventory}
   */
  public Inventory create(InventoryHolder holder, int rows, Component title) {
    if (variableRows) {
      return Bukkit.createInventory(holder, clampRows(rows) * 9, title);
    }
    return Bukkit.createInventory(holder, type, title);
  }

  /** 行数夹取到 1-6（原版箱子上限） */
  public static int clampRows(int rows) {
    return Math.max(1, Math.min(6, rows));
  }

  /**
   * 按 id 解析容器类型（大小写不敏感）
   *
   * @param id 标识，可为 null
   * @return 对应类型；无法识别时返回 {@link #CHEST}
   */
  public static WardContainerType byId(String id) {
    if (id == null || id.isBlank())
      return CHEST;
    String needle = id.trim().toLowerCase(Locale.ROOT);
    // 兼容合并前写下的旧值：它们都是 27 格箱子
    if (needle.equals("barrel") || needle.equals("ender_chest") || needle.equals("shulker_box"))
      return CHEST;
    for (WardContainerType t : values()) {
      if (t.id.equals(needle))
        return t;
    }
    return CHEST;
  }
}
