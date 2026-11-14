package com.github.streackmc.Joyous.JSell.admin;

import java.util.UUID;
import java.util.function.Consumer;

import org.bukkit.entity.Player;

import com.github.streackmc.Joyous.JSell.JSellMain;
import com.github.streackmc.Joyous.JSell.data.SellEntry;
import com.github.streackmc.Joyous.JSell.data.SellEntryStore;
import com.github.streackmc.Joyous.JSell.data.SellService;
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
 * 管理员的「条目详情」
 * <p>
 * 纯按钮界面：单价、限额、窗口、匹配方式、命令、逐件命令、上架状态、删除、返回。
 * 改值走聊天输入（以 {@code INSTANT} 压在本页之上，提交后自动回来）；
 * 按钮类改动就地重画。
 * <p>
 * 本页总是从列表以 {@code INSTANT} 进入，所以「返回」与按 ESC 都只是关掉自己——
 * 仲裁器会把列表恢复回来，不需要重新打开列表。
 *
 * @author Neonai
 * @since 0.8.0
 */
final class JSellDetailPage extends AbstractGuiPage {

  static final String KEY = "jsell.editor.detail";

  private final JSellEditorGui editor;
  private final SellEntryStore store;
  private final UUID entryId;

  JSellDetailPage(JSellEditorGui editor, SellEntryStore store, UUID entryId) {
    this.editor = editor;
    this.store = store;
    this.entryId = entryId;
  }

  @Override
  public String key() {
    return KEY;
  }

  @Override
  public String purpose() {
    return entryId.toString();
  }

  @Override
  public InteractContainerEnum container() {
    return InteractContainerEnum.CHEST;
  }

  @Override
  public int rows() {
    return 3;
  }

  @Override
  public Component title(Player viewer) {
    return Canvas.title(viewer, i18n.tr("jsell.detail.title"));
  }

  @Override
  public boolean keepable() {
    return true;
  }

  /** 纯按钮界面：整页独占，连背包点击也拦（免得把物品丢进按钮格） */
  @Override
  public ClickPolicy policy() {
    return ClickPolicy.STRICT;
  }

  @Override
  public void render(IPageContext ctx, Canvas canvas) {
    Player player = ctx.player();
    if (player == null) {
      return;
    }
    editor.renderDetail(canvas, player, entryId);
  }

  /**
   * 从输入页回来时，条目可能已经被删掉
   * <p>
   * 此时不要再把详情画出来，直接关掉自己让列表恢复。
   */
  @Override
  public void onResume(IPageContext ctx) {
    if (store.byId(entryId) == null) {
      ctx.close();
      return;
    }
    super.onResume(ctx);
  }

