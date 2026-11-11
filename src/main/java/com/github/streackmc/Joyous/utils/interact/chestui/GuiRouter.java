package com.github.streackmc.Joyous.utils.interact.chestui;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;

import com.github.streackmc.Joyous.utils.interact.IPageContext;
import com.github.streackmc.Joyous.utils.interact.PageArbiter;
import com.github.streackmc.Joyous.utils.interact.PageStateEnum;
import com.github.streackmc.Joyous.utils.jlogger;

/**
 * 容器界面的事件路由
 * <p>
 * 三个模块原先各写一套「点击 / 拖拽 / 关闭」分发，取消策略还不一样。这里只做一件事：
 * 认出院面属于哪个页面，把归一化后的点击交给它，取消与否则由页面的 {@link ClickPolicy} 声明。
 *
 * <h2>怎么认界面</h2>
 * 只认 {@link InteractHolder}。用标题字符串判断界面是错的（玩家可以改标题），
 * 用「当前可见页」猜测也不行（其他插件可能刚打开了自己的界面）。
 * 将来若要做不带 Holder 的界面（例如铁砧），再在这里加一条显式声明式的分支。
 *
 * <h2>关闭事件为什么要判断状态</h2>
 * 我们自己关界面（切页、挂起、终止）同样会触发 {@link InventoryCloseEvent}。
 * 只有「页面仍处于 VISIBLE」时的关闭才算玩家主动退出；其余一律忽略。
 *
 * @author Neonai
 * @since 0.7.0
 */
public final class GuiRouter implements Listener {

  private final PageArbiter arbiter;

  public GuiRouter(PageArbiter arbiter) {
    this.arbiter = arbiter;
  }

  @EventHandler(priority = EventPriority.HIGH)
  public void onClick(InventoryClickEvent event) {
    if (!(event.getWhoClicked() instanceof Player player)) {
      return;
    }
    IGuiInteract page = pageOf(event.getView().getTopInventory());
    if (page == null) {
      return;
    }
    ClickPolicy policy = page.policy();
    Click.Region region = regionOf(event.getClickedInventory());

    switch (region) {
      case TOP -> {
        if (policy.cancelTop()) {
          event.setCancelled(true);
        }
      }
      case BOTTOM -> {
        if (policy.cancelBottom(event.getClick())) {
          event.setCancelled(true);
        }
      }
      case OUTSIDE -> {
        if (policy.cancelTop()) {
          event.setCancelled(true);
        }
        return;
      }
    }
    IPageContext ctx = arbiter.visible(player);
    if (ctx == null || ctx.page() != page) {
      return;
    }
    try {
      page.onClick(ctx, new Click(player, event.getSlot(), event.getClick(), region,
          event.getCursor(), event));
    } catch (Exception e) {
      jlogger.warning("页面 %s 处理点击时出错：%s", page.key(), e.getLocalizedMessage(), e);
    }
  }

  @EventHandler(priority = EventPriority.HIGH)
  public void onDrag(InventoryDragEvent event) {
    if (!(event.getWhoClicked() instanceof Player player)) {
      return;
    }
    IGuiInteract page = pageOf(event.getView().getTopInventory());
    if (page == null || !page.policy().cancelDrag()) {
      return;
    }
    int topSize = event.getView().getTopInventory().getSize();
    for (int raw : event.getRawSlots()) {
      if (raw < topSize) {
        event.setCancelled(true);
        return;
      }
    }
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void onClose(InventoryCloseEvent event) {
    if (!(event.getPlayer() instanceof Player player)) {
      return;
    }
    IGuiInteract page = pageOf(event.getInventory());
    if (page == null) {
      return;
    }
    IPageContext ctx = arbiter.visible(player);
    if (ctx == null || ctx.page() != page) {
      return;
    }
    if (ctx.state() != PageStateEnum.VISIBLE) {
      return; // 是我们自己关的（切页 / 挂起 / 终止），不是玩家退出
    }
    ctx.close();
  }

  private static Click.Region regionOf(Inventory clicked) {
    if (clicked == null) {
      return Click.Region.OUTSIDE;
    }
    return InteractHolder.of(clicked) != null ? Click.Region.TOP : Click.Region.BOTTOM;
  }

  private static IGuiInteract pageOf(Inventory inventory) {
    InteractHolder holder = InteractHolder.of(inventory);
    return holder == null ? null : holder.page();
  }
}
