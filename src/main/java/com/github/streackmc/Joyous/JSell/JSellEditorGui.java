package com.github.streackmc.Joyous.JSell;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nullable;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import com.github.streackmc.Joyous.Joyous;
import com.github.streackmc.Joyous.i18n;

import net.kyori.adventure.text.Component;

/**
 * 管理员界面：编辑可售列表
 * <p>
 * 一页 45 条，底栏是操作区（上一页 / 新增 / 搜索 / 跳页 / 关闭 / 刷新 / 下一页）。
 * 三种新增方式都<b>不消耗</b>管理员手上的物品——登记的是模板，物品本体仍然归他：
 * <ol>
 *   <li>把背包里的物品拖进列表区域；</li>
 *   <li>用光标拿起物品后点「新增」按钮（等价于和它换位置）；</li>
 *   <li>Shift+左键点背包里的物品。</li>
 * </ol>
 * 登记时整份物品数据（NBT）都会被复制下来，所以带附魔、Lore、自定义模型、PDC 的物品都能原样匹配。
 * <p>
 * 点某一条进入详情，逐项编辑：单价、窗口内最大数量、窗口时长、匹配模式、卖出后命令、逐件命令、
 * 「从价值列表移除」（移除后不可售但记录保留），以及删除。
 *
 * @author kdxiaoyi
 * @since 0.8.0
 */
public final class JSellEditorGui {

  /** 列表每页条数（前 5 行） */
  public static final int PAGE_SIZE = 45;

  private static final int SLOT_PREV = 45;
  private static final int SLOT_ADD = 46;
  private static final int SLOT_SEARCH = 47;
  private static final int SLOT_PAGE = 48;
  private static final int SLOT_CLOSE = 49;
  private static final int SLOT_INFO = 50;
  private static final int SLOT_RELOAD = 51;
  private static final int SLOT_NEXT = 53;

  // 详情界面
  private static final int DETAIL_SIZE = 27;
  private static final int DETAIL_PREVIEW = 4;
  private static final int DETAIL_VALUE = 10;
  private static final int DETAIL_LIMIT = 11;
  private static final int DETAIL_WINDOW = 12;
  private static final int DETAIL_MATCH = 13;
  private static final int DETAIL_COMMANDS = 14;
  private static final int DETAIL_PER_ITEM = 15;
  private static final int DETAIL_REMOVED = 16;
  private static final int DETAIL_DELETE = 22;
  private static final int DETAIL_BACK = 26;

  private final JSellMain module;
  private final SellEntryStore store;
  private final JSellChatInput input;

  /** 每个管理员的界面状态（页码、搜索词、正在看的条目） */
  private final Map<UUID, State> states = new HashMap<>();

  public JSellEditorGui(JSellMain module, SellEntryStore store, JSellChatInput input) {
    this.module = module;
    this.store = store;
    this.input = input;
  }

  /** 管理员界面状态 */
  public static final class State {
    /** 当前页（0 起） */
    public int page;
    /** 搜索词（空 = 不筛选） */
    public String filter = "";
    /** 详情界面正在编辑的条目 */
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
  // 列表界面
  // ──────────────────────────────────────────────

  public void open(Player player) {
    renderList(player, state(player));
  }

