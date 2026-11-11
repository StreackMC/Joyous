package com.github.streackmc.Joyous.utils.interact.input;

import javax.annotation.Nullable;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import com.github.streackmc.Joyous.Joyous;
import com.github.streackmc.Joyous.utils.i18n;
import com.github.streackmc.Joyous.utils.interact.IPageContext;
import com.github.streackmc.Joyous.utils.interact.PageArbiter;
import com.github.streackmc.Joyous.utils.interact.Text;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/**
 * 聊天栏输入的 transport
 * <p>
 * 只做三件事：截获聊天事件、判 {@code cancel}、把文本<b>转到主线程</b>再交给页面。
 * 提示文案的组装也在这里（{@link #prompt}），免得每个模块各写一遍「点击粘贴旧值」。
 *
 * <h2>为什么不需要「先登记再关界面」</h2>
 * 旧实现在关界面之前必须先登记 pending，否则关闭事件会把「玩家退出编辑器」当成真的退出。
 * 现在聊天输入本身就是一个页面：仲裁器先挂起编辑界面（{@code INSTANT}）、再显示输入页，
 * 关闭事件看到的状态是 {@code SUSPENDED}，天然不会误判。
 *
 * @author Neonai
 * @since 0.7.0
 */
public final class ChatInput implements Listener {

  /** 输入这个词表示取消 */
  public static final String CANCEL_WORD = "cancel";

  private final PageArbiter arbiter;

  public ChatInput(PageArbiter arbiter) {
    this.arbiter = arbiter;
  }

  @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
  public void onChat(AsyncChatEvent event) {
    Player player = event.getPlayer();
    if (!(visibleChat(player) instanceof IChatInteract)) {
      return;
    }
    String text = PlainTextComponentSerializer.plainText().serialize(event.message());
    // 先截获，别让玩家的输入变成一条普通聊天
    event.setCancelled(true);
    Bukkit.getScheduler().runTask(Joyous.plugin, () -> deliver(player, text));
  }

  /** 在主线程上把文本交给当前可见的输入页 */
  private void deliver(Player player, @Nullable String raw) {
    if (!player.isOnline()) {
      return;
    }
    IPageContext ctx = arbiter.visible(player);
    if (ctx == null || !(ctx.page() instanceof IChatInteract page)) {
      // 期间被关掉/超时了：这条消息丢弃即可（事件已经被取消，不会漏到公屏）
      return;
    }
    String text = raw == null ? "" : raw.trim();
    if (text.equalsIgnoreCase(CANCEL_WORD)) {
      player.sendMessage(i18n.tr("ui.input.cancelled"));
      page.onCancel(ctx);
      ctx.close();
      return;
    }
    page.onInput(ctx, text);
  }

  @Nullable
  private IChatInteract visibleChat(Player player) {
    IPageContext ctx = arbiter.visible(player);
    return ctx != null && ctx.page() instanceof IChatInteract chat ? chat : null;
  }

  /**
   * 组装提示：字段名 + 可点击的「粘贴旧值」+ 取消提示 + 额外提示行
   *
   * @param player 目标玩家（用于解析占位符）
   * @param prompt 提示内容
   */
  public static Component prompt(Player player, ChatPrompt prompt) {
    Component head = Text.legacy(i18n.tr("ui.input.prompt", prompt.label()));

    String oldValue = prompt.oldValue();
    if (oldValue != null && !oldValue.isBlank()) {
      head = head.append(Component.newline())
          .append(Text.legacy(i18n.tr("ui.input.paste_hint"))
              .clickEvent(ClickEvent.suggestCommand(oldValue))
              .hoverEvent(HoverEvent.showText(Component.text(oldValue, NamedTextColor.GRAY)))
              .decoration(TextDecoration.UNDERLINED, true));
    }

    head = head.append(Component.newline()).append(Text.legacy(i18n.tr("ui.input.cancel_hint")));
    for (String hint : prompt.extraHints()) {
      head = head.append(Component.newline()).append(Text.legacy(hint));
    }
    return head;
  }
}
