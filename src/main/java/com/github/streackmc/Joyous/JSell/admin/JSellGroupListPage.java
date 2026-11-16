package com.github.streackmc.Joyous.JSell.admin;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

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

import net.kyori.adventure.text.Component;

/**
 * 「全部组」列表：玩家和管理员共用的分组选择器
 * <p>
 * 从价目表 / 可售列表的分组按钮上 <b>Shift+左键</b> 打开。选一个组就回到原来的界面并按它筛选——
 * 返回靠 {@code ctx.close()} 恢复被挂起的父页，所以<b>不需要</b>把父页重新 new 一遍。
 *
 * <h3>两种模式</h3>
 * <ul>
 *   <li><b>玩家</b>：只列出「看得到」的组（{@code joyous.jsell.group.<组>.viewable}），左键筛选；</li>
 *   <li><b>管理员</b>：列出全部组，左键筛选，<b>右键进入分组设置</b>（展示名 / unit / economy 覆写）。</li>
 * </ul>
 * 权限判断留在调用方：本页只负责「列出来、点下去」，不猜你是谁。
 *
 * @author Neonai
 * @since 0.4.1
 */
public final class JSellGroupListPage extends AbstractGuiPage {

  static final String KEY = "jsell.groups";

  private static final int PAGE_SIZE = 45;
  private static final int SLOT_PREV = 45;
  private static final int SLOT_INFO = 49;
  private static final int SLOT_NEXT = 53;

  private final SellEntryStore store;
  /** 是否显示「右键编辑」这套管理员操作 */
  private final boolean admin;
  /** 选中某个组后做什么（{@code all} 也算一个值） */
  private final Consumer<String> onPick;
  private int page;

  public JSellGroupListPage(SellEntryStore store, boolean admin, Consumer<String> onPick) {
    this.store = store;
    this.admin = admin;
    this.onPick = onPick;
  }

  @Override
  public String key() {
    return KEY;
  }

  @Override
  public String purpose() {
    return admin ? "admin" : "player";
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
    return Canvas.title(viewer, i18n.tr("jsell.group.title"));
  }

  @Override
  public boolean keepable() {
    return true;
  }

  /** 纯选择界面：不允许往按钮格里搬东西 */
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
    List<String> groups = options(player);
    int pages = Math.max(1, (groups.size() + PAGE_SIZE - 1) / PAGE_SIZE);
    page = Math.max(0, Math.min(page, pages - 1));

    for (int slot = 0; slot < canvas.size(); slot++) {
      canvas.set(slot, JSellGui.filler());
    }

    int start = page * PAGE_SIZE;
    for (int i = 0; i < PAGE_SIZE && start + i < groups.size(); i++) {
      canvas.set(i, icon(player, groups.get(start + i)));
    }