  @Override
  public Verdict onClick(IPageContext ctx, Click click) {
    if (!click.top()) {
      return Verdict.IGNORED;
    }
    Player player = click.player();
    SellEntry entry = store.byId(entryId);
    if (entry == null) {
      ctx.close();
      return Verdict.HANDLED;
    }

    switch (click.slot()) {
      case JSellEditorGui.DETAIL_VALUE -> openChat(ctx, "value",
          ChatPrompt.of(i18n.tr("jsell.input.field.value"),
              SellService.amountText(entry.unitValue())),
          value -> {
            double parsed = Math.max(0, JSellEditorGui.parseDouble(value, entry.unitValue()));
            entry.unitValue(parsed);
            editor.save(entry);
            tell(player, "jsell.detail.value_set",
                SellService.display(parsed), JSellMain.CONF.unitName());
          });
      case JSellEditorGui.DETAIL_LIMIT -> openChat(ctx, "limit",
          ChatPrompt.of(i18n.tr("jsell.input.field.limit"),
              String.valueOf(entry.limitCount())),
          value -> {
            int parsed = (int) JSellEditorGui.parseLong(value, entry.limitCount());
            entry.limitCount(parsed);
            editor.save(entry);
            tell(player, "jsell.detail.limit_set",
                entry.limitCount() <= 0 ? "∞" : String.valueOf(entry.limitCount()));
          });
      case JSellEditorGui.DETAIL_WINDOW -> openChat(ctx, "window",
          ChatPrompt.of(i18n.tr("jsell.input.field.window"),
              String.valueOf(entry.limitSeconds()))
              .hint(i18n.tr("jsell.input.window_hint")),
          value -> {
            long seconds = parseSeconds(value, entry.limitSeconds());
            entry.limitSeconds(seconds);
            editor.save(entry);
            tell(player, "jsell.detail.window_set",
                seconds <= 0
                    ? i18n.tr("jsell.detail.window_infinite")
                    : seconds + "s");
          });
      case JSellEditorGui.DETAIL_MATCH -> {
        entry.match(entry.match().next());
        editor.save(entry);
        tell(player, "jsell.detail.match_set", entry.match().id());
        repaint(ctx);
      }
      case JSellEditorGui.DETAIL_COMMANDS -> openChat(ctx, "commands",
          ChatPrompt.of(i18n.tr("jsell.input.field.commands"),
              String.join(" | ", entry.commands()))
              .hint(i18n.tr("jsell.input.multi_hint")),
          value -> {
            entry.commands(JSellEditorGui.splitCommands(value));
            editor.save(entry);
            tell(player, "jsell.detail.commands_set", entry.commands().size());
          });
      case JSellEditorGui.DETAIL_PER_ITEM -> openChat(ctx, "per_item",
          ChatPrompt.of(i18n.tr("jsell.input.field.per_item"),
              String.join(" | ", entry.perItemCommands()))
              .hint(i18n.tr("jsell.input.multi_hint")),
          value -> {
            entry.perItemCommands(JSellEditorGui.splitCommands(value));
            editor.save(entry);
            tell(player, "jsell.detail.per_item_set", entry.perItemCommands().size());
          });
      case JSellEditorGui.DETAIL_REMOVED -> {
        entry.removed(!entry.removed());
        editor.save(entry);
        tell(player, entry.removed() ? "jsell.detail.removed_set" : "jsell.detail.restored_set");
        repaint(ctx);
      }
      case JSellEditorGui.DETAIL_DELETE -> openChat(ctx, "confirm_delete",
          ChatPrompt.of(i18n.tr("jsell.input.field.confirm_delete"), "no")
              .hint(i18n.tr("jsell.input.delete_hint")),
          value -> {
            if (isYes(value)) {
              store.delete(entryId);
              store.flush();
              editor.state(player).detail = null;
              tell(player, "jsell.detail.deleted");
            } else {
              tell(player, "jsell.detail.delete_cancelled");
            }
          });
      case JSellEditorGui.DETAIL_BACK -> ctx.close();
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
  }

  /** 发起一次文本输入：压在本页之上，结束后自动回来 */
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

  private static boolean isYes(String value) {
    return value.equalsIgnoreCase("yes") || value.equalsIgnoreCase("y")
        || value.equals("确认") || value.equals("是");
  }

  /** 秒数：支持纯数字，也支持 {@code 1d2h3m} 这种写法（沿用命令侧的习惯） */
  private static long parseSeconds(String raw, long fallback) {
    if (raw == null) {
      return fallback;
    }
    String value = raw.trim().toLowerCase(java.util.Locale.ROOT);
    if (value.isEmpty()) {
      return fallback;
    }
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException ignored) {
      // 落到下面的复合解析
    }
    long total = 0;
    long number = 0;
    boolean any = false;
    for (char c : value.toCharArray()) {
      if (Character.isDigit(c)) {
        number = number * 10 + (c - '0');
        any = true;
        continue;
      }
      long factor = switch (c) {
        case 's' -> 1L;
        case 'm' -> 60L;
        case 'h' -> 3600L;
        case 'd' -> 86400L;
        default -> -1L;
      };
      if (factor < 0) {
        return fallback;
      }
      total += number * factor;
      number = 0;
    }
    return any ? total + number : fallback;
  }

  private static void tell(Player player, String key, Object... args) {
    player.sendMessage(i18n.tr(key, args));
  }
}
