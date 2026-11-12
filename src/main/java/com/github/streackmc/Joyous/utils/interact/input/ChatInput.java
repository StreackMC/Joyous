package com.github.streackmc.Joyous.utils.interact.input;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import com.github.streackmc.Joyous.Joyous;
import com.github.streackmc.Joyous.utils.i18n;
import com.github.streackmc.Joyous.utils.interact.IPageContext;
import com.github.streackmc.Joyous.utils.interact.IPageObserver;
import com.github.streackmc.Joyous.utils.interact.PageArbiter;
import com.github.streackmc.Joyous.utils.interact.PageStateEnum;
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
 * 与页面分工明确：页面只回答「问什么、旧值是什么、拿到文本后干什么」，
 * <b>所有面向玩家的文本都由这里统一组装并发送</b>——提示、可点击的「粘贴旧值」、
 * 取消提示、超时提示全部集中在本类，消费端不再调用 {@code sendMessage}，
 * 也就不可能各写一套措辞或漏掉某条提示。
 *
 * <h2>它怎么知道该发提示了</h2>
 * 靠 {@link IPageObserver}：仲裁器在页面真正显示出来（首次显示或从挂起恢复）时通知本类，
 * 本类据此发提示并起超时计时；页面被挂起或终止时收到隐藏通知，顺手把计时器撤掉。
 * 页面自己因此不需要任何计时器或文案逻辑。
 *
 * <h2>为什么不需要「先登记再关界面」</h2>
 * 旧实现在关界面之前必须先登记 pending，否则关闭事件会把「玩家退出编辑器」当成真的退出。
 * 现在聊天输入本身就是一个页面：仲裁器先挂起编辑界面（{@code INSTANT}）、再显示输入页，
 * 关闭事件看到的状态是 {@code SUSPENDED}，天然不会误判。
 *
 * @author Neonai
 * @since 0.7.0
 */
public final class ChatInput implements Listener, IPageObserver {

  /** 输入这个词表示取消 */
  public static final String CANCEL_WORD = "cancel";

  private final PageArbiter arbiter;

  /** 玩家 → 超时任务的 taskId */
  private final Map<UUID, Integer> timeouts = new ConcurrentHashMap<>();

  public ChatInput(PageArbiter arbiter) {
    this.arbiter = arbiter;
  }

  // ──────────────────────────────────────────────
  // 页面显示 → 发提示并计时
  // ──────────────────────────────────────────────

  @Override
  public void onPageShown(IPageContext ctx) {
    if (!(ctx.page() instanceof IChatInteract page)) {
      return;
    }
    Player player = ctx.player();
    if (player == null) {
      return;
    }
    player.sendMessage(format(page.prompt(player)));
    scheduleTimeout(ctx, page);
  }

  @Override
  public void onPageHidden(IPageContext ctx) {
    if (!(ctx.page() instanceof IChatInteract)) {
      return;
    }
    Player player = ctx.player();
    if (player != null) {
      cancelTimeout(player.getUniqueId());
    }
  }

  // ──────────────────────────────────────────────
  // 聊天事件 → 派发给页面
  // ──────────────────────────────────────────────

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

  // ──────────────────────────────────────────────
  // 超时
  // ──────────────────────────────────────────────

  /**
   * 起一个「没人理我」的计时器
   * <p>
   * 到点后按取消处理：给一句提示，然后关掉输入页（仲裁器会把被挂起的界面恢复回来）。
   */
  private void scheduleTimeout(IPageContext ctx, IChatInteract page) {
    Player player = ctx.player();
    if (player == null) {
      return;
    }
    cancelTimeout(player.getUniqueId());
    int seconds = Math.max(5, page.timeoutSeconds());
    int taskId = Bukkit.getScheduler().runTaskLater(Joyous.plugin, () -> {
      timeouts.remove(player.getUniqueId());
      if (!player.isOnline() || ctx.state() != PageStateEnum.VISIBLE) {
        return;
      }
      player.sendMessage(i18n.tr("ui.input.expired"));
      page.onCancel(ctx);
      ctx.close();
    }, seconds * 20L).getTaskId();
    timeouts.put(player.getUniqueId(), taskId);
  }

  private void cancelTimeout(UUID player) {
    Integer taskId = timeouts.remove(player);
    if (taskId != null && taskId != -1) {
      Bukkit.getScheduler().cancelTask(taskId);
    }
  }

  // ──────────────────────────────────────────────
  // 文案组装（唯一出口）
  // ──────────────────────────────────────────────

  /**
   * 组装提示：字段名 + 可点击的「粘贴旧值」+ 取消提示 + 额外提示行
   * <p>
   * 消费端只提供 {@link ChatPrompt}（问什么、旧值是什么、额外要提示什么），
   * 排版与可点击行为由这里决定，全项目只有这一处。
   */
  private static Component format(ChatPrompt prompt) {
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
