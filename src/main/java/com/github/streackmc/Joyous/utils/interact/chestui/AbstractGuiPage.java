package com.github.streackmc.Joyous.utils.interact.chestui;

import javax.annotation.Nullable;

import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;

import com.github.streackmc.Joyous.utils.interact.DetachReasonEnum;
import com.github.streackmc.Joyous.utils.interact.IPageContext;
import com.github.streackmc.Joyous.utils.interact.PageStateEnum;

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

  /** 与 {@link #inventory} 配套的 Holder，用来判断「玩家当前开的还是不是我这份界面」 */
  @Nullable
  private InteractHolder holder;

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
   * <p>
   * 只在页面<b>正在显示</b>时真的去画：被挂起时界面已关闭，这时写进去没有意义，
   * 而恢复时 {@link #onResume} 会重开并重画一遍。
   */
  protected final void repaint(IPageContext ctx) {
    if (ctx.state() != PageStateEnum.VISIBLE) {
      return;
    }
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
    InteractHolder fresh = new InteractHolder(this);
    Inventory view = container().create(fresh, rows(), title(player));
    fresh.attach(view);
    holder = fresh;
    inventory = view;
    render(ctx, new Canvas(view));
    player.openInventory(view);
  }

  /**
   * 关掉自己那份界面
   * <p>
   * 必须按 Holder 判断「玩家当前开的还是不是我」：切到另一个界面时，服务端会先对旧界面发关闭事件，
   * 那时若再补一次 {@code closeInventory()}，会把刚要打开的新界面一起吞掉。
   */
  private void closeView() {
    Inventory view = inventory;
    InteractHolder mine = holder;
    if (view == null || mine == null) {
      return;
    }
    for (HumanEntity viewer : view.getViewers().toArray(new HumanEntity[0])) {
      if (InteractHolder.of(viewer.getOpenInventory().getTopInventory()) == mine) {
        viewer.closeInventory();
      }
    }
  }
}
