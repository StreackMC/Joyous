package com.github.streackmc.Joyous.JwardChest;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nullable;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import com.github.streackmc.Joyous.Joyous;
import com.github.streackmc.Joyous.i18n;

import net.kyori.adventure.text.Component;

/**
 * 编辑界面
 * <p>
 * 主界面固定 7 个按钮（容器类型 / 预设代号 / 待选物品 / 各品质最大抽出 / 标题 / 粗筛时长 / 预览）。
 * 除「待选物品」子菜单带显式保存/取消外，其余改动即时生效并落盘。
 * 所有编辑都通过 {@link WardProfileStore#effective(UUID)} 取生效设置，
 * 因此属于预设组时会自动联动整组。
 *
 * @author kdxiaoyi
 * @since 0.7.0
 */
public final class WardEditorGui {

  /** 待选物品子菜单每页可编辑的条目数（5 行 × 每行 2 块） */
  private static final int PAGE_SIZE = 10;
  /** 待选物品子菜单行数 */
  private static final int ITEM_EDITOR_ROWS = 6;

  private final JwardChestMain module;
  private final WardProfileStore store;
  private final WardSessionManager sessions;
  private final WardSearchController search;

  /** 待选物品子菜单的工作副本：玩家 → 状态 */
  private final Map<UUID, ItemEditorState> itemWork = new HashMap<>();

  public WardEditorGui(JwardChestMain module, WardProfileStore store, WardSessionManager sessions,
      WardSearchController search) {
    this.module = module;
    this.store = store;
    this.sessions = sessions;
    this.search = search;
  }

  /** 待选物品子菜单的工作状态（显式保存/取消） */
  private static final class ItemEditorState {
    final UUID profileId;
    final List<WardItemEntry> entries = new ArrayList<>();
    int page;

    ItemEditorState(UUID profileId, WardProfile profile) {
      this.profileId = profileId;
      for (WardItemEntry e : profile.entries()) {
        entries.add(e);
      }
    }
  }

  // ──────────────────────────────────────────────
  // 主编辑界面
  // ──────────────────────────────────────────────

  /**
   * 打开主编辑界面
   *
   * @param player    编辑者
   * @param profileId 容器 UUID
   * @param block     被绑定的方块（预览需要它的坐标）
   */
  public void openEditor(Player player, UUID profileId, Location block) {
    WardProfile profile = store.effective(profileId);
    WardGui.Holder holder = new WardGui.Holder(WardGui.Kind.EDITOR, profileId, block);
    Inventory inv = Bukkit.createInventory(holder, 27,
        WardGui.title(player, i18n.tr("jwardchest.editor.gui_title")));
    holder.attach(inv);

    inv.setItem(10, button(profile.container().icon(),
        i18n.tr("jwardchest.editor.container_type", profile.container().displayName()),
        List.of(
            i18n.tr("jwardchest.editor.container_type_current", profile.container().id(), profile.slots()),
            i18n.tr("jwardchest.editor.click"))));

    String code = profile.inPreset() ? profile.presetCode() : WardProfile.UNSET_CODE;
    inv.setItem(11, button(Material.NAME_TAG,
        i18n.tr("jwardchest.editor.code_label"),
        List.of(
            i18n.tr("jwardchest.editor.code_current", code),
            i18n.tr("jwardchest.editor.code_hint"))));

    inv.setItem(12, button(Material.CHEST_MINECART,
        i18n.tr("jwardchest.editor.items_label"),
        List.of(
            i18n.tr("jwardchest.editor.items_count", profile.entryCount(), profile.totalWeight()),
            i18n.tr("jwardchest.editor.click"))));

    inv.setItem(13, button(Material.HOPPER,
        i18n.tr("jwardchest.editor.maxdraw"),
        maxDrawLore(profile)));

    inv.setItem(14, button(Material.OAK_SIGN,
        i18n.tr("jwardchest.editor.title_label"),
        List.of(
            i18n.tr("jwardchest.editor.title_current", profile.title()),
            i18n.tr("jwardchest.editor.click"))));

    inv.setItem(15, button(Material.CLOCK,
        i18n.tr("jwardchest.editor.prefilter"),
        List.of(
            i18n.tr("jwardchest.editor.prefilter_current",
                profile.prefilterEnabled() ? profile.prefilterSeconds() + "s" : "0"),
            i18n.tr("jwardchest.editor.click"))));

    inv.setItem(16, button(Material.SPYGLASS,
        i18n.tr("jwardchest.editor.preview"),
        List.of(
            i18n.tr("jwardchest.editor.preview_hint"),
            i18n.tr("jwardchest.editor.preview_hint_instant"))));

    player.openInventory(inv);
  }