  private void renderList(Player player, State state) {
    List<SellEntry> list = filtered(state);
    int pages = Math.max(1, (list.size() + PAGE_SIZE - 1) / PAGE_SIZE);
    state.page = Math.max(0, Math.min(state.page, pages - 1));

    JSellGui.Holder holder = new JSellGui.Holder(JSellGui.Kind.EDITOR_LIST, state);
    Inventory inv = Bukkit.createInventory(holder, JSellGui.SIZE,
        JSellGui.text(player, i18n.tr("jsell.editor.title")));
    holder.attach(inv);

    int start = state.page * PAGE_SIZE;
    for (int i = 0; i < PAGE_SIZE; i++) {
      int index = start + i;
      if (index >= list.size()) {
        inv.setItem(i, JSellGui.filler());
        continue;
      }
      inv.setItem(i, entryIcon(list.get(index)));
    }

    for (int slot = PAGE_SIZE; slot < JSellGui.SIZE; slot++) {
      inv.setItem(slot, JSellGui.filler());
    }

    inv.setItem(SLOT_PREV, JSellGui.button(Material.ARROW,
        i18n.tr("jsell.editor.prev"), List.of(i18n.tr("jsell.editor.page", state.page + 1, pages))));
    inv.setItem(SLOT_ADD, JSellGui.button(Material.EMERALD,
        i18n.tr("jsell.editor.add"), List.of(
            i18n.tr("jsell.editor.add_lore1"),
            i18n.tr("jsell.editor.add_lore2"),
            i18n.tr("jsell.editor.add_lore3"))));
    inv.setItem(SLOT_SEARCH, JSellGui.button(Material.COMPASS,
        i18n.tr("jsell.editor.search"),
        List.of(state.filter.isBlank()
            ? i18n.tr("jsell.editor.search_none")
            : i18n.tr("jsell.editor.search_current", state.filter),
            i18n.tr("jsell.editor.search_hint"))));
    inv.setItem(SLOT_PAGE, JSellGui.button(Material.BOOK,
        i18n.tr("jsell.editor.goto_page"),
        List.of(i18n.tr("jsell.editor.page", state.page + 1, pages),
            i18n.tr("jsell.editor.goto_hint"))));
    inv.setItem(SLOT_CLOSE, JSellGui.button(Material.BARRIER,
        i18n.tr("jsell.editor.close"), List.of(i18n.tr("jsell.editor.close_lore"))));
    inv.setItem(SLOT_INFO, JSellGui.button(Material.PAPER,
        i18n.tr("jsell.editor.info"), List.of(
            i18n.tr("jsell.editor.info_count", store.size(), list.size()),
            i18n.tr("jsell.editor.info_page", state.page + 1, pages))));
    inv.setItem(SLOT_RELOAD, JSellGui.button(Material.HOPPER,
        i18n.tr("jsell.editor.reload"), List.of(i18n.tr("jsell.editor.reload_lore"))));
    inv.setItem(SLOT_NEXT, JSellGui.button(Material.ARROW,
        i18n.tr("jsell.editor.next"), List.of(i18n.tr("jsell.editor.page", state.page + 1, pages))));

    player.openInventory(inv);
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
      String plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
          .plainText().serialize(meta.displayName()).toLowerCase(Locale.ROOT);
      if (plain.contains(needle)) {
        return true;
      }
    }
    return false;
  }

  // ──────────────────────────────────────────────
  // 详情界面
  // ──────────────────────────────────────────────

