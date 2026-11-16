package com.github.streackmc.Joyous.JSell.admin;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import com.github.streackmc.Joyous.JSell.JSellMain;
import com.github.streackmc.Joyous.JSell.data.SellEntryStore;
import com.github.streackmc.Joyous.JSell.data.SellGroupSettings;
import com.github.streackmc.Joyous.JSell.data.SellGroups;
import com.github.streackmc.Joyous.JSell.user.JSellGui;
import com.github.streackmc.Joyous.utils.i18n;
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
 * 单个分组的设置（管理员）：展示名 / unit 覆写 / economy 覆写
 *
 * <h3>「继承」是默认状态</h3>
 * 每一项都可能是「未设置」，此时它跟着 {@code JSell.*} 的全局配置走。界面上直接写出
 * 「继承全局（当前：金币）」，比让人猜「留空是什么意思」清楚得多；输入 {@code unset} 即恢复继承。
 *
 * <h3>default 组没有 unit / economy</h3>
 * {@code default} 代表「没有分组的东西」，它的值<b>就是</b>全局值。给它做覆写会让同一个值
 * 出现两个来源，所以这里索性不画那两个按钮，只留展示名。
 *
 * @author Neonai
 * @since 0.4.1
 */
final class JSellGroupSettingsPage extends AbstractGuiPage {

  static final String KEY = "jsell.groups.settings";

  private static final int SLOT_DISPLAY = 4;
  private static final int SLOT_UNIT = 11;
  private static final int SLOT_ECONOMY = 13;
  private static final int SLOT_DECIMALS = 15;
  private static final int SLOT_BACK = 22;

  private final SellEntryStore store;
  private final String group;

  JSellGroupSettingsPage(SellEntryStore store, String group) {
    this.store = store;
    this.group = SellGroups.normalize(group);
  }

  @Override
  public String key() {
    return KEY;
  }

  /** 每个组一个独立会话：挂起/恢复靠 (key, purpose) 区分 */
  @Override
  public String purpose() {
    return group;
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
    return Canvas.title(viewer, i18n.tr("jsell.group.settings_title", store.label(group)));
  }

  @Override
  public boolean keepable() {
    return true;
  }

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
    for (int slot = 0; slot < canvas.size(); slot++) {
      canvas.set(slot, JSellGui.filler());
    }
    SellGroupSettings settings = store.settingsOf(group);

    canvas.set(SLOT_DISPLAY, button(Material.NAME_TAG,
        settings.display() == null
            ? i18n.tr("jsell.group.display_none", group)
            : i18n.tr("jsell.group.display", settings.display()),
        i18n.tr("jsell.group.display_hint"),
        i18n.tr("jsell.group.unset_hint")));

    if (settings.canOverride()) {
      canvas.set(SLOT_UNIT, button(Material.GOLD_INGOT,
          settings.unitName() == null
              ? i18n.tr("jsell.group.unit_inherit", JSellMain.CONF.unitName())
              : i18n.tr("jsell.group.unit", settings.unitName()),
          i18n.tr("jsell.group.unit_hint"),
          i18n.tr("jsell.group.unset_hint")));

      canvas.set(SLOT_ECONOMY, button(Material.COMMAND_BLOCK,
          settings.economyCommands() == null
              ? i18n.tr("jsell.group.economy_inherit", JSellMain.CONF.economyCommands().size())
              : i18n.tr("jsell.group.economy", settings.economyCommands().size()),
          i18n.tr("jsell.group.economy_hint"),
          i18n.tr("jsell.group.economy_multi"),
          i18n.tr("jsell.group.unset_hint")));

      canvas.set(SLOT_DECIMALS, button(Material.COMPARATOR,
          settings.decimals() == null
              ? i18n.tr("jsell.group.decimals_inherit", JSellMain.CONF.decimals())
              : i18n.tr("jsell.group.decimals", settings.decimals()),
          i18n.tr("jsell.group.decimals_hint"),
          i18n.tr("jsell.group.unset_hint")));
    } else {
      canvas.set(SLOT_ECONOMY, button(Material.PAPER,
          i18n.tr("jsell.group.default_note"),
          i18n.tr("jsell.group.default_note_lore1"),
          i18n.tr("jsell.group.default_note_lore2")));
    }

