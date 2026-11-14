package com.github.streackmc.Joyous.JSell;

import java.util.List;
import java.util.function.Consumer;

import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;

import com.github.streackmc.Joyous.utils.i18n;
import com.github.streackmc.Joyous.utils.interact.DetachReasonEnum;
import com.github.streackmc.Joyous.utils.interact.IPageContext;
import com.github.streackmc.Joyous.utils.interact.PageArbiter;
import com.github.streackmc.Joyous.utils.interact.PagePriorityEnum;
import com.github.streackmc.Joyous.utils.interact.chestui.AbstractGuiPage;
import com.github.streackmc.Joyous.utils.interact.chestui.Canvas;
import com.github.streackmc.Joyous.utils.interact.chestui.Click;
import com.github.streackmc.Joyous.utils.interact.chestui.ClickPolicy;
import com.github.streackmc.Joyous.utils.interact.chestui.InteractContainerEnum;
import com.github.streackmc.Joyous.utils.interact.input.ChatPrompt;

import net.kyori.adventure.text.Component;

/**
 * 管理员的「可售物品列表」
 * <p>
 * 一屏 45 条（5 行）+ 底部操作栏：翻页、新增、搜索、跳页、关闭、统计、重载。
 * <p>
 * 打开详情用 {@code INSTANT}：本页被<b>挂起</b>，详情按 ESC 或「返回」时自动回到这里，
 * 所以不需要「关掉后重新打开列表」这类回调。搜索/跳页的聊天输入同理。
 *
 * @author Neonai
 * @since 0.8.0
 */
final class JSellListPage extends AbstractGuiPage {

  static final String KEY = "jsell.editor.list";

  private final JSellEditorGui editor;
  private final SellEntryStore store;
  private final JSellEditorGui.State state;

  JSellListPage(JSellEditorGui editor, SellEntryStore store, JSellEditorGui.State state) {
    this.editor = editor;
    this.store = store;
    this.state = state;
  }

  @Override
  public String key() {
    return KEY;
  }

  @Override
  public String purpose() {
    return "";
  }

  @Override
  public InteractContainerEnum container() {
    return InteractContainerEnum.CHEST;
  }

  @Override
  public int rows() {
    return 6;
  }

  @Override
  public Component title(Player viewer) {
    return Canvas.title(viewer, i18n.tr("jsell.editor.title"));
  }

  /** 内容全部来自存档，随时可重画 */
  @Override
  public boolean keepable() {
    return true;
  }

  /** 上半区独占；背包侧放行整理，Shift+左键会被转发过来登记成新条目 */
  @Override
  public ClickPolicy policy() {
    return ClickPolicy.TRANSFER_ONLY;
  }

  @Override
  public void render(IPageContext ctx, Canvas canvas) {
    Player player = ctx.player();
    if (player == null) {
      return;
    }
    editor.renderList(canvas, player, state);
  }

  @Override
  public Verdict onClick(IPageContext ctx, Click click) {
    Player player = click.player();

    if (!click.top()) {
      // 背包侧 Shift+左键：登记成一条新的可售物品（不消耗原物品）
      if (click.click() == ClickType.SHIFT_LEFT) {
        if (editor.addEntry(player, state, click.event().getCurrentItem())) {
          repaint(ctx);
        }
      }
      return Verdict.HANDLED;
    }

    int slot = click.slot();

    // 拿着物品点「新增」按钮 = 把它登记进来
    if (slot == JSellEditorGui.SLOT_ADD) {
      if (click.holding()) {
        if (editor.addEntry(player, state, click.cursor())) {
          repaint(ctx);
        }
      } else {
        tell(player, "jsell.editor.add_empty");
      }
      return Verdict.HANDLED;
    }

    if (slot >= 0 && slot < JSellEditorGui.PAGE_SIZE) {
      List<SellEntry> list = editor.pageEntries(state);
      int index = state.page * JSellEditorGui.PAGE_SIZE + slot;
      if (index >= 0 && index < list.size()) {
        editor.openDetail(player, list.get(index).id());
      }
      return Verdict.HANDLED;
    }

    switch (slot) {
      case JSellEditorGui.SLOT_PREV -> {
        state.page = Math.max(0, state.page - 1);
        repaint(ctx);
      }
      case JSellEditorGui.SLOT_NEXT -> {
        state.page = state.page + 1;
        repaint(ctx);
      }
      case JSellEditorGui.SLOT_SEARCH -> openChat(ctx, "search",
          ChatPrompt.of(i18n.tr("jsell.input.field.search"), state.filter),
          value -> {
            JSellEditorGui.State current = editor.state(player);
            current.filter =
                value.equalsIgnoreCase("clear") || value.equals("清除") ? "" : value;
            current.page = 0;
            tell(player, "jsell.editor.search_done",
                current.filter.isBlank() ? "-" : current.filter);
          });
      case JSellEditorGui.SLOT_PAGE -> openChat(ctx, "page",
          ChatPrompt.of(i18n.tr("jsell.input.field.page"),
              String.valueOf(state.page + 1)),
          value -> {
            JSellEditorGui.State current = editor.state(player);
            int pages = Math.max(1,
                (editor.pageEntries(current).size() + JSellEditorGui.PAGE_SIZE - 1)
                    / JSellEditorGui.PAGE_SIZE);
            int page = (int) JSellEditorGui.parseLong(value, current.page + 1L);
            current.page = Math.max(0, Math.min(pages - 1, page - 1));
            tell(player, "jsell.editor.goto_done", current.page + 1);
          });
      case JSellEditorGui.SLOT_RELOAD -> {
        store.reload();
        tell(player, "jsell.editor.reloaded", store.size());
        repaint(ctx);
      }
      case JSellEditorGui.SLOT_CLOSE -> ctx.close();
      default -> {
        return Verdict.HANDLED;
      }
    }
    return Verdict.HANDLED;
  }

  @Override
  public void onDetach(IPageContext ctx, DetachReasonEnum why) {
    super.onDetach(ctx, why);
    store.flush();
    // 跳详情 / 去输入都会让本页终止或挂起，所以只在「玩家真的关掉」时提示
    if (why == DetachReasonEnum.CLOSED) {
      tellLater(ctx.player(), "jsell.editor.closed");
    }
  }

  /** 发起一次文本输入：压在列表之上，结束后自动回到列表 */
  private void openChat(IPageContext ctx, String field, ChatPrompt prompt,
      Consumer<String> accept) {
    Player player = ctx.player();
    if (player == null) {
      return;
    }
    PageArbiter.get().open(player,
        new JSellFieldChatPage(key() + "." + field, purpose(), prompt, accept),
        PagePriorityEnum.INSTANT);
  }

  private static void tell(Player player, String key, Object... args) {
    player.sendMessage(i18n.tr(key, args));
  }

  /** 真退出时给一句反馈：等一刻确认玩家没有别的界面在手上 */
  private static void tellLater(Player player, String key) {
    if (player == null) {
      return;
    }
    org.bukkit.Bukkit.getScheduler().runTask(
        com.github.streackmc.Joyous.Joyous.plugin, () -> {
          if (!player.isOnline()) {
            return;
          }
          if (PageArbiter.get().visible(player) != null) {
            return;
          }
          player.sendMessage(i18n.tr(key));
        });
  }
}
