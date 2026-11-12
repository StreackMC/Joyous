package com.github.streackmc.Joyous.JwardChest;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nullable;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import com.github.streackmc.Joyous.utils.i18n;
import com.github.streackmc.Joyous.utils.interact.DetachReasonEnum;
import com.github.streackmc.Joyous.utils.interact.IPageContext;
import com.github.streackmc.Joyous.utils.interact.PageArbiter;
import com.github.streackmc.Joyous.utils.interact.chestui.Canvas;
import com.github.streackmc.Joyous.utils.interact.chestui.Click;
import com.github.streackmc.Joyous.utils.interact.chestui.ClickPolicy;
import com.github.streackmc.Joyous.utils.interact.chestui.InteractContainerEnum;
import com.github.streackmc.Joyous.utils.interact.input.ChatPrompt;

import net.kyori.adventure.text.Component;

/**
 * 待选物品编辑
 * <p>
 * 六行：前五行每行两块，一块 = 物品格 + 权重格 + 珍惜度格；底行是翻页、取消、保存。
 * <p>
 * <b>编辑的是一份工作副本</b>：进入时从容器设置里复制条目，之后所有改动只落在副本上，
 * 只有按「保存」才写回设置（并按预设组联动整组），按「取消」直接丢弃。
 * 因此副本必须是<b>深拷贝</b>——浅拷贝会让「取消」变成假的（改动已经穿透到设置对象里，
 * 下一次任何一次无关的保存都会把它们一起落盘）。
 * <p>
 * 界面里的展示物品一律是 {@code clone()} 的副本，改 Lore / 改名字都不影响真实内容。
 *
 * @author Neonai
 * @since 0.7.0
 */
final class WardItemEditorPage extends WardSubPage {

  static final String KEY = "ward.items";

  /** 每页可编辑的条目数（5 行 × 每行 2 块） */
  private static final int PAGE_SIZE = 10;

  /** 工作副本：玩家 → 编辑状态。跨「关掉再打开」保留，直到保存或取消 */
  private static final Map<UUID, State> WORK = new HashMap<>();

  /** 本次进入是否已经以「保存 / 取消」收尾：仅用于区分「玩家按 ESC 退出」 */
  private boolean finished;

  WardItemEditorPage(WardEditorGui editor, WardProfileStore store,
      UUID profileId, @Nullable Location block) {
    super(editor, store, profileId, block);
  }

  // ──────────────────────────────────────────────
  // 工作副本
  // ──────────────────────────────────────────────

  /** 一份待选物品的工作状态（显式保存 / 取消） */
  private static final class State {
    final UUID profileId;
    final List<WardItemEntry> entries = new ArrayList<>();
    int page;

    State(UUID profileId, WardProfile profile) {
      this.profileId = profileId;
      for (WardItemEntry entry : profile.entries()) {
        // 深拷贝：浅拷贝会让「取消」失效（改动直接穿透进设置对象）
        entries.add(entry == null ? null : entry.copy());
      }
    }
  }

  /** 取该玩家的工作副本；没有或换了容器就新建一份 */
  private State state(Player player) {
    State state = WORK.get(player.getUniqueId());
    if (state == null || !state.profileId.equals(profileId)) {
      state = new State(profileId, store.effective(profileId));
      WORK.put(player.getUniqueId(), state);
    }
    return state;
  }

  /** 玩家退出时丢弃工作副本 */
  static void clearWorking(Player player) {
    WORK.remove(player.getUniqueId());
  }

  // ──────────────────────────────────────────────
  // 页面外形
  // ──────────────────────────────────────────────

  @Override
  public String key() {
    return KEY;
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
    return Canvas.title(viewer, i18n.tr("jwardchest.items.title"));
  }

  /** 内容全在工作副本里，随时可以重画 */
  @Override
  public boolean keepable() {
    return true;
  }

  /** 上半区独占；背包侧放行整理，只拦「可能搬进上半区」的操作（Shift 仍会转发给本页做登记） */
  @Override
  public ClickPolicy policy() {
    return ClickPolicy.TRANSFER_ONLY;
  }

  // ──────────────────────────────────────────────
  // 渲染
  // ──────────────────────────────────────────────

