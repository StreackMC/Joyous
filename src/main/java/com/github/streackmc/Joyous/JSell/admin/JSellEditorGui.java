package com.github.streackmc.Joyous.JSell.admin;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nullable;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import com.github.streackmc.Joyous.JSell.JSellMain;
import com.github.streackmc.Joyous.JSell.data.SellEntry;
import com.github.streackmc.Joyous.JSell.data.SellEntryStore;
import com.github.streackmc.Joyous.JSell.data.SellService;
import com.github.streackmc.Joyous.JSell.user.JSellGui;
import com.github.streackmc.Joyous.utils.i18n;
import com.github.streackmc.Joyous.utils.interact.PageArbiter;
import com.github.streackmc.Joyous.utils.interact.PagePriorityEnum;
import com.github.streackmc.Joyous.utils.interact.chestui.Canvas;

import net.kyori.adventure.text.Component;

/**
 * 管理员界面：可售物品列表与条目详情
 * <p>
 * 界面本体（生命周期、点击）在 {@link JSellListPage} 与 {@link JSellDetailPage} 里，
 * 这里负责三件事：
 * <ul>
 *   <li><b>渲染</b>：把一页列表 / 一个条目的详情画到 {@link Canvas} 上；</li>
 *   <li><b>筛选与分页状态</b>：按玩家保存搜索词与页码（跨「关掉再打开」保留）；</li>
 *   <li><b>登记新条目</b>：从管理员手里的物品复制模板（不消耗原件）。</li>
 * </ul>
 *
 * @author kdxiaoyi
 * @since 0.8.0
 */
public final class JSellEditorGui {

  /** 列表每页条目数 */
  public static final int PAGE_SIZE = 45;

  static final int SLOT_PREV = 45;
  static final int SLOT_ADD = 46;
  static final int SLOT_SEARCH = 47;
  static final int SLOT_PAGE = 48;
  static final int SLOT_CLOSE = 49;
  static final int SLOT_INFO = 50;
  static final int SLOT_RELOAD = 51;
  static final int SLOT_NEXT = 53;

  static final int DETAIL_SIZE = 27;
  static final int DETAIL_PREVIEW = 4;
  static final int DETAIL_VALUE = 10;
  static final int DETAIL_LIMIT = 11;
  static final int DETAIL_WINDOW = 12;
  static final int DETAIL_MATCH = 13;
  static final int DETAIL_COMMANDS = 14;
  static final int DETAIL_PER_ITEM = 15;
  static final int DETAIL_REMOVED = 16;
  static final int DETAIL_DELETE = 22;
  static final int DETAIL_BACK = 26;

  private final SellEntryStore store;

  /** 按玩家的浏览状态：搜索词、页码、正在看的条目 */
  private final Map<UUID, State> states = new HashMap<>();

  public JSellEditorGui(SellEntryStore store) {
    this.store = store;
  }

  /** 浏览状态（跨「关掉再打开」保留） */
  public static final class State {
    /** 当前页码（0 起） */
    public int page;
    /** 搜索词（空 = 不过滤） */
    public String filter = "";
    /** 正在查看的条目 */
    @Nullable
    public UUID detail;
  }

  public State state(Player player) {
    return states.computeIfAbsent(player.getUniqueId(), ignored -> new State());
  }

  public void clear(Player player) {
    states.remove(player.getUniqueId());
  }

  // ──────────────────────────────────────────────
  // 打开
  // ──────────────────────────────────────────────

  /** 打开列表 */
  public void open(Player player) {
    PageArbiter.get().open(player, new JSellListPage(this, store, state(player)),
        PagePriorityEnum.REPLACE);
  }

  /** 打开详情；条目已不存在时退回列表 */
  public void openDetail(Player player, UUID entryId) {
    if (store.byId(entryId) == null) {
      player.sendMessage(i18n.tr("jsell.editor.gone"));
      open(player);
      return;
    }
    State state = state(player);
    state.detail = entryId;
    // INSTANT：列表被挂起，详情按 ESC / 返回时自动回到列表
    PageArbiter.get().open(player, new JSellDetailPage(this, store, entryId),
        PagePriorityEnum.INSTANT);
  }

