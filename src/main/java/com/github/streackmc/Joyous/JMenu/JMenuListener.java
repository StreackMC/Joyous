package com.github.streackmc.Joyous.JMenu;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import com.github.streackmc.Joyous.logger;
import com.github.streackmc.StreackLib.utils.MCColor;

/**
 * JMenu 事件监听器
 * <p>
 * 监听以下事件：
 * <ul>
 *   <li>{@link InventoryClickEvent} — Java 版箱子菜单的点击处理</li>
 *   <li>{@link InventoryDragEvent} — 禁止拖拽到菜单内</li>
 *   <li>{@link InventoryCloseEvent} — 清理活跃菜单记录</li>
 *   <li>{@link PlayerInteractEvent} — 使用菜单物品打开菜单</li>
 *   <li>{@link PlayerQuitEvent} — 清理玩家数据</li>
 * </ul>
 *
 * @author kdxiaoyi
 * @since 0.2.0
 */
public class JMenuListener implements Listener {

  private final JMenuManager manager;

  public JMenuListener(JMenuManager manager) {
    this.manager = manager;
  }

  // ──────────────────────────────────────────────
  // 箱子菜单点击
  // ──────────────────────────────────────────────

  /**
   * 处理 Java 版箱子菜单的点击事件
   * <p>
   * 仅处理 JMenuManager 记录的活跃菜单中的点击。
   * 取消所有点击事件，只对有效按钮执行对应动作。
   */
  @EventHandler(priority = EventPriority.HIGH)
  public void onInventoryClick(InventoryClickEvent event) {
    if (!(event.getWhoClicked() instanceof Player player)) return;
    if (event.getClickedInventory() == null) return;

    // 检查是否是我们打开的菜单
    JMenuEntry menu = manager.getActiveMenu(player);
    if (menu == null) return;

    // 取消所有点击（禁止取走物品）
    event.setCancelled(true);

    // 只处理菜单内（上侧）的点击
    if (event.getClickedInventory().equals(player.getOpenInventory().getTopInventory())) {
      int slot = event.getRawSlot();

      // 查找对应的按钮
      for (var btn : menu.getJavaButtons()) {
        int btnSlot = (btn.x() - 1) * 9 + (btn.y() - 1);
        if (slot == btnSlot) {
          // 权限校验
          if (!JMenuManager.checkPermission(player, btn.perm(), btn.permUnhave())) {
            return;
          }

          // 播放点击声音
          player.playSound(player.getLocation(),
              org.bukkit.Sound.UI_BUTTON_CLICK, 0.5f, 1.0f);

          // 关闭菜单后再执行动作（避免 GUI 干扰）
          player.closeInventory();

          // 执行按钮动作
          manager.executeAction(player, btn.action(), btn.param());
          return;
        }
      }
    }
  }

  // ──────────────────────────────────────────────
  // 禁止拖拽
  // ──────────────────────────────────────────────

  /**
   * 禁止向菜单内拖拽物品
   */
  @EventHandler(priority = EventPriority.HIGH)
  public void onInventoryDrag(InventoryDragEvent event) {
    if (!(event.getWhoClicked() instanceof Player player)) return;
    JMenuEntry menu = manager.getActiveMenu(player);
    if (menu == null) return;

    // 检查拖拽的目标是否包含菜单的槽位
    Inventory top = player.getOpenInventory().getTopInventory();
    for (int slot : event.getRawSlots()) {
      if (slot < top.getSize()) {
        event.setCancelled(true);
        return;
      }
    }
  }

  // ──────────────────────────────────────────────
  // 关闭菜单清理
  // ──────────────────────────────────────────────

  /**
   * 关闭菜单时清理活跃记录
   */
  @EventHandler(priority = EventPriority.MONITOR)
  public void onInventoryClose(InventoryCloseEvent event) {
    if (!(event.getPlayer() instanceof Player player)) return;
    manager.removeActiveMenu(player);
  }

  // ──────────────────────────────────────────────
  // 菜单物品交互
  // ──────────────────────────────────────────────

  /**
   * 处理玩家右键使用菜单物品打开菜单
   * <p>
   * 仅当配置中启用了 {@code specialized} 模式时生效。
   * 通过检查物品是否含有插件标记的 PDC 数据来判断。
   */
  @EventHandler(priority = EventPriority.HIGH)
  public void onPlayerInteract(PlayerInteractEvent event) {
    if (event.getHand() != EquipmentSlot.HAND) return;
    if (!event.getAction().name().contains("RIGHT_")) return;

    Player player = event.getPlayer();
    ItemStack item = player.getInventory().getItemInMainHand();
    if (item == null || item.getType().isAir()) return;

    // 检查是否是菜单物品（含 PDC 标记）
    var pdc = item.getItemMeta() != null
        ? item.getItemMeta().getPersistentDataContainer()
        : null;
    if (pdc == null) return;

    String menuPath = pdc.get(JMenuMain.MENU_ITEM_KEY,
        org.bukkit.persistence.PersistentDataType.STRING);
    if (menuPath == null || menuPath.isBlank()) return;

    event.setCancelled(true);

    try {
      manager.openMenuFor(menuPath, player);
    } catch (IllegalArgumentException e) {
      player.sendMessage(MCColor.parse("&c无法打开菜单：" + e.getLocalizedMessage()));
      logger.warn("玩家 [%s] 通过物品打开菜单 [%s] 失败：%s",
          player.getName(), menuPath, e.getLocalizedMessage());
    }
  }

  // ──────────────────────────────────────────────
  // 玩家退出清理
  // ──────────────────────────────────────────────

  /**
   * 玩家退出时清理活跃菜单记录
   */
  @EventHandler()
  public void onPlayerQuit(PlayerQuitEvent event) {
    manager.removeActiveMenu(event.getPlayer());
  }
}