  @Override
  public void render(IPageContext ctx, Canvas canvas) {
    Player player = ctx.player();
    if (player == null || canvas.size() < 45) {
      return;
    }
    State state = state(player);
    compact(state);
    canvas.clear();

    int totalWeight = 0;
    for (WardItemEntry entry : state.entries) {
      if (entry != null) {
        totalWeight += Math.max(0, entry.weight());
      }
    }

    int start = state.page * PAGE_SIZE;
    for (int i = 0; i < PAGE_SIZE; i++) {
      int index = start + i;
      int[] slots = blockSlots(i);
      WardItemEntry entry = entryAt(state, index);
      if (entry == null) {
        // 空块：用「结构空位」明示可放置的位置（否则操作者不知道该往哪放物品）
        canvas.set(slots[0], emptyItemSlot());
        canvas.set(slots[1], fillerSlot());
        canvas.set(slots[2], fillerSlot());
        continue;
      }
      canvas.set(slots[0], itemSlot(entry, index, totalWeight));
      canvas.set(slots[1], weightSlot(entry, totalWeight));
      canvas.set(slots[2], raritySlot(entry));
    }

    int pages = pageCount(state);
    canvas.set(45, Canvas.button(Material.ARROW, i18n.tr("jwardchest.items.prev"),
        List.of(i18n.tr("jwardchest.items.page", state.page + 1, pages))));
    canvas.set(49, Canvas.button(Material.BARRIER, i18n.tr("jwardchest.items.cancel"),
        List.of(i18n.tr("jwardchest.items.cancel_hint"))));
    canvas.set(51, Canvas.button(Material.EMERALD, i18n.tr("jwardchest.items.save"),
        List.of(i18n.tr("jwardchest.items.save_hint"))));
    canvas.set(53, Canvas.button(Material.ARROW, i18n.tr("jwardchest.items.next"),
        List.of(i18n.tr("jwardchest.items.page", state.page + 1, pages))));
    for (int slot : new int[] { 46, 47, 48, 50, 52 }) {
      canvas.set(slot, fillerSlot());
    }
  }

  // ──────────────────────────────────────────────
  // 点击
  // ──────────────────────────────────────────────

  @Override
  public Verdict onClick(IPageContext ctx, Click click) {
    Player player = click.player();
    State state = state(player);

    if (!click.top()) {
      // 背包侧 Shift+左键：登记成一条新条目（不消耗原物品，只取模板副本）
      if (click.click() == ClickType.SHIFT_LEFT) {
        append(player, state, click.event().getCurrentItem());
      }
      return Verdict.HANDLED;
    }

    int slot = click.slot();
    if (slot >= 45) {
      return clickBottom(ctx, player, state, slot);
    }

    int[] kind = locateSlot(slot);
    if (kind == null) {
      return Verdict.HANDLED;
    }
    int index = state.page * PAGE_SIZE + kind[0];
    return switch (kind[1]) {
      case 0 -> handleItemSlot(ctx, player, state, index, click);
      case 1 -> handleWeightSlot(ctx, player, state, index, click);
      case 2 -> handleRaritySlot(ctx, player, state, index, click.right());
      default -> Verdict.HANDLED;
    };
  }

  /** 底行：翻页 / 取消 / 保存 */
  private Verdict clickBottom(IPageContext ctx, Player player, State state, int slot) {
    switch (slot) {
      case 45 -> {
        state.page = Math.max(0, state.page - 1);
        repaint(ctx);
      }
      case 53 -> {
        state.page = Math.min(pageCount(state) - 1, state.page + 1);
        repaint(ctx);
      }
      case 49 -> {
        finished = true;
        WORK.remove(player.getUniqueId());
        tell(player, "jwardchest.items.discarded");
        backToEditor(ctx);
      }
      case 51 -> {
        finished = true;
        saveToProfile(player, state);
        WORK.remove(player.getUniqueId());
        backToEditor(ctx);
      }
      default -> {
        return Verdict.HANDLED;
      }
    }
    return Verdict.HANDLED;
  }

  /** 把工作副本写回设置（属于预设组时由 store 联动整组） */
  private void saveToProfile(Player player, State state) {
    WardProfile profile = store.effective(profileId);
    profile.entries().clear();
    int kept = 0;
    for (WardItemEntry entry : state.entries) {
      if (entry != null) {
        profile.entries().add(entry);
        kept++;
      }
    }
    save(profile);
    // 待选物品变了 → 已生成的内容作废，下次打开重新抽取（否则改了表还是旧结果）
    editor.invalidate(profileId);
    tell(player, "jwardchest.items.saved", kept);
  }