  // ──────────────────────────────────────────────
  // 列表渲染
  // ──────────────────────────────────────────────

  void renderList(Canvas canvas, Player player, State state) {
    List<SellEntry> list = filtered(state);
    int pages = Math.max(1, (list.size() + PAGE_SIZE - 1) / PAGE_SIZE);
    state.page = Math.max(0, Math.min(state.page, pages - 1));

    int start = state.page * PAGE_SIZE;
    for (int i = 0; i < PAGE_SIZE; i++) {
      int index = start + i;
      if (index >= list.size()) {
        canvas.set(i, JSellGui.filler());
        continue;
      }
      canvas.set(i, entryIcon(list.get(index)));
    }

    for (int slot = PAGE_SIZE; slot < canvas.size(); slot++) {
      canvas.set(slot, JSellGui.filler());
    }

    canvas.set(SLOT_PREV, JSellGui.button(Material.ARROW,
        i18n.tr("jsell.editor.prev"),
        List.of(i18n.tr("jsell.editor.page", state.page + 1, pages))));
    canvas.set(SLOT_ADD, JSellGui.button(Material.EMERALD,
        i18n.tr("jsell.editor.add"), List.of(
            i18n.tr("jsell.editor.add_lore1"),
            i18n.tr("jsell.editor.add_lore2"),
            i18n.tr("jsell.editor.add_lore3"))));
    canvas.set(SLOT_SEARCH, JSellGui.button(Material.COMPASS,
        i18n.tr("jsell.editor.search"),
        List.of(state.filter.isBlank()
            ? i18n.tr("jsell.editor.search_none")
            : i18n.tr("jsell.editor.search_current", state.filter),
            i18n.tr("jsell.editor.search_hint"))));
    canvas.set(SLOT_PAGE, JSellGui.button(Material.BOOK,
        i18n.tr("jsell.editor.goto_page"),
        List.of(i18n.tr("jsell.editor.page", state.page + 1, pages),
            i18n.tr("jsell.editor.goto_hint"))));
    canvas.set(SLOT_CLOSE, JSellGui.button(Material.BARRIER,
        i18n.tr("jsell.editor.close"), List.of(i18n.tr("jsell.editor.close_lore"))));
    canvas.set(SLOT_INFO, JSellGui.button(Material.PAPER,
        i18n.tr("jsell.editor.info"), List.of(
            i18n.tr("jsell.editor.info_count", store.size(), list.size()),
            i18n.tr("jsell.editor.info_page", state.page + 1, pages))));
    canvas.set(SLOT_RELOAD, JSellGui.button(Material.HOPPER,
        i18n.tr("jsell.editor.reload"), List.of(i18n.tr("jsell.editor.reload_lore"))));
    canvas.set(SLOT_NEXT, JSellGui.button(Material.ARROW,
        i18n.tr("jsell.editor.next"),
        List.of(i18n.tr("jsell.editor.page", state.page + 1, pages))));
  }

  /** 当前页的条目（页面点击时也要用同一份结果定位） */
  List<SellEntry> pageEntries(State state) {
    return filtered(state);
  }

  /** 列表里的一条：模板物品 + 摘要 Lore */
  private ItemStack entryIcon(SellEntry entry) {
    ItemStack icon = entry.display();
    ItemMeta meta = icon.getItemMeta();
    if (meta == null) {
      return icon;
    }
    String unit = JSellMain.CONF.unitName();
    List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
    lore.add(Component.empty());
    lore.add(JSellGui.legacy(i18n.tr("jsell.editor.entry_value",
        SellService.display(entry.unitValue()), unit)));
    lore.add(JSellGui.legacy(limitLine(entry, unit)));
    lore.add(JSellGui.legacy(i18n.tr("jsell.editor.entry_match",
        entry.match().id(), i18n.tr("jsell.match." + entry.match().id()))));
    lore.add(JSellGui.legacy(i18n.tr("jsell.editor.entry_commands",
        entry.commands().size(), entry.perItemCommands().size())));
    if (entry.removed()) {
      lore.add(JSellGui.legacy(i18n.tr("jsell.editor.entry_removed")));
    }
    lore.add(Component.empty());
    lore.add(JSellGui.legacy(i18n.tr("jsell.editor.entry_hint")));
    meta.lore(lore);
    icon.setItemMeta(meta);
    return icon;
  }

