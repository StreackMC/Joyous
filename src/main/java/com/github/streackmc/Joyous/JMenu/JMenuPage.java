package com.github.streackmc.Joyous.JMenu;

import org.bukkit.Sound;
import org.bukkit.entity.Player;

import com.github.streackmc.Joyous.utils.interact.Canvas;
import com.github.streackmc.Joyous.utils.interact.Click;
import com.github.streackmc.Joyous.utils.interact.InteractContainer;
import com.github.streackmc.Joyous.utils.interact.PageContext;
import com.github.streackmc.Joyous.utils.interact.SimpleGuiPage;

import net.kyori.adventure.text.Component;

/**
 * JMenu 的 Java 版页面
 * <p>
 * 只描述「这个菜单长什么样、某格点了算什么」，排队与生命周期交给
 * {@code PageArbiter}；点击的取消策略由基类默认的 {@code STRICT} 决定
 * （菜单整页独占，连背包点击也拦，与原实现一致）。
 * <p>
 * 菜单内容全部来自 JSON 缓存，可以随时从模型重画，因此声明为可保留：
 * 系统提示用 {@code INSTANT} 插进来时，菜单会被挂起而不是被关掉。
 *
 * @author Neonai
 * @since 0.7.0
 */
public final class JMenuPage extends SimpleGuiPage {

  /** 页面 key：同一玩家的所有 JMenu 共用，靠 purpose 区分具体菜单 */
  public static final String KEY = "jmenu";

  private final JMenuEntry menu;
  private final JMenuManager manager;

  public JMenuPage(JMenuEntry menu, JMenuManager manager) {
    this.menu = menu;
    this.manager = manager;
  }

  @Override
  public String key() {
    return KEY;
  }

  @Override
  public String purpose() {
    return menu.getMenuPath();
  }

  @Override
  public boolean keepable() {
    return true;
  }

  @Override
  public InteractContainer container() {
    return InteractContainer.CHEST;
  }

  @Override
  public int rows() {
    return InteractContainer.clampRows(menu.getLines());
  }

  @Override
  public Component title(Player viewer) {
    return Canvas.title(viewer, menu.getTitle());
  }

  @Override
  public void render(PageContext ctx, Canvas canvas) {
    Player player = ctx.player();
    if (player == null) {
      return;
    }
    canvas.clear();
    for (var button : menu.getJavaButtons()) {
      int slot = button.slot();
      if (slot < 0 || slot >= canvas.size()) {
        continue;
      }
      // 权限不满足时画的是 else 回退按钮，与点击时执行的保持一致
      var effective = button.effectiveFor(player);
      if (effective != null) {
        canvas.set(slot, effective.buildItem(player));
      }
    }
  }

  @Override
  public Verdict onClick(PageContext ctx, Click click) {
    Player player = click.player();
    for (var button : menu.getJavaButtons()) {
      if (button.slot() != click.slot()) {
        continue;
      }
      var effective = button.effectiveFor(player);
      if (effective == null || effective.isNoop()) {
        // 没权限也没回退：这一格本来就是空的；动作未知：点了不该有任何反应
        return Verdict.HANDLED;
      }
      player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.0f);
      // 先收掉菜单再执行动作，避免动作里开的界面被这一下点击干扰
      ctx.close();
      manager.executeAction(player, effective.action(), effective.param());
      return Verdict.HANDLED;
    }
    return Verdict.HANDLED;
  }
}
