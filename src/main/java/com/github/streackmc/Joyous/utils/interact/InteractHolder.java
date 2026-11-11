package com.github.streackmc.Joyous.utils.interact;

import javax.annotation.Nullable;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/**
 * 把界面和页面绑起来的唯一 Holder 实现
 * <p>
 * 取代各模块自造的 Holder 与「按标题字符串认界面」的写法。
 * 路由拿不到 Holder 时（例如铁砧那种由服务端创建的界面）会退化成
 * 「当前可见页 + 容器类型匹配」，见 {@link GuiRouter}。
 *
 * @author Neonai
 * @since 0.7.0
 */
public final class InteractHolder implements InventoryHolder {

  private final GuiInteract page;
  private Inventory inventory;

  public InteractHolder(GuiInteract page) {
    this.page = page;
  }

  public GuiInteract page() {
    return page;
  }

  /** 建好界面后回填，供 {@link #getInventory()} 返回 */
  public void attach(Inventory inventory) {
    this.inventory = inventory;
  }

  @Override
  public Inventory getInventory() {
    return inventory;
  }

  /**
   * 取某个界面上的 Holder
   *
   * @param inventory 界面，可为 null
   * @return 不是我们的界面时返回 null
   */
  @Nullable
  public static InteractHolder of(@Nullable Inventory inventory) {
    if (inventory == null) {
      return null;
    }
    return inventory.getHolder() instanceof InteractHolder holder ? holder : null;
  }
}
