package com.github.streackmc.Joyous.utils.interact;

import javax.annotation.Nullable;

import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;

/**
 * 容器界面的通用基类
 * <p>
 * 只做三件事：开界面、调用 {@link #render} 画内容、在合适的时候关掉它。
 * 页面只需要提供外形、标题、渲染与点击语义。
 *
 * @author Neonai
 * @since 0.7.0
 */
public abstract class SimpleGuiPage implements GuiInteract {

  @Nullable
  private Inventory inventory;

  @Override
  @Nullable
  public Inventory inventory() {
    return inventory;
  }

  @Override
  public void onAttach(PageContext ctx) {
    openView(ctx);
  }

  @Override
  public void onResume(PageContext ctx) {
    openView(ctx);
  }

  @Override
  public void onSuspend(PageContext ctx) {
    closeView();
  }

  @Override
  public void onDetach(PageContext ctx, DetachReason why) {
    closeView();
  }

  /**
   * 原地重绘（内容变化时用）
   * <p>
   * 不会重开界面，也不会触发任何生命周期钩子。
   */
  protected final void repaint(PageContext ctx) {
    Inventory view = inventory;
    if (view != null) {
      render(ctx, new Canvas(view));
    }
  }

  private void openView(PageContext ctx) {
    Player player = ctx.player();
    if (player == null) {
      return;
    }
    InteractHolder holder = new InteractHolder(this);
    Inventory view = container().create(holder, rows(), title(player));
    holder.attach(view);
    inventory = view;
    render(ctx, new Canvas(view));
    player.openInventory(view);
  }

  private void closeView() {
    Inventory view = inventory;
    if (view == null) {
      return;
    }
    for (HumanEntity viewer : view.getViewers().toArray(new HumanEntity[0])) {
      viewer.closeInventory();
    }
  }
}