    canvas.set(SLOT_PREV, JSellGui.button(Material.ARROW,
        i18n.tr("jsell.group.prev"), List.of(pageLine(pages))));
    canvas.set(SLOT_NEXT, JSellGui.button(Material.ARROW,
        i18n.tr("jsell.group.next"), List.of(pageLine(pages))));
    canvas.set(SLOT_INFO, JSellGui.button(Material.PAPER,
        i18n.tr("jsell.group.info"),
        admin
            ? List.of(i18n.tr("jsell.group.info_count", groups.size() - 1),
                i18n.tr("jsell.group.pick"), i18n.tr("jsell.group.edit"))
            : List.of(i18n.tr("jsell.group.info_count", groups.size() - 1),
                i18n.tr("jsell.group.pick"))));
  }

  @Override
  public Verdict onClick(IPageContext ctx, Click click) {
    if (!click.top()) {
      return Verdict.HANDLED;
    }
    Player player = click.player();
    List<String> groups = options(player);

    if (click.slot() < PAGE_SIZE) {
      int index = page * PAGE_SIZE + click.slot();
      if (index < 0 || index >= groups.size()) {
        return Verdict.HANDLED;
      }
      String group = groups.get(index);
      if (admin && click.right() && !group.equals(SellGroups.ALL)) {
        // 右键 = 进这一组的设置；本页被挂起，返回时 onResume 会重画（展示名可能已经变了）
        PageArbiter.get().open(player, new JSellGroupSettingsPage(store, group),
            PagePriorityEnum.INSTANT);
        return Verdict.HANDLED;
      }
      onPick.accept(group);
      ctx.close();
      return Verdict.HANDLED;
    }

    switch (click.slot()) {
      case SLOT_PREV -> {
        page = Math.max(0, page - 1);
        repaint(ctx);
      }
      case SLOT_NEXT -> {
        page = page + 1;
        repaint(ctx);
      }
      default -> {
        return Verdict.HANDLED;
      }
    }
    return Verdict.HANDLED;
  }

  // ──────────────────────────────────────────────
  // 数据
  // ──────────────────────────────────────────────

  /** 可选的组：{@code all} 在最前；玩家只能看到有权限的组 */
  private List<String> options(Player player) {
    List<String> result = new ArrayList<>();
    result.add(SellGroups.ALL);
    for (String group : store.groups()) {
      if (admin || SellGroups.canView(player, group)) {
        result.add(group);
      }
    }
    return result;
  }

  private ItemStack icon(Player player, String group) {
    boolean all = group.equals(SellGroups.ALL);
    String label = all ? i18n.tr("jsell.group.all") : store.label(group);
    ItemStack icon = new ItemStack(all ? Material.BOOKSHELF : Material.CHEST);
    ItemMeta meta = icon.getItemMeta();
    if (meta == null) {
      return icon;
    }
    meta.displayName(JSellGui.legacy("&f" + label));

    List<Component> lore = new ArrayList<>();
    if (all) {
      lore.add(JSellGui.legacy(i18n.tr("jsell.group.entry_all")));
    } else {
      lore.add(JSellGui.legacy(i18n.tr("jsell.group.entry_count", countOf(group))));
      if (admin) {
        // unit / economy 属于管理信息；玩家只需要「这一组有几条、点了会筛选」
        SellGroupSettings settings = store.settingsOf(group);
        if (settings.display() != null) {
          lore.add(JSellGui.legacy(i18n.tr("jsell.group.entry_display", settings.display())));
        }
        lore.add(JSellGui.legacy(i18n.tr("jsell.group.entry_unit", unitLine(group))));
        lore.add(JSellGui.legacy(i18n.tr("jsell.group.entry_economy", economyLine(group))));
        lore.add(Component.empty());
        lore.add(JSellGui.legacy(i18n.tr("jsell.group.pick")));
        lore.add(JSellGui.legacy(i18n.tr("jsell.group.edit")));
      } else {
        lore.add(Component.empty());
        lore.add(JSellGui.legacy(i18n.tr("jsell.group.pick")));
      }
    }
    meta.lore(lore);
    icon.setItemMeta(meta);
    return icon;
  }

  private int countOf(String group) {
    int count = 0;
    for (var entry : store.all()) {
      if (entry.group().equals(group)) {
        count++;
      }
    }
    return count;
  }

  private String unitLine(String group) {
    SellGroupSettings settings = store.settingsOf(group);
    if (settings.unitName() != null) {
      return settings.unitName();
    }
    return i18n.tr("jsell.group.unit_inherit", JSellMain.CONF.unitName());
  }

  private String economyLine(String group) {
    SellGroupSettings settings = store.settingsOf(group);
    if (settings.economyCommands() != null) {
      return i18n.tr("jsell.group.economy_own", settings.economyCommands().size());
    }
    return i18n.tr("jsell.group.economy_inherit", JSellMain.CONF.economyCommands().size());
  }

  private String pageLine(int pages) {
    return i18n.tr("jsell.group.page", page + 1, pages);
  }
}