  private static List<String> maxDrawLore(WardProfile profile) {
    List<String> lore = new ArrayList<>();
    for (WardRarity r : WardRarity.values()) {
      lore.add(r.color() + r.displayName() + " §7→ §f" + profile.minDraw(r) + " §8~ §f"
          + profile.maxDraw(r));
    }
    lore.add(i18n.tr("jwardchest.maxdraw.total", profile.totalMinDraw(), profile.totalMaxDraw()));
    lore.addAll(overflowHints(profile));
    lore.add(i18n.tr("jwardchest.editor.click"));
    return lore;
  }

  /** 上/下限与容器容量不匹配时的提示（多余会被舍弃 / 未设上限时按容量抽） */
  private static List<String> overflowHints(WardProfile profile) {
    int lower = profile.totalMinDraw();
    int upper = profile.totalMaxDraw();
    int slots = profile.slots();
    if (upper <= 0) {
      return List.of(lower > 0
          ? i18n.tr("jwardchest.maxdraw.auto_floor", lower, slots)
          : i18n.tr("jwardchest.maxdraw.auto", slots));
    }
    if (upper > slots) {
      return List.of(i18n.tr("jwardchest.maxdraw.overflow", upper, slots));
    }
    return List.of();
  }

  // ──────────────────────────────────────────────
  // 容器类型子菜单
  // ──────────────────────────────────────────────

  private void openContainerPicker(Player player, UUID profileId, Location block) {
    WardProfile profile = store.effective(profileId);
    WardGui.Holder holder = new WardGui.Holder(WardGui.Kind.CONTAINER_PICKER, profileId, block);
    Inventory inv = Bukkit.createInventory(holder, 27,
        WardGui.title(player, i18n.tr("jwardchest.picker.title")));
    holder.attach(inv);

    int slot = 0;
    for (WardContainerType type : WardContainerType.values()) {
      if (slot >= inv.getSize()) {
        break;
      }
      boolean current = type == profile.container();
      List<String> lore = new ArrayList<>();
      lore.add(i18n.tr("jwardchest.picker.size", type.slots(profile.rows())));
      lore.add(current ? i18n.tr("jwardchest.picker.current") : i18n.tr("jwardchest.editor.click"));
      inv.setItem(slot++, button(type.icon(), type.displayName(), lore, current));
    }
    player.openInventory(inv);
  }

  /** 箱子行数选择 */
  private void openRowsPicker(Player player, UUID profileId, Location block) {
    WardProfile profile = store.effective(profileId);
    WardGui.Holder holder = new WardGui.Holder(WardGui.Kind.CONTAINER_PICKER, profileId, block);
    Inventory inv = Bukkit.createInventory(holder, 9,
        WardGui.title(player, i18n.tr("jwardchest.rows.title")));
    holder.attach(inv);

    for (int rows = 1; rows <= 6; rows++) {
      ItemStack item = button(Material.RED_STAINED_GLASS_PANE,
          i18n.tr("jwardchest.rows.entry", rows, rows * 9),
          List.of(rows == profile.rows()
              ? i18n.tr("jwardchest.picker.current")
              : i18n.tr("jwardchest.editor.click")),
          rows == profile.rows());
      item.setAmount(rows);
      inv.setItem(rows - 1, item);
    }
    player.openInventory(inv);
  }

  // ──────────────────────────────────────────────
  // 各品质最大抽出数量
  // ──────────────────────────────────────────────

