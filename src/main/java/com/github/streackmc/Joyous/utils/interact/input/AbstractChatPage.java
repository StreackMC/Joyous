package com.github.streackmc.Joyous.utils.interact.input;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import com.github.streackmc.Joyous.Joyous;
import com.github.streackmc.Joyous.utils.i18n;
import com.github.streackmc.Joyous.utils.interact.DetachReasonEnum;
import com.github.streackmc.Joyous.utils.interact.IPageContext;

/**
 * 聊天栏输入页面的通用基类
 * <p>
 * 负责三件琐事，子类只需要提供 {@link #key()}、{@link #purpose()}、{@link #prompt(Player)}
 * 与 {@link #onInput(IPageContext, String)}：
 * <ol>
 *   <li>显示时把提示发出去（含可点击的「粘贴旧值」）；</li>
 *   <li>若干秒没有输入就自动收掉，并提示超时；</li>
 *   <li>收尾时取消定时器。</li>
 * </ol>
 * <b>不需要手动「把界面开回来」</b>：聊天输入用 {@code INSTANT} 压在编辑界面之上，
 * 关掉它以后仲裁器会自动恢复被挂起的编辑界面——原先各模块用 {@code onFinish} 回调干这件事，
 * 现在是仲裁器的基本能力。
 *
 * @author Neonai
 * @since 0.7.0
 */
public abstract class AbstractChatPage implements IChatInteract {

  private int timeoutTaskId = -1;

  @Override
  public final void onAttach(IPageContext ctx) {
    Player player = ctx.player();
    if (player == null) {
      return;
    }
    player.sendMessage(ChatInput.prompt(player, prompt(player)));
    scheduleTimeout(ctx);
  }

  @Override
  public final void onDetach(IPageContext ctx, DetachReasonEnum why) {
    cancelTimeout();
  }

  /** 不可保留：打到一半被挤下去，玩家刚打的字就没了，不如让他重新来 */
  @Override
  public boolean keepable() {
    return false;
  }

  private void scheduleTimeout(IPageContext ctx) {
    int seconds = Math.max(5, timeoutSeconds());
    timeoutTaskId = Bukkit.getScheduler().runTaskLater(Joyous.plugin, () -> {
      timeoutTaskId = -1;
      Player player = ctx.player();
      if (player != null && player.isOnline()) {
        player.sendMessage(i18n.tr("ui.input.expired"));
      }
      if (ctx.active()) {
        onCancel(ctx);
        ctx.close();
      }
    }, seconds * 20L).getTaskId();
  }

  private void cancelTimeout() {
    if (timeoutTaskId != -1) {
      Bukkit.getScheduler().cancelTask(timeoutTaskId);
      timeoutTaskId = -1;
    }
  }
}
