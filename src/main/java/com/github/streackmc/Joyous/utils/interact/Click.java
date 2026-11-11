package com.github.streackmc.Joyous.utils.interact;

import javax.annotation.Nullable;

import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

/**
 * 一次点击的归一化描述
 * <p>
 * 页面实现不用再去分辨「点的是上半区还是自己背包」「是不是 Shift」，
 * 直接读这里已经算好的字段即可。
 *
 * @param player 点击者
 * @param slot   原始槽位（配合 {@link #region()} 才有意义）
 * @param click  原版点击类型
 * @param region 点击发生的区域
 * @param cursor 点击时光标上的物品（可能为空气）
 * @param event  原始事件，需要更深细节（如热键编号）时再用
 * @author Neonai
 * @since 0.7.0
 */
public record Click(
    Player player,
    int slot,
    ClickType click,
    Click.Region region,
    @Nullable ItemStack cursor,
    InventoryClickEvent event) {

  /** 点击区域 */
  public enum Region {
    /** 页面自己的上半区 */
    TOP,
    /** 玩家背包 */
    BOTTOM,
    /** 界面之外（丢物品等） */
    OUTSIDE
  }

  public boolean top() {
    return region == Region.TOP;
  }

  public boolean shift() {
    return click.isShiftClick();
  }

  public boolean right() {
    return click.isRightClick();
  }

  /** 光标上是否拿着东西 */
  public boolean holding() {
    return cursor != null && !cursor.getType().isAir();
  }
}