  /**
   * 各品质的抽出数量上/下限
   * <p>
   * 两行：上排「最小」（地毯）＝保底，下排「最大」（羊毛）＝上限。
   * 上限只是上限——设 3 不等于一定抽 3 条，实际条数由权重分摊与总条数决定。
   */
  private void openMaxDraw(Player player, UUID profileId, Location block) {
    WardProfile profile = store.effective(profileId);
    WardGui.Holder holder = new WardGui.Holder(WardGui.Kind.MAX_DRAW, profileId, block);
    Inventory inv = Bukkit.createInventory(holder, 18,
        WardGui.title(player, i18n.tr("jwardchest.maxdraw.title")));
    holder.attach(inv);

    int i = 0;
    for (WardRarity r : WardRarity.values()) {
      inv.setItem(i, drawButton(profile, r, true));
      inv.setItem(9 + i, drawButton(profile, r, false));
      i++;
    }
    player.openInventory(inv);
  }

  /**
   * 上/下限的编辑按钮
   *
   * @param floorRow true = 上排（最小 / 保底，用地毯区分），false = 下排（最大 / 上限，用羊毛）
   */
  private ItemStack drawButton(WardProfile profile, WardRarity r, boolean floorRow) {
    List<String> lore = new ArrayList<>();
    lore.add(i18n.tr("jwardchest.maxdraw.range", profile.minDraw(r), profile.maxDraw(r)));
    lore.add(i18n.tr(floorRow
        ? "jwardchest.maxdraw.row_floor"
        : "jwardchest.maxdraw.row_ceiling"));
    lore.add(i18n.tr("jwardchest.maxdraw.total", profile.totalMinDraw(), profile.totalMaxDraw()));
    lore.addAll(overflowHints(profile));
    lore.add(i18n.tr("jwardchest.maxdraw.hint"));
    return button(floorRow ? carpetOf(r) : woolOf(r),
        i18n.tr(floorRow ? "jwardchest.maxdraw.label_floor" : "jwardchest.maxdraw.label_ceiling",
            r.color() + r.displayName()),
        lore);
  }

  private static Material woolOf(WardRarity r) {
    return switch (r) {
      case COMMON -> Material.WHITE_WOOL;
      case RARE -> Material.GREEN_WOOL;
      case COLLECT -> Material.PURPLE_WOOL;
      case PRECIOUS -> Material.YELLOW_WOOL;
      case MYTHIC -> Material.RED_WOOL;
    };
  }

  /** 地毯版材质，用于和「上限」的羊毛区分开 */
  private static Material carpetOf(WardRarity r) {
    return switch (r) {
      case COMMON -> Material.WHITE_CARPET;
      case RARE -> Material.GREEN_CARPET;
      case COLLECT -> Material.PURPLE_CARPET;
      case PRECIOUS -> Material.YELLOW_CARPET;
      case MYTHIC -> Material.RED_CARPET;
    };
  }

  // ──────────────────────────────────────────────
  // 待选物品子菜单
  // ──────────────────────────────────────────────

  private void openItemEditor(Player player, UUID profileId, Location block) {
    ItemEditorState state = itemWork.get(player.getUniqueId());
    if (state == null || !state.profileId.equals(profileId)) {
      state = new ItemEditorState(profileId, store.effective(profileId));
      state.page = 0;
      itemWork.put(player.getUniqueId(), state);
    }
    renderItemEditor(player, state, block);
  }

  private void renderItemEditor(Player player, ItemEditorState state, Location block) {
    WardGui.Holder holder = new WardGui.Holder(WardGui.Kind.ITEM_EDITOR, state.profileId, state);
    Inventory inv = Bukkit.createInventory(holder, ITEM_EDITOR_ROWS * 9,
        WardGui.title(player, i18n.tr("jwardchest.items.title")));
    holder.attach(inv);
    paintItemEditor(inv, state);
    player.openInventory(inv);
  }

  /**
   * 原地重绘
   * <p>
   * <b>不重开界面</b>：直接往玩家当前开着的那个 Inventory 里刷内容。
   * 这样有两个好处——不会闪屏，也不会因为"带着光标物品重开容器"而丢失玩家手上正拿着的那摞东西
   * （重开窗口时客户端的光标物品是有丢失风险的，而这里从头到尾没碰过光标）。
   * 界面不是我们的时候（例如玩家已经关掉）才退回重开。
   */
  private void repaint(Player player, ItemEditorState state) {
    Inventory open = player.getOpenInventory().getTopInventory();
    WardGui.Holder holder = WardGui.holderOf(open);
    if (holder != null && holder.kind() == WardGui.Kind.ITEM_EDITOR
        && holder.payload() == state) {
      paintItemEditor(open, state);
      player.updateInventory();
      return;
    }
    renderItemEditor(player, state, null);
  }

