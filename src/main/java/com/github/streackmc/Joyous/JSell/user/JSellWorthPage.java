package com.github.streackmc.Joyous.JSell.user;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import com.github.streackmc.Joyous.JSell.JSellMain;
import com.github.streackmc.Joyous.JSell.admin.JSellGroupListPage;
import com.github.streackmc.Joyous.JSell.data.SellEntry;
import com.github.streackmc.Joyous.JSell.data.SellEntryStore;
import com.github.streackmc.Joyous.JSell.data.SellGroups;
import com.github.streackmc.Joyous.JSell.data.SellService;
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
 * 玩家总览：这个服务器都收些什么、分别值多少
 * <p>
 * 只读。玩家能看到哪些组由 {@code joyous.jsell.group.<组名>.viewable} 决定，
 * 所以同一个界面在不同人眼里列出来的东西可以不同——这也是它必须做成页面而不是一份静态清单的原因。
 * <p>
 * 分组筛选是可循环的（左键下一个、右键上一个），筛选值存在页面实例里：
 * 关掉再打开会回到「全部组」，这符合「只想顺手看一眼」的使用场景。
 *
 * @author Neonai
 * @since 0.4.0
 */
public final class JSellWorthPage extends AbstractGuiPage {

  static final String KEY = "jsell.worth";

  /** 每页条目数（前 5 行） */
  private static final int PAGE_SIZE = 45;
  private static final int SLOT_PREV = 45;
  private static final int SLOT_GROUP = 47;
  private static final int SLOT_CLOSE = 49;
  private static final int SLOT_INFO = 50;
  private static final int SLOT_NEXT = 53;

  private final SellEntryStore store;
  private int page;
  private String group;

  JSellWorthPage(SellEntryStore store, String group) {
    this.store = store;
    this.group = group == null ? SellGroups.ALL : group;
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
    return Canvas.title(viewer, i18n.tr("jsell.worth.title"));
  }

  /** 内容全部来自存档，随时可重画 */
  @Override
  public boolean keepable() {
    return true;
  }

  /** 纯只读界面：整页独占，玩家不该能往按钮格里丢东西 */
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
    List<SellEntry> list = visible(player);
    int pages = Math.max(1, (list.size() + PAGE_SIZE - 1) / PAGE_SIZE);
    page = Math.max(0, Math.min(page, pages - 1));

    for (int slot = 0; slot < canvas.size(); slot++) {
      canvas.set(slot, JSellGui.filler());
    }

    int start = page * PAGE_SIZE;
    for (int i = 0; i < PAGE_SIZE && start + i < list.size(); i++) {
      canvas.set(i, icon(player, list.get(start + i)));
    }

