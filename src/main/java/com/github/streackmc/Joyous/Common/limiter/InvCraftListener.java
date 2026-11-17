package com.github.streackmc.Joyous.Common.limiter;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.inventory.InventoryView;

import com.github.streackmc.Joyous.Common.CommonMain;
import com.github.streackmc.Joyous.utils.i18n;

/**
 * 玩家背包 2x2 合成方格的权限限制。
 *
 * <p>判定采用「显式拒绝」语义：仅当权限节点 {@code joyous.common.invcraft}
 * 被显式设为 false（权限被设置过、但判定为无权限）时才禁用合成；节点从未被设置时
 * 保持原版行为，所有人照常合成。这样新增限制不会默认影响任何玩家，只有管理员明确
 * 给玩家或用户组加上 {@code -joyous.common.invcraft} 才会生效。
 *
 * <p>只作用于玩家背包自带的合成网格（{@link InventoryType#CRAFTING}），
 * 工作台（{@link InventoryType#WORKBENCH}）与其它容器不受影响。
 *
 * @author Neonai
 * @author kdxiaoyi
 * @since 0.4.1
 */
public final class InvCraftListener implements Listener {

  /** CRAFTING 视图中合成网格的 rawSlot 范围：0 = 结果格，1~4 = 2x2 输入格。 */
  private static final int GRID_SLOT_MIN = 0;
  private static final int GRID_SLOT_MAX = 4;

  private static final String PERMISSION = CommonMain.NAMES.PERMISSION_PREFIX("invcraft");

  /** 只有模块启用且权限被显式拒绝时才返回 true。 */
  private static boolean isDenied(Player player) {
    if (!CommonMain.conf.getBoolean("invcraft-limiter", true)) return false;
    return player.isPermissionSet(PERMISSION) && !player.hasPermission(PERMISSION);
  }

  /** 只认玩家背包自带的合成网格视图，工作台与其它容器一律放行。 */
  private static boolean isPlayerCraftingView(InventoryView view) {
    return view.getType() == InventoryType.CRAFTING;
  }

  /** 阻止在合成网格区域点击：放入/取出材料、拿取结果、Shift 点击等。 */
  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void onInventoryClick(InventoryClickEvent event) {
    if (!isPlayerCraftingView(event.getView())) {
      return;
    }
    int raw = event.getRawSlot();
    if (raw < GRID_SLOT_MIN || raw > GRID_SLOT_MAX) {
      return;
    }
    if (!(event.getWhoClicked() instanceof Player player)) {
      return;
    }
    if (isDenied(player)) {
      player.sendMessage(i18n.tr("common.invcraft_prevented"));
      event.setCancelled(true);
    }
  }

  /** 阻止把物品拖拽进合成网格。 */
  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void onInventoryDrag(InventoryDragEvent event) {
    if (!isPlayerCraftingView(event.getView())) {
      return;
    }
    boolean hitsGrid = event.getRawSlots().stream()
        .anyMatch(slot -> slot >= GRID_SLOT_MIN && slot <= GRID_SLOT_MAX);
    if (!hitsGrid) {
      return;
    }
    if (!(event.getWhoClicked() instanceof Player player)) {
      return;
    }
    if (isDenied(player)) {
      player.sendMessage(i18n.tr("common.invcraft_prevented"));
      event.setCancelled(true);
    }
  }

  /** 兜底：即便材料已被放入，也不让结果格显示可合成的产物。 */
  @EventHandler(priority = EventPriority.HIGH)
  public void onPrepareCraft(PrepareItemCraftEvent event) {
    if (event.getInventory().getType() != InventoryType.CRAFTING) {
      return;
    }
    if (!(event.getView().getPlayer() instanceof Player player)) {
      return;
    }
    if (isDenied(player)) {
      event.getInventory().setResult(null);
    }
  }

  /** 兜底：阻止真正拿走合成产物（合成动作发生的最终时刻）。 */
  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void onCraftItem(CraftItemEvent event) {
    if (event.getInventory().getType() != InventoryType.CRAFTING) {
      return;
    }
    if (!(event.getWhoClicked() instanceof Player player)) {
      return;
    }
    if (isDenied(player)) {
      player.sendMessage(i18n.tr("common.invcraft_prevented"));
      event.setCancelled(true);
    }
  }
}