  private static String limitLine(SellEntry entry, String unit) {
    if (entry.limitCount() <= 0) {
      return i18n.tr("jsell.editor.entry_limit_none");
    }
    if (entry.limitSeconds() <= 0) {
      return i18n.tr("jsell.editor.entry_limit_lifetime", entry.limitCount(), unit);
    }
    return i18n.tr("jsell.editor.entry_limit_window", entry.limitCount(), entry.limitSeconds(), unit);
  }

  /** 按搜索词筛选，新登记的排在前面 */
  private List<SellEntry> filtered(State state) {
    List<SellEntry> result = new ArrayList<>();
    String needle = state.filter == null ? "" : state.filter.trim().toLowerCase(Locale.ROOT);
    for (SellEntry entry : store.all()) {
      if (needle.isEmpty() || matchesFilter(entry, needle)) {
        result.add(entry);
      }
    }
    result.sort(Comparator.comparingLong(SellEntry::createdAt).reversed());
    return result;
  }

  private static boolean matchesFilter(SellEntry entry, String needle) {
    String material = entry.template().getType().name().toLowerCase(Locale.ROOT);
    if (material.contains(needle)) {
      return true;
    }
    ItemStack display = entry.display();
    ItemMeta meta = display.getItemMeta();
    if (meta != null && meta.hasDisplayName()) {
      Component name = meta.displayName();
      if (name != null && net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
          .plainText().serialize(name).toLowerCase(Locale.ROOT).contains(needle)) {
        return true;
      }
    }
    return false;
  }

  // ──────────────────────────────────────────────
  // 详情渲染
  // ──────────────────────────────────────────────