  /** 物品格：丢弃删除、光标登记模板、空位则按左右键改数量 */
  private Verdict handleItemSlot(IPageContext ctx, Player player, State state, int index,
      Click click) {
    boolean drop = click.click() == ClickType.DROP || click.click() == ClickType.CONTROL_DROP;
    WardItemEntry existing = entryAt(state, index);

    if (drop) {
      if (existing != null) {
        setEntryAt(state, index, null);
        repaint(ctx);
      }
      return Verdict.HANDLED;
    }

    if (click.holding()) {
      // 登记模板：数量随物品堆叠继承（由 WardItemEntry.of 负责）。
      // **不消耗**玩家手上的物品——这里是「登记」而不是「投入」，光标原样不动。
      setEntryAt(state, index, WardItemEntry.of(click.cursor()));
      repaint(ctx);
      return Verdict.HANDLED;
    }

    WardItemEntry entry = existing;
    if (entry == null) {
      return Verdict.HANDLED;
    }
    if (click.right()) {
      openChat(ctx, "item." + index + ".max",
          ChatPrompt.of(i18n.tr("jwardchest.input.field.max"),
              String.valueOf(entry.maxAmount())),
          value -> {
            entry.setMax(intOrDefault(value, entry.maxAmount()));
            repaint(ctx);
          });
    } else {
      openChat(ctx, "item." + index + ".min",
          ChatPrompt.of(i18n.tr("jwardchest.input.field.min"),
              String.valueOf(entry.minAmount())),
          value -> {
            entry.setMin(intOrDefault(value, entry.minAmount()));
            repaint(ctx);
          });
    }
    return Verdict.HANDLED;
  }

  /** 权重格：Shift 输入精确值，否则左右键 ±1 */
  private Verdict handleWeightSlot(IPageContext ctx, Player player, State state, int index,
      Click click) {
    WardItemEntry entry = entryAt(state, index);
    if (entry == null) {
      return Verdict.HANDLED;
    }
    if (click.shift()) {
      openChat(ctx, "item." + index + ".weight",
          ChatPrompt.of(i18n.tr("jwardchest.input.field.weight"),
              String.valueOf(entry.weight())),
          value -> {
            entry.weight(intOrDefault(value, entry.weight()));
            repaint(ctx);
          });
      return Verdict.HANDLED;
    }
    entry.weight(entry.weight() + (click.right() ? -1 : 1));
    repaint(ctx);
    return Verdict.HANDLED;
  }

  /** 珍惜度格：左右键在声明顺序里前后切换 */
  private Verdict handleRaritySlot(IPageContext ctx, Player player, State state, int index,
      boolean right) {
    WardItemEntry entry = entryAt(state, index);
    if (entry == null) {
      return Verdict.HANDLED;
    }
    List<WardRarity> all = WardRarity.list();
    int current = WardRarity.indexOf(entry.rarity());
    entry.rarity(all.get(Math.floorMod(current + (right ? -1 : 1), all.size())));
    repaint(ctx);
    return Verdict.HANDLED;
  }

  /** Shift+左键背包物品：登记成一条新条目（不消耗原物品） */
  private void append(Player player, State state, @Nullable ItemStack fromInventory) {
    if (fromInventory == null || fromInventory.getType().isAir()) {
      return;
    }
    state.entries.add(WardItemEntry.of(fromInventory));
    compact(state);
    repaintVisible(player);
  }

  /**
   * 重绘（背包侧登记时拿不到 ctx）
   * <p>
   * 走仲裁器确认「当前显示的仍是本页」，再原地重画。
   */
  private void repaintVisible(Player player) {
    IPageContext ctx = PageArbiter.get().visible(player);
    if (ctx != null && ctx.page() == this) {
      repaint(ctx);
    }
  }

  // ──────────────────────────────────────────────
  // 生命周期
  // ──────────────────────────────────────────────

  @Override
  public void onDetach(IPageContext ctx, DetachReasonEnum why) {
    super.onDetach(ctx, why);
    // 玩家按 ESC / E 关掉：改动仍留在工作副本里，下次进来还能看到
    if (why == DetachReasonEnum.CLOSED && !finished) {
      Player player = ctx.player();
      if (player != null) {
        tell(player, "jwardchest.editor.items_unsaved");
      }
    }
  }

