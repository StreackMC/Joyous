package com.github.streackmc.Joyous.utils.interact.chestui;

import java.util.Locale;

import org.bukkit.Bukkit;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import net.kyori.adventure.text.Component;

/**
 * 可用的容器外形
 * <p>
 * 只描述「客户端画成什么样、有几个槽」，不带任何业务含义。绝大多数类型尺寸固定，
 * 只有 {@link #CHEST} 支持 1-6 行。
 * <p>
 * <b>不要往这里加带客户端侧组件的界面</b>：铁砧（改名框）、织布机、锻造台、制图台、工作台
 * 的自定义 Inventory 都拿不到它们的输入子界面或结果槽由客户端预判，做不出稳定效果。
 *
 * @author Neonai
 * @since 0.7.0
 */
public enum InteractContainerEnum {

  CHEST("chest", InventoryType.CHEST, true),
  DISPENSER("dispenser", InventoryType.DISPENSER, false),
  DROPPER("dropper", InventoryType.DROPPER, false),
  HOPPER("hopper", InventoryType.HOPPER, false),
  FURNACE("furnace", InventoryType.FURNACE, false),
  BLAST_FURNACE("blast_furnace", InventoryType.BLAST_FURNACE, false),
  SMOKER("smoker", InventoryType.SMOKER, false),
  BREWING("brewing", InventoryType.BREWING, false),
  CRAFTER("crafter", InventoryType.CRAFTER, false);

  private final String id;
  private final InventoryType type;
  private final boolean variableRows;

  InteractContainerEnum(String id, InventoryType type, boolean variableRows) {
    this.id = id;
    this.type = type;
    this.variableRows = variableRows;
  }

  public String id() {
    return id;
  }

  public InventoryType type() {
    return type;
  }

  /** 是否支持自定义行数 */
  public boolean variableRows() {
    return variableRows;
  }

  /**
   * 实际槽位数
   *
   * @param rows 行数，仅对 {@link #variableRows()} 为 true 的类型生效
   */
  public int slots(int rows) {
    return variableRows ? clampRows(rows) * 9 : type.getDefaultSize();
  }

  /**
   * 创建界面
   *
   * @param holder 持有者（用于把界面和页面绑起来）
   * @param rows   行数，仅对箱子有效
   * @param title  标题
   */
  public Inventory create(InventoryHolder holder, int rows, Component title) {
    return variableRows
        ? Bukkit.createInventory(holder, clampRows(rows) * 9, title)
        : Bukkit.createInventory(holder, type, title);
  }

  /** 行数夹到原版箱子的 1-6 行 */
  public static int clampRows(int rows) {
    return Math.max(1, Math.min(6, rows));
  }

  /**
   * 按 id 解析（大小写不敏感，无法识别时回落到箱子）
   *
   * @param id 标识，可为 null
   */
  public static InteractContainerEnum byId(String id) {
    if (id == null || id.isBlank()) {
      return CHEST;
    }
    String needle = id.trim().toLowerCase(Locale.ROOT);
    for (InteractContainerEnum c : values()) {
      if (c.id.equals(needle)) {
        return c;
      }
    }
    return CHEST;
  }
}