  /** 把当前页画进给定界面（每次画之前先紧凑化：条目从左上角依次排开 = 自动排序） */
  private void paintItemEditor(Inventory inv, ItemEditorState state) {
    compact(state);
    inv.clear();

    int totalWeight = 0;
    for (WardItemEntry e : state.entries) {
      if (e != null) {
        totalWeight += Math.max(0, e.weight());
      }
    }

    int start = state.page * PAGE_SIZE;
    for (int i = 0; i < PAGE_SIZE; i++) {
      int index = start + i;
      int[] slots = blockSlots(i);
      if (index >= state.entries.size()) {
        // 空块：用「结构空位」明示可放置的位置（否则操作者不知道该往哪放物品）
        inv.setItem(slots[0], emptyItemSlot());
        inv.setItem(slots[1], fillerSlot());
        inv.setItem(slots[2], fillerSlot());
        continue;
      }
      WardItemEntry entry = entryAt(state, index);
      if (entry == null) {
        inv.setItem(slots[0], emptyItemSlot());
        inv.setItem(slots[1], fillerSlot());
        inv.setItem(slots[2], fillerSlot());
        continue;
      }
      inv.setItem(slots[0], itemSlot(entry, index, totalWeight));
      inv.setItem(slots[1], weightSlot(entry, totalWeight));
      inv.setItem(slots[2], raritySlot(entry));
    }

    // 底行：分页 / 取消 / 保存（其余格子也铺满，避免快捷移动把物品塞进无主的空槽）
    int pages = pageCount(state);
    inv.setItem(45, button(Material.ARROW, i18n.tr("jwardchest.items.prev"),
        List.of(i18n.tr("jwardchest.items.page", state.page + 1, pages))));
    inv.setItem(49, button(Material.BARRIER, i18n.tr("jwardchest.items.cancel"),
        List.of(i18n.tr("jwardchest.items.cancel_hint"))));
    inv.setItem(51, button(Material.EMERALD, i18n.tr("jwardchest.items.save"),
        List.of(i18n.tr("jwardchest.items.save_hint"))));
    inv.setItem(53, button(Material.ARROW, i18n.tr("jwardchest.items.next"),
        List.of(i18n.tr("jwardchest.items.page", state.page + 1, pages))));
    for (int slot : new int[] { 46, 47, 48, 50, 52 }) {
      inv.setItem(slot, fillerSlot());
    }
  }

  /**
   * 去掉列表里的 {@code null} 空洞，让条目紧凑排列
   * <p>
   * 放入/移除后调用，因此「自动排序」的含义是：条目始终从第 1 格开始依次占位，
   * 不会因为中间被删掉一条而留下空格。页码也在这里夹回合法范围。
   */
  private static void compact(ItemEditorState state) {
    List<WardItemEntry> packed = new ArrayList<>(state.entries.size());
    for (WardItemEntry e : state.entries) {
      if (e != null) {
        packed.add(e);
      }
    }
    if (packed.size() != state.entries.size()) {
      state.entries.clear();
      state.entries.addAll(packed);
    }
    state.page = Math.max(0, Math.min(state.page, Math.max(0, pageCount(state) - 1)));
  }

  /**
   * 取第 {@code index} 条条目
   * <p>
   * 列表理论上可能存在 {@code null} 空洞（历史遗留写法），因此取值一律走这里而不是直接 get。
   * 正常流程下每次绘制前都会 {@link #compact(ItemEditorState)}，不会有空洞。
   *
   * @return 条目；该位置为空返回 null
   */
  @Nullable
  private static WardItemEntry entryAt(ItemEditorState state, int index) {
    return (index >= 0 && index < state.entries.size()) ? state.entries.get(index) : null;
  }

  /**
   * 把条目放到指定块（不足处补 null 空洞）
   * <p>
   * 随后立刻会 {@code compact()}，所以「放在第几块」不影响最终顺序——条目总是排到队尾。
   */
  private static void setEntryAt(ItemEditorState state, int index, @Nullable WardItemEntry entry) {
    while (state.entries.size() <= index) {
      state.entries.add(null);
    }
    state.entries.set(index, entry);
  }