  // ──────────────────────────────────────────────
  // 布局与图标
  // ──────────────────────────────────────────────

  /** 去掉列表里的 {@code null} 空洞，让条目紧凑排列；页码也夹回合法范围 */
  private static void compact(State state) {
    List<WardItemEntry> packed = new ArrayList<>(state.entries.size());
    for (WardItemEntry entry : state.entries) {
      if (entry != null) {
        packed.add(entry);
      }
    }
    if (packed.size() != state.entries.size()) {
      state.entries.clear();
      state.entries.addAll(packed);
    }
    state.page = Math.max(0, Math.min(state.page, Math.max(0, pageCount(state) - 1)));
  }

  @Nullable
  private static WardItemEntry entryAt(State state, int index) {
    return (index >= 0 && index < state.entries.size()) ? state.entries.get(index) : null;
  }

  private static void setEntryAt(State state, int index, @Nullable WardItemEntry entry) {
    while (state.entries.size() <= index) {
      state.entries.add(null);
    }
    state.entries.set(index, entry);
  }

  /** 第 i 个块的三个槽位（物品格 / 权重格 / 珍惜度格） */
  private static int[] blockSlots(int i) {
    int row = i / 2;
    int block = i % 2;
    int startCol = block == 0 ? 1 : 5;
    int base = row * 9 + startCol;
    return new int[] { base, base + 1, base + 2 };
  }

  /** 总页数：末页永远保留空块以便新增 */
  private static int pageCount(State state) {
    return Math.max(1, (state.entries.size() / PAGE_SIZE) + 1);
  }

  /**
   * 定位点击的槽位属于哪个块的第几个部件
   *
   * @return {页内块序号 0-9, 部件}，部件 0=物品格 1=权重格 2=珍惜度格；不在任何块内返回 null
   */
  @Nullable
  private static int[] locateSlot(int slot) {
    if (slot < 0 || slot >= 45) {
      return null;
    }
    int row = slot / 9;
    int col = slot % 9;
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

  /** 空块里的「结构空位」：提醒操作者把背包里的物品放到这里 */
  private static ItemStack emptyItemSlot() {
    return Canvas.button(Material.STRUCTURE_VOID,
        i18n.tr("jwardchest.items.empty_slot"),
        List.of(
            i18n.tr("jwardchest.items.empty_slot_hint"),
            i18n.tr("jwardchest.items.slot.help")));
  }

  /** 空块里另外两格的弱占位，让「一格三件」的结构看得清 */
  private static ItemStack fillerSlot() {
    return Canvas.button(Material.GRAY_STAINED_GLASS_PANE,
        i18n.tr("jwardchest.items.empty_filler"), List.of());
  }

  private static ItemStack itemSlot(WardItemEntry entry, int index, int totalWeight) {
    ItemStack item = entry.display(entry.maxAmount());
    ItemMeta meta = item.getItemMeta();
    if (meta != null) {
      List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
      lore.add(Canvas.legacy(i18n.tr("jwardchest.items.slot.index", index + 1)));
      lore.add(Canvas.legacy(i18n.tr("jwardchest.items.slot.amount",
          entry.minAmount(), entry.maxAmount(), entry.maxStackSize())));
      lore.add(Canvas.legacy(i18n.tr("jwardchest.items.slot.weight",
          entry.weight(), totalWeight,
          String.format("%.2f", WardRoller.share(entry, totalWeight) * 100.0))));
      lore.add(Canvas.legacy(i18n.tr("jwardchest.items.slot.help")));
      meta.lore(lore);
      item.setItemMeta(meta);
    }
    return item;
  }

  private static ItemStack weightSlot(WardItemEntry entry, int totalWeight) {
    return Canvas.button(Material.REDSTONE,
        i18n.tr("jwardchest.items.weight", entry.weight(), totalWeight),
        List.of(
            i18n.tr("jwardchest.items.weight_share",
                String.format("%.2f", WardRoller.share(entry, totalWeight) * 100.0)),
            i18n.tr("jwardchest.items.weight_help")));
  }

  private static ItemStack raritySlot(WardItemEntry entry) {
    return Canvas.button(entry.rarity().wool(),
        entry.rarity().color() + entry.rarity().displayName(),
        List.of(i18n.tr("jwardchest.items.rarity_help")));
  }
}
