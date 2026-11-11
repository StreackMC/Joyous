package com.github.streackmc.Joyous.utils.interact.chestui;

import org.bukkit.event.inventory.ClickType;

/**
 * 一个页面允许原版搬运语义到什么程度
 * <p>
 * 取消策略与「这一格点了算什么」是两件事：策略由路由统一执行，语义在
 * {@link IGuiInteract#onClick} 里决定。把两者混在一起写，是三个模块原先各自
 * 演化出不同行为的原因。
 *
 * @param cancelTop  上半区的点击一律取消
 * @param bottom     玩家背包侧的取消规则
 * @param cancelDrag 拖拽到上半区一律取消
 * @author Neonai
 * @since 0.7.0
 */
public record ClickPolicy(boolean cancelTop, ClickPolicy.Bottom bottom, boolean cancelDrag) {

  /** 玩家背包侧的取消规则 */
  public enum Bottom {
    /** 完全不拦，玩家可以随意整理背包 */
    ALLOW,
    /** 全拦（页面会把背包也当成自己的一部分来读） */
    CANCEL_ALL,
    /** 只拦那些可能把物品挪进上半区的操作 */
    CANCEL_TRANSFER
  }

  /** 全拦：页面独占整个界面，连背包点击也不放过（JMenu 的现有行为） */
  public static final ClickPolicy STRICT = new ClickPolicy(true, Bottom.CANCEL_ALL, true);

  /** 只拦上半区与「会碰到上半区」的操作，背包整理放行 */
  public static final ClickPolicy TRANSFER_ONLY = new ClickPolicy(true, Bottom.CANCEL_TRANSFER, true);

  /** 默认与 {@link #STRICT} 一致：宁可拦得狠一点，也不要让页面内容被搬走 */
  public static ClickPolicy DEFAULT = STRICT;

  /**
   * 背包侧的这次点击是否要取消
   *
   * @param type 原版点击类型
   */
  public boolean cancelBottom(ClickType type) {
    return switch (bottom) {
      case ALLOW -> false;
      case CANCEL_ALL -> true;
      case CANCEL_TRANSFER -> type.isShiftClick()
          || type.isKeyboardClick()
          || type == ClickType.DOUBLE_CLICK;
    };
  }
}
