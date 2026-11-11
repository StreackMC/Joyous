package com.github.streackmc.Joyous.utils.interact;

import javax.annotation.Nullable;

import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;

import net.kyori.adventure.text.Component;

/**
 * 容器界面类页面
 * <p>
 * 生命周期（何时显示、被谁挤下去、什么时候终止）由 {@link PageArbiter} 决定，
 * 这里只回答四件事：长什么样、每格放什么、某格点了算什么、允许原版搬运到什么程度。
 *
 * @author Neonai
 * @since 0.7.0
 */
public interface GuiInteract extends JoyousPage {

  /** 点击的处理结果 */
  enum Verdict {
    /** 页面认领了这次点击（是否取消由 {@link ClickPolicy} 决定） */
    HANDLED,
    /** 与我无关，交给策略默认处理 */
    IGNORED
  }

  /** 容器外形 */
  InteractContainer container();

  /** 行数（只对箱子有效） */
  default int rows() {
    return 6;
  }

  /** 标题（支持颜色码与 PAPI，用 {@link Canvas#title} 构造） */
  Component title(Player viewer);

  /**
   * 渲染
   * <p>
   * 允许被反复调用（重绘、恢复、广播），实现必须是「从模型现算」的，不要缓存上次画的东西。
   */
  void render(PageContext ctx, Canvas canvas);

  /**
   * 点击
   * <p>
   * 只会收到上半区（{@link Click.Region#TOP}）的点击；背包侧的点击由策略统一处理。
   */
  default Verdict onClick(PageContext ctx, Click click) {
    return Verdict.IGNORED;
  }

  /** 取消策略；默认整页独占 */
  default ClickPolicy policy() {
    return ClickPolicy.DEFAULT;
  }

  /**
   * 当前这份界面
   * <p>
   * 用来判断「关闭事件是不是我自己关的」。由 {@link SimpleGuiPage} 维护；
   * 不用 Inventory 承载内容的页面（例如铁砧）可以一直返回 null。
   */
  @Nullable
  default Inventory inventory() {
    return null;
  }
}