    canvas.set(SLOT_BACK, JSellGui.button(Material.OAK_DOOR,
        i18n.tr("jsell.group.back"), List.of(i18n.tr("jsell.group.back_hint"))));
  }

  private static ItemStack button(Material material, String name, String... lore) {
    List<String> lines = new ArrayList<>(lore.length);
    for (String line : lore) {
      lines.add(line);
    }
    return JSellGui.button(material, name, lines);
  }

  @Override
  public Verdict onClick(IPageContext ctx, Click click) {
    if (!click.top()) {
      return Verdict.HANDLED;
    }
    Player player = click.player();
    SellGroupSettings settings = store.settingsOf(group);

    switch (click.slot()) {
      case SLOT_DISPLAY -> ask(player, "display",
          ChatPrompt.of(i18n.tr("jsell.input.field.display"),
              settings.display() == null ? group : settings.display())
              .hint(i18n.tr("jsell.input.display_hint")),
          value -> {
            if (isUnset(value)) {
              settings.display(null);
              tell(player, "jsell.group.display_cleared", group);
            } else {
              settings.display(value);
              tell(player, "jsell.group.display_set", group, value.trim());
            }
            store.saveSettings(settings);
            store.flush();
          });
      case SLOT_UNIT -> {
        if (!settings.canOverride()) {
          return Verdict.HANDLED;
        }
        ask(player, "unit",
            ChatPrompt.of(i18n.tr("jsell.input.field.unit"), settings.unitName())
                .hint(i18n.tr("jsell.input.unit_hint")),
            value -> {
              boolean inherit = isUnset(value);
              settings.unitName(inherit ? null : value);
              store.saveSettings(settings);
              store.flush();
              if (inherit) {
                tell(player, "jsell.group.inherit_done");
              } else {
                tell(player, "jsell.group.unit_set", value.trim());
              }
            });
      }
      case SLOT_ECONOMY -> {
        if (!settings.canOverride()) {
          return Verdict.HANDLED;
        }
        ask(player, "economy",
            ChatPrompt.of(i18n.tr("jsell.input.field.economy"),
                settings.economyCommands() == null
                    ? ""
                    : String.join(" | ", settings.economyCommands()))
                .hint(i18n.tr("jsell.input.economy_hint")),
            value -> {
              if (isUnset(value)) {
                settings.economyCommands(null);
                store.saveSettings(settings);
                store.flush();
                tell(player, "jsell.group.inherit_done");
                return;
              }
              List<String> commands = JSellEditorGui.splitCommands(value);
              if (commands.isEmpty()) {
                // 命令为空等于「恢复继承」：没有命令可执行的覆写是没有意义的
                settings.economyCommands(null);
                store.saveSettings(settings);
                store.flush();
                tell(player, "jsell.group.inherit_done");
                return;
              }
              settings.economyCommands(commands);
              store.saveSettings(settings);
              store.flush();
              tell(player, "jsell.group.economy_set", commands.size());
            });
      }
      case SLOT_DECIMALS -> {
        if (!settings.canOverride()) {
          return Verdict.HANDLED;
        }
        ask(player, "decimals",
            ChatPrompt.of(i18n.tr("jsell.input.field.decimals"),
                settings.decimals() == null ? "" : String.valueOf(settings.decimals()))
                .hint(i18n.tr("jsell.input.decimals_hint")),
            value -> {
              if (isUnset(value)) {
                settings.decimals(null);
                store.saveSettings(settings);
                store.flush();
                tell(player, "jsell.group.inherit_done");
                return;
              }
              Integer parsed = parseInt(value);
              if (parsed == null || parsed < 0 || parsed > 6) {
                tell(player, "jsell.group.bad_decimals");
                return;
              }
              settings.decimals(parsed);
              store.saveSettings(settings);
              store.flush();
              tell(player, "jsell.group.decimals_set", parsed);
            });
      }
      case SLOT_BACK -> ctx.close();
      default -> {
        return Verdict.HANDLED;
      }
    }
    return Verdict.HANDLED;
  }

  // ──────────────────────────────────────────────
  // 工具
  // ──────────────────────────────────────────────

  /** 发起一次输入：压在设置页之上，提交/取消/超时后自动回来并重画 */
  private void ask(Player player, String field, ChatPrompt prompt,
      java.util.function.Consumer<String> accept) {
    PageArbiter.get().open(player,
        new JSellFieldChatPage(key() + "." + field, purpose(), prompt, accept),
        PagePriorityEnum.INSTANT);
  }

  /** {@code unset} / {@code clear} / {@code -} / 空 都表示「恢复继承」 */
  private static boolean isUnset(String value) {
    if (value == null) {
      return true;
    }
    String trimmed = value.trim().toLowerCase(java.util.Locale.ROOT);
    return trimmed.isEmpty() || trimmed.equals("-") || trimmed.equals("unset")
        || trimmed.equals("inherit") || trimmed.equals("clear") || trimmed.equals("none")
        || trimmed.equals("无") || trimmed.equals("继承");
  }

  private static Integer parseInt(String raw) {
    try {
      return Integer.parseInt(raw.trim());
    } catch (Exception e) {
      return null;
    }
  }

  private static void tell(Player player, String key, Object... args) {
    player.sendMessage(i18n.tr(key, args));
  }
}