  public void openDetail(Player player, UUID entryId) {
    SellEntry entry = store.byId(entryId);
    if (entry == null) {
      player.sendMessage(i18n.tr("jsell.editor.gone"));
      open(player);
      return;
    }
    State state = state(player);
    state.detail = entryId;

    JSellGui.Holder holder = new JSellGui.Holder(JSellGui.Kind.EDITOR_DETAIL, entryId);
    Inventory inv = Bukkit.createInventory(holder, DETAIL_SIZE,
        JSellGui.text(player, i18n.tr("jsell.detail.title")));
    holder.attach(inv);
    for (int slot = 0; slot < DETAIL_SIZE; slot++) {
      inv.setItem(slot, JSellGui.filler());
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
    inv.setItem(DETAIL_PREVIEW, preview);

    inv.setItem(DETAIL_VALUE, JSellGui.button(Material.GOLD_INGOT,
        i18n.tr("jsell.detail.value", SellService.display(entry.unitValue()), unit),
        List.of(i18n.tr("jsell.detail.value_hint"))));

    inv.setItem(DETAIL_LIMIT, JSellGui.button(Material.CHEST,
        i18n.tr("jsell.detail.limit", entry.limitCount() <= 0 ? "∞" : entry.limitCount()),
        List.of(
            i18n.tr("jsell.detail.limit_hint"),
            i18n.tr("jsell.detail.limit_zero"))));

    inv.setItem(DETAIL_WINDOW, JSellGui.button(Material.CLOCK,
        i18n.tr("jsell.detail.window",
            entry.limitSeconds() <= 0 ? i18n.tr("jsell.detail.window_infinite")
                : String.valueOf(entry.limitSeconds())),
        List.of(
            i18n.tr("jsell.detail.window_hint"),
            i18n.tr("jsell.detail.window_zero"))));

    inv.setItem(DETAIL_MATCH, JSellGui.button(Material.COMPARATOR,
        i18n.tr("jsell.detail.match", entry.match().id()),
        List.of(
            i18n.tr("jsell.match." + entry.match().id()),
            i18n.tr("jsell.detail.match_hint"))));

    inv.setItem(DETAIL_COMMANDS, JSellGui.button(Material.COMMAND_BLOCK,
        i18n.tr("jsell.detail.commands", entry.commands().size()),
        List.of(
            i18n.tr("jsell.detail.commands_hint"),
            i18n.tr("jsell.detail.commands_vars"),
            i18n.tr("jsell.detail.click"))));

    inv.setItem(DETAIL_PER_ITEM, JSellGui.button(Material.REPEATER,
        i18n.tr("jsell.detail.per_item", entry.perItemCommands().size()),
        List.of(
            i18n.tr("jsell.detail.per_item_vars"),
            i18n.tr("jsell.detail.per_item_warn"),
            i18n.tr("jsell.detail.click"))));

    inv.setItem(DETAIL_REMOVED, JSellGui.button(
        entry.removed() ? Material.LIME_DYE : Material.GRAY_DYE,
        i18n.tr(entry.removed() ? "jsell.detail.restore" : "jsell.detail.remove"),
        List.of(i18n.tr("jsell.detail.remove_hint"))));

    inv.setItem(DETAIL_DELETE, JSellGui.button(Material.LAVA_BUCKET,
        i18n.tr("jsell.detail.delete"), List.of(i18n.tr("jsell.detail.delete_hint"))));

    inv.setItem(DETAIL_BACK, JSellGui.button(Material.OAK_DOOR,
        i18n.tr("jsell.detail.back"), List.of(i18n.tr("jsell.detail.back_hint"))));

    player.openInventory(inv);
  }

  // ──────────────────────────────────────────────
  // 点击
  // ──────────────────────────────────────────────

  /**
   * 处理管理员界面的点击
   *
   * @return true 表示已被处理
   */
  public boolean handleClick(InventoryClickEvent event, Player player, JSellGui.Holder holder) {
    if (holder.kind() == JSellGui.Kind.EDITOR_DETAIL
        && holder.payload() instanceof UUID entryId) {
      return handleDetailClick(event, player, entryId);
    }
    if (!(holder.payload() instanceof State state)) {
      return false;
    }
    return handleListClick(event, player, state);
  }

  private boolean handleListClick(InventoryClickEvent event, Player player, State state) {
    Inventory top = player.getOpenInventory().getTopInventory();
    Inventory clicked = event.getClickedInventory();
    boolean inTop = clicked != null && clicked.equals(top);

    if (!inTop) {
      // 背包侧：Shift+左键 = 登记成一条新的可售物品（不消耗原物品）
      if (event.getClick() == ClickType.SHIFT_LEFT && event.getSlot() >= 0) {
        event.setCancelled(true);
        addEntry(player, state, event.getCurrentItem());
        return true;
      }
      if (event.getClick() == ClickType.DOUBLE_CLICK) {
        event.setCancelled(true);
      }
      return true;
    }

    event.setCancelled(true);
    int slot = event.getRawSlot();

    // 拿着物品点「新增」按钮 = 把它登记进来（这就是「与新增按钮交换位置」）
    if (slot == SLOT_ADD) {
      ItemStack cursor = event.getCursor();
      if (cursor != null && !cursor.getType().isAir()) {
        addEntry(player, state, cursor);
      } else {
        player.sendMessage(i18n.tr("jsell.editor.add_empty"));
      }
      return true;
    }

    if (slot < PAGE_SIZE) {
      List<SellEntry> list = filtered(state);
      int index = state.page * PAGE_SIZE + slot;
      if (index >= 0 && index < list.size()) {
        openDetail(player, list.get(index).id());
      }
      return true;
    }

    switch (slot) {
      case SLOT_PREV -> {
        state.page = Math.max(0, state.page - 1);
        renderList(player, state);
      }
      case SLOT_NEXT -> {
        state.page = state.page + 1;
        renderList(player, state);
      }
      case SLOT_SEARCH -> input.request(player, JSellChatInput.TYPE_SEARCH, state.filter,
          i18n.tr("jsell.input.field.search"), value -> {
            State current = state(player);
            current.filter = value.equalsIgnoreCase("clear") || value.equals("清除") ? "" : value;
            current.page = 0;
            player.sendMessage(i18n.tr("jsell.editor.search_done",
                current.filter.isBlank() ? "-" : current.filter));
            reopenList(player);
          }, null);
      case SLOT_PAGE -> input.request(player, JSellChatInput.TYPE_PAGE,
          String.valueOf(state.page + 1), i18n.tr("jsell.input.field.page"), value -> {
            int pages = Math.max(1, (filtered(state(player)).size() + PAGE_SIZE - 1) / PAGE_SIZE);
            int page = (int) parseLong(value, state(player).page + 1);
            State current = state(player);
            current.page = Math.max(0, Math.min(pages - 1, page - 1));
            player.sendMessage(i18n.tr("jsell.editor.goto_done", current.page + 1));
            reopenList(player);
          }, null);
      case SLOT_RELOAD -> {
        store.reload();
        player.sendMessage(i18n.tr("jsell.editor.reloaded", store.size()));
        renderList(player, state);
      }
      case SLOT_CLOSE -> player.closeInventory();
      default -> {
      }
    }
    return true;
  }

  private boolean handleDetailClick(InventoryClickEvent event, Player player, UUID entryId) {
    Inventory top = player.getOpenInventory().getTopInventory();
    Inventory clicked = event.getClickedInventory();
    if (clicked != null && !clicked.equals(top)) {
      // 详情界面是纯按钮界面：背包侧也全部拦掉，免得把物品丢进按钮格
      event.setCancelled(true);
      return true;
    }
    event.setCancelled(true);

    SellEntry entry = store.byId(entryId);
    if (entry == null) {
      open(player);
      return true;
    }
    int slot = event.getRawSlot();
    State state = state(player);

    switch (slot) {
      case DETAIL_VALUE -> input.request(player, JSellChatInput.TYPE_VALUE,
          SellService.amountText(entry.unitValue()), i18n.tr("jsell.input.field.value"), value -> {
            double parsed = parseDouble(value, entry.unitValue());
            if (parsed < 0) {
              parsed = 0;
            }
            entry.unitValue(parsed);
            save(entry);
            player.sendMessage(i18n.tr("jsell.detail.value_set",
                SellService.display(parsed), JSellMain.CONF.unitName()));
          }, reopenDetail(player, entryId));
      case DETAIL_LIMIT -> input.request(player, JSellChatInput.TYPE_LIMIT,
          String.valueOf(entry.limitCount()), i18n.tr("jsell.input.field.limit"), value -> {
            int parsed = (int) parseLong(value, entry.limitCount());
            entry.limitCount(parsed);
            save(entry);
            player.sendMessage(i18n.tr("jsell.detail.limit_set",
                entry.limitCount() <= 0 ? "∞" : String.valueOf(entry.limitCount())));
          }, reopenDetail(player, entryId));
      case DETAIL_WINDOW -> input.request(player, JSellChatInput.TYPE_WINDOW,
          String.valueOf(entry.limitSeconds()), i18n.tr("jsell.input.field.window"), value -> {
            long seconds = parseSeconds(value, entry.limitSeconds());
            entry.limitSeconds(seconds);
            save(entry);
            player.sendMessage(i18n.tr("jsell.detail.window_set",
                seconds <= 0 ? i18n.tr("jsell.detail.window_infinite")
                    : seconds + "s"));
          }, reopenDetail(player, entryId));
      case DETAIL_MATCH -> {
        entry.match(entry.match().next());
        save(entry);
        player.sendMessage(i18n.tr("jsell.detail.match_set", entry.match().id()));
        openDetail(player, entryId);
      }
      case DETAIL_COMMANDS -> input.request(player, JSellChatInput.TYPE_COMMANDS,
          String.join(" | ", entry.commands()), i18n.tr("jsell.input.field.commands"), value -> {
            entry.commands(splitCommands(value));
            save(entry);
            player.sendMessage(i18n.tr("jsell.detail.commands_set", entry.commands().size()));
          }, reopenDetail(player, entryId));
      case DETAIL_PER_ITEM -> input.request(player, JSellChatInput.TYPE_PER_ITEM,
          String.join(" | ", entry.perItemCommands()), i18n.tr("jsell.input.field.per_item"),
          value -> {
            entry.perItemCommands(splitCommands(value));
            save(entry);
            player.sendMessage(i18n.tr("jsell.detail.per_item_set",
                entry.perItemCommands().size()));
          }, reopenDetail(player, entryId));
      case DETAIL_REMOVED -> {
        entry.removed(!entry.removed());
        save(entry);
        player.sendMessage(i18n.tr(entry.removed()
            ? "jsell.detail.removed_set"
            : "jsell.detail.restored_set"));
        openDetail(player, entryId);
      }
      case DETAIL_DELETE -> input.request(player, JSellChatInput.TYPE_CONFIRM_DELETE,
          "no", i18n.tr("jsell.input.field.confirm_delete"), value -> {
            if (value.equalsIgnoreCase("yes") || value.equalsIgnoreCase("y")
                || value.equals("确认") || value.equals("是")) {
              store.delete(entryId);
              store.flush();
              state.detail = null;
              player.sendMessage(i18n.tr("jsell.detail.deleted"));
            } else {
              player.sendMessage(i18n.tr("jsell.detail.delete_cancelled"));
            }
          }, () -> Bukkit.getScheduler().runTask(Joyous.plugin, () -> {
            if (player.isOnline()) {
              open(player);
            }
          }));
      case DETAIL_BACK -> open(player);
      default -> {
      }
    }
    return true;
  }

  // ──────────────────────────────────────────────
  // 新增
  // ──────────────────────────────────────────────

  /**
   * 登记一条新的可售物品
   * <p>
   * 只取模板副本（数量归一为 1），<b>不动</b>管理员手上的物品。
   */
  public void addEntry(Player player, State state, @Nullable ItemStack source) {
    if (source == null || source.getType().isAir()) {
      return;
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
    Bukkit.getScheduler().runTask(Joyous.plugin, () -> {
      if (player.isOnline()) {
        JSellGui.Holder holder = JSellGui.holderOf(player.getOpenInventory().getTopInventory());
        if (holder != null && holder.kind() == JSellGui.Kind.EDITOR_LIST) {
          renderList(player, state);
        }
      }
    });
  }

  /** 拖拽：把拖进来的那件登记成新条目（拖拽本身取消，物品留在管理员身上） */
  public boolean handleDrag(InventoryDragEvent event, Player player, JSellGui.Holder holder) {
    if (holder.kind() != JSellGui.Kind.EDITOR_LIST) {
      return false;
    }
    Inventory top = player.getOpenInventory().getTopInventory();
    for (int rawSlot : event.getRawSlots()) {
      if (rawSlot >= top.getSize()) {
        // 拖到自己背包里：与我们无关
        return false;
      }
    }
    event.setCancelled(true);
    addEntry(player, state(player), event.getOldCursor());
    return true;
  }

  // ──────────────────────────────────────────────
  // 工具
  // ──────────────────────────────────────────────

  private void save(SellEntry entry) {
    store.save(entry);
    store.flush();
  }

  /** 详情界面的「收尾动作」：重新打开同一个条目 */
  private Runnable reopenDetail(Player player, UUID entryId) {
    return () -> Bukkit.getScheduler().runTask(Joyous.plugin, () -> {
      if (player.isOnline()) {
        openDetail(player, entryId);
      }
    });
  }

  private void reopenList(Player player) {
    Bukkit.getScheduler().runTask(Joyous.plugin, () -> {
      if (player.isOnline()) {
        open(player);
      }
    });
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

  private static double parseDouble(String raw, double fallback) {
    try {
      return Double.parseDouble(raw.trim().replace(",", ""));
    } catch (Exception e) {
      return fallback;
    }
  }

  private static long parseLong(String raw, long fallback) {
    try {
      return Long.parseLong(raw.trim());
    } catch (Exception e) {
      return fallback;
    }
  }

  /**
   * 时长解析：纯数字＝秒；也支持 {@code 30s / 10m / 2h / 7d}；
   * {@code 0}、{@code -1}、{@code infinite}/{@code 无限} 都表示「无限时长」
   */
  static long parseSeconds(String raw, long fallback) {
    if (raw == null || raw.isBlank()) {
      return fallback;
    }
    String value = raw.trim().toLowerCase(Locale.ROOT);
    if (value.equals("infinite") || value.equals("inf") || value.equals("无限")
        || value.equals("forever")) {
      return 0L;
    }
    long multiplier = 1L;
    char last = value.charAt(value.length() - 1);
    if (!Character.isDigit(last)) {
      multiplier = switch (last) {
        case 's' -> 1L;
        case 'm' -> 60L;
        case 'h' -> 3600L;
        case 'd' -> 86400L;
        default -> -1L;
      };
      if (multiplier < 0) {
        return fallback;
      }
      value = value.substring(0, value.length() - 1);
    }
    try {
      long base = Long.parseLong(value.trim());
      return base <= 0 ? 0L : base * multiplier;
    } catch (Exception e) {
      return fallback;
    }
  }
}