  /** 空块里的「结构空位」：提醒操作者把背包里的物品放到这里 */
  private static ItemStack emptyItemSlot() {
    return button(Material.STRUCTURE_VOID,
        i18n.tr("jwardchest.items.empty_slot"),
        List.of(
            i18n.tr("jwardchest.items.empty_slot_hint"),
            i18n.tr("jwardchest.items.slot.help")));
  }

  /** 空块里另外两格的弱占位，让「一格三件」的结构看得清 */
  private static ItemStack fillerSlot() {
    return button(Material.GRAY_STAINED_GLASS_PANE,
        i18n.tr("jwardchest.items.empty_filler"), List.of());
  }

  /** 第 i 个块的三个槽位（物品格 / 概率格 / 珍惜度格） */
  private static int[] blockSlots(int i) {
    int row = i / 2;
    int block = i % 2;
    int startCol = block == 0 ? 1 : 5;
    int base = row * 9 + startCol;
    return new int[] { base, base + 1, base + 2 };
  }

  /** 总页数：末页永远保留空块以便新增（列表允许 null 空洞，空洞也占位置） */
  private static int pageCount(ItemEditorState state) {
    return Math.max(1, (state.entries.size() / PAGE_SIZE) + 1);
  }

  private ItemStack itemSlot(WardItemEntry entry, int index, int totalWeight) {
    ItemStack item = entry.display(entry.maxAmount());
    ItemMeta meta = item.getItemMeta();
    if (meta != null) {
      List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
      lore.add(WardGui.legacy(i18n.tr("jwardchest.items.slot.index", index + 1)));
      lore.add(WardGui.legacy(i18n.tr("jwardchest.items.slot.amount",
          entry.minAmount(), entry.maxAmount(), entry.maxStackSize())));
      lore.add(WardGui.legacy(i18n.tr("jwardchest.items.slot.weight",
          entry.weight(), totalWeight,
          String.format("%.2f", WardRoller.share(entry, totalWeight) * 100.0))));
      lore.add(WardGui.legacy(i18n.tr("jwardchest.items.slot.help")));
      meta.lore(lore);
      item.setItemMeta(meta);
    }
    return item;
  }

  private ItemStack weightSlot(WardItemEntry entry, int totalWeight) {
    return button(Material.REDSTONE,
        i18n.tr("jwardchest.items.weight", entry.weight(), totalWeight),
        List.of(
            i18n.tr("jwardchest.items.weight_share",
                String.format("%.2f", WardRoller.share(entry, totalWeight) * 100.0)),
            i18n.tr("jwardchest.items.weight_help")));
  }

  private ItemStack raritySlot(WardItemEntry entry) {
    return button(woolOf(entry.rarity()),
        entry.rarity().color() + entry.rarity().displayName(),
        List.of(i18n.tr("jwardchest.items.rarity_help")));
  }

  // ──────────────────────────────────────────────
  // 预览
  // ──────────────────────────────────────────────

