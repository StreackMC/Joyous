package com.github.streackmc.Joyous.JMenu;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import com.github.streackmc.Joyous.utils.jlogger;
import com.github.streackmc.StreackLib.utils.MCColor;

/**
 * JMenu 事件监听器
 * <p>
 * 只剩「用菜单物品打开菜单」这件事：箱子界面的点击、拖拽、关闭已经统一由
 * {@code utils.interact.GuiRouter} 路由到 {@link JMenuPage}，玩家退出也由页面仲裁器清理。
 *
 * @author kdxiaoyi
 * @since 0.2.0
 */
public class JMenuListener implements Listener {

  private final JMenuManager manager;

  public JMenuListener(JMenuManager manager) {
    this.manager = manager;
  }

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
      jlogger.warn("玩家 [%s] 通过物品打开菜单 [%s] 失败：%s",
          player.getName(), menuPath, e.getLocalizedMessage());
    }
  }
}
