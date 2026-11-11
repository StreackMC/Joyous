package com.github.streackmc.Joyous.utils.interact.chestui;

import javax.annotation.Nullable;

import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;

import com.github.streackmc.Joyous.utils.interact.DetachReasonEnum;
import com.github.streackmc.Joyous.utils.interact.IPageContext;

/**
 * 容器界面的通用基类
 * <p>
 * 只做三件事：开界面、调用 {@link #render} 画内容、在合适的时候关掉它。
 * 页面只需要提供外形、标题、渲染与点击语义。
 *
 * @author Neonai
 * @since 0.7.0
 */
public abstract class AbstractGuiPage implements IGuiInteract {

  @Nullable
  private Inventory inventory;

  @Override
  @Nullable
  public Inventory inventory() {
    return inventory;
  }

  @Override
  public void onAttach(IPageContext ctx) {
    openView(ctx);
  }

  @Override
  public void onResume(IPageContext ctx) {
    openView(ctx);
  }

  @Override
  public void onSuspend(IPageContext ctx) {
    closeView();
  }

  @Override
  public void onDetach(IPageContext ctx, DetachReasonEnum why) {
    closeView();
  }

  /**
   * 原地重绘（内容变化时用）
   * <p>
   * 不会重开界面，也不会触发任何生命周期钩子。
   */
  protected final void repaint(IPageContext ctx) {
    Inventory view = inventory;
    if (view != null) {
      render(ctx, new Canvas(view));
    }
  }

  private void openView(IPageContext ctx) {
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