  /**
   * 预览
   *
   * @param instant Shift 点击时为 true：跳过粗筛与搜索动画，直接摆出全部结果
   */
  private void openPreview(Player player, UUID profileId, Location block, boolean instant) {
    WardProfile profile = store.effective(profileId);
    Location loc = block == null ? player.getLocation() : block;
    WardSession session = sessions.getOrCreate(
        WardSessionManager.previewKey(loc.getWorld(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(),
            player.getUniqueId()),
        profileId, loc.getWorld(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(), true);
    search.open(player, profile, session, !instant);
  }

  // ──────────────────────────────────────────────
  // 点击处理
  // ──────────────────────────────────────────────

  /**
   * 处理我们界面里的点击
   *
   * @return true 表示已被处理（调用方不必再处理）
   */
  public boolean handleClick(InventoryClickEvent event, WardGui.Holder holder) {
    if (!(event.getWhoClicked() instanceof Player player)) {
      return false;
    }
    switch (holder.kind()) {
      case EDITOR -> {
        return handleEditorClick(event, player, holder);
      }
      case CONTAINER_PICKER -> {
        return handlePickerClick(event, player, holder);
      }
      case MAX_DRAW -> {
        return handleMaxDrawClick(event, player, holder);
      }
      case ITEM_EDITOR -> {
        return handleItemEditorClick(event, player, holder);
      }
      default -> {
        return false;
      }
    }
  }

  private boolean handleEditorClick(InventoryClickEvent event, Player player, WardGui.Holder holder) {
    if (event.getClickedInventory() == null
        || !event.getClickedInventory().equals(player.getOpenInventory().getTopInventory())) {
      return true;
    }
    UUID id = holder.profileId();
    Location block = holder.payload() instanceof Location l ? l : null;

    switch (event.getRawSlot()) {
      case 10 -> openContainerPicker(player, id, block);
      case 11 -> {
        WardProfile profile = store.effective(id);
        String old = profile.inPreset() ? profile.presetCode() : WardProfile.UNSET_CODE;
        module.chatInput().request(player, WardChatInput.TYPE_CODE, old, value -> {
          store.setPresetCode(id, value);
          player.sendMessage(i18n.tr("jwardchest.editor.code_updated", value));
          reopenLater(player, () -> openEditor(player, id, block));
        });
      }
      case 12 -> openItemEditor(player, id, block);
      case 13 -> openMaxDraw(player, id, block);
      case 14 -> {
        WardProfile profile = store.effective(id);
        module.chatInput().request(player, WardChatInput.TYPE_TITLE, profile.title(), value -> {
          WardProfile updated = store.effective(id);
          updated.title(value);
          store.save(updated);
          player.sendMessage(i18n.tr("jwardchest.editor.title_updated", value));
          reopenLater(player, () -> openEditor(player, id, block));
        });
      }
      case 15 -> {
        WardProfile profile = store.effective(id);
        module.chatInput().request(player, WardChatInput.TYPE_WEIGHT,
            String.valueOf(profile.prefilterSeconds()), value -> {
              int seconds = parseIntOrDefault(value, profile.prefilterSeconds());
              WardProfile updated = store.effective(id);
              updated.prefilterSeconds(seconds);
              store.save(updated);
              player.sendMessage(i18n.tr("jwardchest.editor.prefilter_updated", seconds));
              reopenLater(player, () -> openEditor(player, id, block));
            });
      }
      case 16 -> openPreview(player, id, block, event.isShiftClick());
      default -> {
        return true;
      }
    }
    return true;
  }

  private boolean handlePickerClick(InventoryClickEvent event, Player player, WardGui.Holder holder) {
    if (event.getClickedInventory() == null
        || !event.getClickedInventory().equals(player.getOpenInventory().getTopInventory())) {
      return true;
    }
    UUID id = holder.profileId();
    Location block = holder.payload() instanceof Location l ? l : null;
    int slot = event.getRawSlot();

    // 行数选择界面（9 格）与类型选择界面（27 格）共用这一种 Holder，用格子数区分
    if (player.getOpenInventory().getTopInventory().getSize() == 9) {
      if (slot < 1 || slot > 6) {
        return true;
      }
      WardProfile profile = store.effective(id);
      profile.rows(slot);
      store.save(profile);
      player.sendMessage(i18n.tr("jwardchest.rows.updated", profile.rows()));
      reopenLater(player, () -> openEditor(player, id, block));
      return true;
    }

    WardContainerType[] types = WardContainerType.values();
    if (slot < 0 || slot >= types.length) {
      return true;
    }
    WardContainerType picked = types[slot];
    WardProfile profile = store.effective(id);
    profile.container(picked);
    store.save(profile);

    if (picked.variableRows()) {
      openRowsPicker(player, id, block);
    } else {
      player.sendMessage(i18n.tr("jwardchest.picker.updated", picked.displayName()));
      reopenLater(player, () -> openEditor(player, id, block));
    }
    return true;
  }

  private boolean handleMaxDrawClick(InventoryClickEvent event, Player player, WardGui.Holder holder) {
    if (event.getClickedInventory() == null
        || !event.getClickedInventory().equals(player.getOpenInventory().getTopInventory())) {
      return true;
    }
    int slot = event.getRawSlot();
    boolean floorRow;
    int index;
    if (slot >= 0 && slot < WardRarity.values().length) {
      floorRow = true;
      index = slot;
    } else if (slot >= 9 && slot < 9 + WardRarity.values().length) {
      floorRow = false;
      index = slot - 9;
    } else {
      return true;
    }

    WardRarity rarity = WardRarity.values()[index];
    UUID id = holder.profileId();
    Location block = holder.payload() instanceof Location l ? l : null;
    WardProfile profile = store.effective(id);
    int current = floorRow ? profile.minDraw(rarity) : profile.maxDraw(rarity);

    if (event.isShiftClick()) {
      module.chatInput().request(player, WardChatInput.TYPE_WEIGHT, String.valueOf(current), value -> {
        WardProfile updated = store.effective(id);
        int fallback = floorRow ? updated.minDraw(rarity) : updated.maxDraw(rarity);
        applyDraw(updated, rarity, floorRow, parseIntOrDefault(value, fallback));
        store.save(updated);
        reopenLater(player, () -> openMaxDraw(player, id, block));
      });
      return true;
    }

    int delta = event.isRightClick() ? -1 : 1;
    applyDraw(profile, rarity, floorRow, current + delta);
    store.save(profile);
    reopenLater(player, () -> openMaxDraw(player, id, block));
    return true;
  }

  /** 写入上排（保底）或下排（上限）的值；两者的联动由 {@link WardProfile} 内部保证 */
  private static void applyDraw(WardProfile profile, WardRarity rarity, boolean floorRow, int value) {
    if (floorRow) {
      profile.minDraw(rarity, value);
    } else {
      profile.maxDraw(rarity, value);
    }
  }

  private boolean handleItemEditorClick(InventoryClickEvent event, Player player,
      WardGui.Holder holder) {
    if (!(holder.payload() instanceof ItemEditorState state)) {
      return false;
    }
    Location block = null;
    if (event.getClickedInventory() == null) {
      return true;
    }
    boolean top = event.getClickedInventory().equals(player.getOpenInventory().getTopInventory());

    // 底行：分页 / 取消 / 保存
    if (top && event.getRawSlot() >= 45) {
      switch (event.getRawSlot()) {
        case 45 -> {
          state.page = Math.max(0, state.page - 1);
          repaint(player, state);
        }
        case 53 -> {
          state.page = Math.min(pageCount(state) - 1, state.page + 1);
          repaint(player, state);
        }
        case 49 -> {
          itemWork.remove(player.getUniqueId());
          player.sendMessage(i18n.tr("jwardchest.items.discarded"));
          reopenLater(player, () -> openEditor(player, state.profileId, block));
        }
        case 51 -> {
          WardProfile profile = store.effective(state.profileId);
          profile.entries().clear();
          int kept = 0;
          for (WardItemEntry e : state.entries) {
            if (e != null) {
              profile.entries().add(e);
              kept++;
            }
          }
          store.save(profile);
          itemWork.remove(player.getUniqueId());
          player.sendMessage(i18n.tr("jwardchest.items.saved", kept));
          reopenLater(player, () -> openEditor(player, state.profileId, block));
        }
        default -> {
          return true;
        }
      }
      return true;
    }

    if (!top) {
      return false;
    }

    int[] kind = locateSlot(event.getRawSlot());
    if (kind == null) {
      return true;
    }
    int blockIndex = kind[0];
    int part = kind[1];
    int index = state.page * PAGE_SIZE + blockIndex;

    switch (part) {
      case 0 -> handleItemSlot(event, player, state, index, block);
      case 1 -> handleWeightSlot(event, player, state, index, block);
      case 2 -> handleRaritySlot(player, state, index, event.isRightClick(), block);
      default -> {
        return true;
      }
    }
    return true;
  }

  /**
   * 定位点击的槽位属于哪个块的第几个部件
   *
   * @return {页内块序号 0-9, 部件}，部件 0=物品格 1=概率格 2=珍惜度格；不在任何块内返回 null
   */
  @Nullable
  private static int[] locateSlot(int rawSlot) {
    if (rawSlot < 0 || rawSlot >= 45) {
      return null;
    }
    int row = rawSlot / 9;
    int col = rawSlot % 9;
    int part;
    if (col >= 1 && col <= 3) {
      part = col - 1;
    } else if (col >= 5 && col <= 7) {
      part = col - 5;
    } else {
      return null;
    }
    int which = col <= 3 ? 0 : 1;
    return new int[] { row * 2 + which, part };
  }

  private void handleItemSlot(InventoryClickEvent event, Player player, ItemEditorState state,
      int index, Location block) {
    ItemStack cursor = event.getCursor();
    boolean drop = event.getClick() == ClickType.DROP || event.getClick() == ClickType.CONTROL_DROP;
    WardItemEntry existing = entryAt(state, index);

    if (drop) {
      if (existing != null) {
        setEntryAt(state, index, null);
        player.sendMessage(i18n.tr("jwardchest.items.removed"));
        repaint(player, state);
      }
      return;
    }

    if (cursor != null && !cursor.getType().isAir()) {
      // 登记模板：数量随物品堆叠继承（由 WardItemEntry.of 负责）。
      // **不消耗**玩家手上的物品——这里是「登记」而不是「投入」，光标原样不动，
      // 玩家把手上那摞放回哪里由他自己决定。
      setEntryAt(state, index, WardItemEntry.of(cursor));
      player.sendMessage(i18n.tr("jwardchest.items.placed"));
      repaint(player, state);
      return;
    }

    // 空位左键/右键：没有条目就什么都不做
    WardItemEntry entry = existing;
    if (entry == null) {
      return;
    }
    if (event.isRightClick()) {
      module.chatInput().request(player, WardChatInput.TYPE_MAX,
          String.valueOf(entry.maxAmount()), value -> {
            entry.setMax(parseIntOrDefault(value, entry.maxAmount()));
            repaint(player, state);
          });
    } else {
      module.chatInput().request(player, WardChatInput.TYPE_MIN,
          String.valueOf(entry.minAmount()), value -> {
            entry.setMin(parseIntOrDefault(value, entry.minAmount()));
            repaint(player, state);
          });
    }
  }


  private void handleWeightSlot(InventoryClickEvent event, Player player, ItemEditorState state,
      int index, Location block) {
    WardItemEntry entry = entryAt(state, index);
    if (entry == null) {
      return;
    }

    if (event.isShiftClick()) {
      module.chatInput().request(player, WardChatInput.TYPE_WEIGHT,
          String.valueOf(entry.weight()), value -> {
            entry.weight(parseIntOrDefault(value, entry.weight()));
            repaint(player, state);
          });
      return;
    }

    int step = event.isRightClick() ? -1 : 1;
    entry.weight(entry.weight() + step);
    repaint(player, state);
  }

  private void handleRaritySlot(Player player, ItemEditorState state, int index, boolean right,
      Location block) {
    WardItemEntry entry = entryAt(state, index);
    if (entry == null) {
      return;
    }
    WardRarity[] all = WardRarity.values();
    int next = entry.rarity().ordinal() + (right ? -1 : 1);
    next = Math.floorMod(next, all.length);
    entry.rarity(all[next]);
    repaint(player, state);
  }

  // ──────────────────────────────────────────────
  // 工具
  // ─────────────────────────────────────────────

  /** 构造一个按钮物品 */
  private static ItemStack button(Material material, String name, List<String> lore) {
    return button(material, name, lore, false);
  }

  @SuppressWarnings("deprecation")
  private static ItemStack button(Material material, String name, List<String> lore, boolean glow) {
    ItemStack item = new ItemStack(material);
    ItemMeta meta = item.getItemMeta();
    if (meta != null) {
      meta.displayName(WardGui.legacy(name));
      if (lore != null && !lore.isEmpty()) {
        meta.lore(lore.stream().map(WardGui::legacy).toList());
      }
      if (glow) {
        meta.setEnchantmentGlintOverride(true);
      }
      item.setItemMeta(meta);
    }
    return item;
  }

  private static int parseIntOrDefault(String value, int def) {
    try {
      return Integer.parseInt(value.trim());
    } catch (Exception e) {
      return def;
    }
  }

  /** 下一 tick 再执行（避免在点击事件里重开界面导致客户端标题不刷新） */
  private void reopenLater(Player player, Runnable action) {
    Bukkit.getScheduler().runTask(Joyous.plugin, action);
  }

  /** 玩家退出时清理工作副本 */
  public void clearWorking(Player player) {
    itemWork.remove(player.getUniqueId());
  }
}