    canvas.set(SLOT_PREV, JSellGui.button(Material.ARROW,
        i18n.tr("jsell.worth.prev"), List.of(pageLine(pages))));
    canvas.set(SLOT_NEXT, JSellGui.button(Material.ARROW,
        i18n.tr("jsell.worth.next"), List.of(pageLine(pages))));
    canvas.set(SLOT_GROUP, JSellGui.button(Material.MAP,
        i18n.tr("jsell.worth.group", groupLabel()),
        List.of(
            i18n.tr("jsell.worth.group_current", groupLabel()),
            i18n.tr("jsell.worth.group_hint"),
            i18n.tr("jsell.worth.group_next"))));
    canvas.set(SLOT_INFO, JSellGui.button(Material.PAPER,
        i18n.tr("jsell.worth.info"),
        List.of(
            i18n.tr("jsell.worth.info_count", list.size(), store.size()),
            i18n.tr("jsell.worth.info_group", groupLabel()),
            pageLine(pages))));
    canvas.set(SLOT_CLOSE, JSellGui.button(Material.BARRIER,
        i18n.tr("jsell.worth.close"), List.of(i18n.tr("jsell.worth.close_lore"))));
  }

  @Override
  public Verdict onClick(IPageContext ctx, Click click) {
    if (!click.top()) {
      return Verdict.IGNORED;
    }
    Player player = click.player();
    switch (click.slot()) {
      case SLOT_PREV -> {
        page = Math.max(0, page - 1);
        repaint(ctx);
      }
      case SLOT_NEXT -> {
        page = page + 1;
        repaint(ctx);
      }
      case SLOT_GROUP -> {
        // Shift+左键：列出全部组（只列有查看权限的），选完自动回来并按它筛选
        if (click.click() == ClickType.SHIFT_LEFT) {
          PageArbiter.get().open(player,
              new JSellGroupListPage(store, false, chosen -> {
                group = chosen;
                page = 0;
              }),
              PagePriorityEnum.INSTANT);
          return Verdict.HANDLED;
        }
        List<String> options = viewableGroups(player);
        int index = options.indexOf(group);
        if (index < 0) {
          index = 0;
        }
        int step = click.click() == ClickType.RIGHT ? -1 : 1;
        group = options.get(Math.floorMod(index + step, options.size()));
        page = 0;
        repaint(ctx);
      }
      case SLOT_CLOSE -> ctx.close();
      default -> {
        return Verdict.HANDLED;
      }
    }
    return Verdict.HANDLED;
  }

  // ──────────────────────────────────────────────
  // 数据
  // ──────────────────────────────────────────────

  /** 当前筛选下、该玩家有权查看的条目（新登记的排前面） */
  private List<SellEntry> visible(Player player) {
    List<SellEntry> result = new ArrayList<>();
    boolean allGroups = group.equals(SellGroups.ALL);
    for (SellEntry entry : store.all()) {
      if (entry.removed()) {
        continue;
      }
      if (!SellGroups.canView(player, entry.group())) {
        continue;
      }
      if (!allGroups && !entry.group().equals(group)) {
        continue;
      }
      result.add(entry);
    }
    result.sort(Comparator.comparingLong(SellEntry::createdAt).reversed());
    return result;
  }

  /** 可循环的分组选项：{@code all} + 该玩家能看的组 */
  private List<String> viewableGroups(Player player) {
    List<String> options = new ArrayList<>();
    options.add(SellGroups.ALL);
    boolean anyViewable = false;
    for (String name : store.groups()) {
      if (SellGroups.canView(player, name)) {
        options.add(name);
        anyViewable = true;
      }
    }
    if (!anyViewable) {
      return List.of(SellGroups.ALL);
    }
    return options;
  }

  private String groupLabel() {
    return group.equals(SellGroups.ALL) ? i18n.tr("jsell.worth.group_all") : store.label(group);
  }

  private String pageLine(int pages) {
    return i18n.tr("jsell.worth.page", page + 1, pages);
  }

  /** 一条可售物品：模板 + 单价 + 分组 + 限额 + 耐久说明 */
  private ItemStack icon(Player player, SellEntry entry) {
    ItemStack icon = entry.display();
    ItemMeta meta = icon.getItemMeta();
    if (meta == null) {
      return icon;
    }
    String unit = JSellMain.CONF.unitName(entry.group());
    List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
    lore.add(Component.empty());
    lore.add(JSellGui.legacy(i18n.tr("jsell.worth.entry_value",
        SellService.display(entry.unitValue()), unit)));
    lore.add(JSellGui.legacy(i18n.tr("jsell.worth.entry_group", store.label(entry.group()))));
    lore.add(JSellGui.legacy(limitLine(entry, unit)));
    if (entry.hasDurability() && entry.durabilityLoss()) {
      lore.add(JSellGui.legacy(i18n.tr("jsell.worth.entry_durability")));
    }
    if (!SellGroups.canSell(player, entry.group())) {
      lore.add(JSellGui.legacy(i18n.tr("jsell.worth.entry_no_sell")));
    }
    meta.lore(lore);
    icon.setItemMeta(meta);
    return icon;
  }

  private static String limitLine(SellEntry entry, String unit) {
    if (entry.limitCount() <= 0) {
      return i18n.tr("jsell.worth.entry_limit_none");
    }
    if (entry.limitSeconds() <= 0) {
      return i18n.tr("jsell.worth.entry_limit_lifetime", entry.limitCount(), unit);
    }
    return i18n.tr("jsell.worth.entry_limit_window", entry.limitCount(),
        SellService.formatDuration(entry.limitSeconds()), unit);
  }
}