  void renderDetail(Canvas canvas, Player player, UUID entryId) {
    SellEntry entry = store.byId(entryId);
    if (entry == null) {
      return;
    }
    for (int slot = 0; slot < DETAIL_SIZE; slot++) {
      canvas.set(slot, JSellGui.filler());
    }

    String unit = JSellMain.CONF.unitName();

    ItemStack preview = entry.display();
    ItemMeta previewMeta = preview.getItemMeta();
    if (previewMeta != null) {
      List<Component> lore = new ArrayList<>();
      lore.add(JSellGui.legacy(i18n.tr("jsell.detail.preview_hint")));
      previewMeta.lore(lore);
      preview.setItemMeta(previewMeta);
    }
    canvas.set(DETAIL_PREVIEW, preview);

    canvas.set(DETAIL_VALUE, JSellGui.button(Material.GOLD_INGOT,
        i18n.tr("jsell.detail.value", SellService.display(entry.unitValue()), unit),
        List.of(i18n.tr("jsell.detail.value_hint"))));

    canvas.set(DETAIL_LIMIT, JSellGui.button(Material.CHEST,
        i18n.tr("jsell.detail.limit", entry.limitCount() <= 0 ? "∞" : entry.limitCount()),
        List.of(
            i18n.tr("jsell.detail.limit_hint"),
            i18n.tr("jsell.detail.limit_zero"))));

    String windowText = entry.limitSeconds() <= 0
        ? i18n.tr("jsell.detail.window_infinite")
        : i18n.tr("jsell.detail.window_value", entry.limitSeconds(),
            SellService.formatDuration(entry.limitSeconds()));
    canvas.set(DETAIL_WINDOW, JSellGui.button(Material.CLOCK,
        i18n.tr("jsell.detail.window", windowText),
        List.of(
            i18n.tr("jsell.detail.window_hint"),
            i18n.tr("jsell.detail.window_zero"))));

    canvas.set(DETAIL_MATCH, JSellGui.button(Material.COMPARATOR,
        i18n.tr("jsell.detail.match", entry.match().id()),
        List.of(
            i18n.tr("jsell.match." + entry.match().id()),
            i18n.tr("jsell.detail.match_hint"))));

    canvas.set(DETAIL_COMMANDS, JSellGui.button(Material.COMMAND_BLOCK,
        i18n.tr("jsell.detail.commands", entry.commands().size()),
        List.of(
            i18n.tr("jsell.detail.commands_hint"),
            i18n.tr("jsell.detail.commands_vars"),
            i18n.tr("jsell.detail.click"))));

    canvas.set(DETAIL_PER_ITEM, JSellGui.button(Material.REPEATER,
        i18n.tr("jsell.detail.per_item", entry.perItemCommands().size()),
        List.of(
            i18n.tr("jsell.detail.per_item_vars"),
            i18n.tr("jsell.detail.per_item_warn"),
            i18n.tr("jsell.detail.click"))));

    canvas.set(DETAIL_REMOVED, JSellGui.button(
        entry.removed() ? Material.LIME_DYE : Material.GRAY_DYE,
        i18n.tr(entry.removed() ? "jsell.detail.restore" : "jsell.detail.remove"),
        List.of(i18n.tr("jsell.detail.remove_hint"))));

    canvas.set(DETAIL_DELETE, JSellGui.button(Material.LAVA_BUCKET,
        i18n.tr("jsell.detail.delete"), List.of(i18n.tr("jsell.detail.delete_hint"))));

    canvas.set(DETAIL_BACK, JSellGui.button(Material.OAK_DOOR,
        i18n.tr("jsell.detail.back"), List.of(i18n.tr("jsell.detail.back_hint"))));
  }

  // ──────────────────────────────────────────────
  // 新增与保存
  // ──────────────────────────────────────────────

  /**
   * 登记一条新的可售物品
   * <p>
   * 只取模板副本（数量归一为 1），<b>不动</b>管理员手上的物品。
   *
   * @return 是否真的新增了一条
   */
  boolean addEntry(Player player, State state, @Nullable ItemStack source) {
    if (source == null || source.getType().isAir()) {
      return false;
    }
    ItemStack template = source.clone();
    template.setAmount(1);
    SellEntry entry = new SellEntry(UUID.randomUUID(), template, 1.0, System.currentTimeMillis());
    store.save(entry);
    store.flush();
    player.sendMessage(i18n.tr("jsell.editor.added",
        JSellGui.prettyName(template), JSellMain.CONF.unitName()));
    // 新增后回到第一页，保证管理员一眼能看到刚加的那条（列表按登记时间倒序）
    state.page = 0;
    return true;
  }

  /** 保存并落盘 */
  void save(SellEntry entry) {
    store.save(entry);
    store.flush();
  }

  /** 命令输入：{@code |} 分隔；输入 clear/none/- 表示清空 */
  static List<String> splitCommands(@Nullable String raw) {
    List<String> result = new ArrayList<>();
    if (raw == null) {
      return result;
    }
    String trimmed = raw.trim();
    if (trimmed.isEmpty() || trimmed.equalsIgnoreCase("clear") || trimmed.equalsIgnoreCase("none")
        || trimmed.equals("-") || trimmed.equals("无")) {
      return result;
    }
    for (String part : trimmed.split("\\|")) {
      String cmd = part.trim();
      if (!cmd.isEmpty()) {
        result.add(cmd);
      }
    }
    return result;
  }

  static double parseDouble(String raw, double fallback) {
    try {
      return Double.parseDouble(raw.trim().replace(",", ""));
    } catch (Exception e) {
      return fallback;
    }
  }

  static long parseLong(String raw, long fallback) {
    try {
      return Long.parseLong(raw.trim());
    } catch (Exception e) {
      return fallback;
    }
  }
}
