package com.github.streackmc.Joyous.JSell;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import javax.annotation.Nullable;

import org.bukkit.inventory.ItemStack;

/**
 * 一次「出售界面」的托管状态
 * <p>
 * <b>界面里展示的物品永远不是这里的原件</b>：{@link #items()} 是权威内容（玩家真正交出来的东西），
 * 界面上画的是它的<b>副本</b>再加上「售出价值」之类的说明行。所以
 * 「在界面里改 Lore / 改名字」不可能影响真正被卖掉的东西，反过来也一样。
 * <p>
 * 玩家关掉界面（Esc / E）＝取消，{@link JSellGui} 会把 {@link #items()} 原样还给他；
 * 点「确认出售」时才会进入 {@link SellService} 的结算流程。
 *
 * @author kdxiaoyi
 * @since 0.8.0
 */
public final class SellSession {

  private final UUID player;
  /** 托管内容（权威）：只存非空气物品，顺序即界面从左到右的顺序 */
  private final List<ItemStack> items = new ArrayList<>();

  /** 界面是被本模块关掉的（结算完成 / 等待输入），关闭事件里不该当作「取消」 */
  private volatile boolean closingByPlugin;
  /** 已经结算过，防止重复点击确认 */
  private volatile boolean settled;

  public SellSession(UUID player) {
    this.player = player;
  }

  public UUID player() {
    return player;
  }

  /** 只读视图（不要直接改，改内容请走 {@link #add}/{@link #take}） */
  public List<ItemStack> items() {
    return Collections.unmodifiableList(items);
  }

  /** 深拷贝一份内容（结算、落库用这个，避免持有内部引用） */
  public List<ItemStack> snapshot() {
    List<ItemStack> copy = new ArrayList<>(items.size());
    for (ItemStack item : items) {
      copy.add(item == null ? null : item.clone());
    }
    return copy;
  }

  public boolean isEmpty() {
    return items.isEmpty();
  }

  public int size() {
    return items.size();
  }

  /**
   * 追加物品（堆叠数超过上限时自动拆成多份）
   *
   * @return 实际放进去的数量（总是等于入参数量，拆分不会丢）
   */
  public int add(ItemStack item) {
    if (item == null || item.getType().isAir() || item.getAmount() <= 0) {
      return 0;
    }
    int remaining = item.getAmount();
    int max = Math.max(1, item.getMaxStackSize());
    while (remaining > 0) {
      int amount = Math.min(max, remaining);
      ItemStack part = item.clone();
      part.setAmount(amount);
      items.add(part);
      remaining -= amount;
    }
    return item.getAmount();
  }

  /**
   * 取走某一格
   *
   * @param index 界面上的序号（0 起）
   * @param amount 取走数量；{@code <= 0} 表示整格
   * @return 被取走的物品副本；序号越界或为空返回 null
   */
  @Nullable
  public ItemStack take(int index, int amount) {
    if (index < 0 || index >= items.size()) {
      return null;
    }
    ItemStack current = items.get(index);
    if (current == null || current.getType().isAir()) {
      return null;
    }
    int take = amount <= 0 ? current.getAmount() : Math.min(amount, current.getAmount());
    ItemStack taken = current.clone();
    taken.setAmount(take);
    if (take >= current.getAmount()) {
      items.remove(index);
    } else {
      current.setAmount(current.getAmount() - take);
    }
    return taken;
  }

  /** 清空托管内容（结算后调用；返回内容副本，调用方负责处置） */
  public List<ItemStack> drain() {
    List<ItemStack> copy = snapshot();
    items.clear();
    return copy;
  }

  /**
   * 用给定内容整体替换托管内容（整理排序、退回不可售物品时用）
   * <p>
   * 会把每摞按最大堆叠拆开，保证「一格一摞」的界面模型始终成立。
   */
  public void reset(@Nullable List<ItemStack> replacement) {
    items.clear();
    if (replacement == null) {
      return;
    }
    for (ItemStack item : replacement) {
      add(item);
    }
  }

  public boolean closingByPlugin() {
    return closingByPlugin;
  }

  public void closingByPlugin(boolean value) {
    this.closingByPlugin = value;
  }

  public boolean settled() {
    return settled;
  }

  public void settled(boolean value) {
    this.settled = value;
  }
}
