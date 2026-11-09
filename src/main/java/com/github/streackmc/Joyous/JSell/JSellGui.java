package com.github.streackmc.Joyous.JSell;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import com.github.streackmc.Joyous.Joyous;
import com.github.streackmc.Joyous.i18n;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

/**
 * 玩家的出售界面（6 行 × 9 列）
 * <p>
 * 布局：前 5 行（0-44）是放入区，最后一行是操作栏——首格关闭、末格确认出售、中间显示结算预览。
 * <p>
 * 两条不变量：
 * <ul>
 *   <li><b>界面上的物品永远是副本</b>：真身在 {@link SellSession#items()} 里。所以给展示物品追加
 *       「售出价值」Lore、把不可售物品改名成「不接受此物品」都不会污染真正被卖掉的东西；</li>
 *   <li><b>托管内容每次变动都立刻落库</b>：这样断线、崩服都不会让界面里的东西凭空消失。</li>
 * </ul>
 * 确认按钮上的金额是<b>防抖更新</b>的：连点放入/取出不会每次都算一遍总价，而是合并成若干刻后的一次刷新。
 *
 * @author kdxiaoyi
 * @since 0.8.0
 */
public final class JSellGui {

  /** 界面总行数 */
  public static final int ROWS = 6;
  /** 界面总格数 */
  public static final int SIZE = ROWS * 9;
  /** 放入区最后一格的序号（不含） */
  public static final int DEPOSIT_SLOTS = 45;
  /** 关闭按钮 */
  public static final int CLOSE_SLOT = 45;
  /** 结算预览 */
  public static final int INFO_SLOT = 49;
  /** 确认出售按钮 */
  public static final int CONFIRM_SLOT = 53;

  private final JSellMain module;
  private final SellEntryStore entries;
  private final SellSessionManager sessions;
  private final SellService service;

  /** 防抖：玩家 → 待执行的刷新任务 */
  private final Map<UUID, Integer> pendingRefresh = new ConcurrentHashMap<>();

  public JSellGui(JSellMain module, SellEntryStore entries, SellSessionManager sessions,
      SellService service) {
    this.module = module;
    this.entries = entries;
    this.sessions = sessions;
    this.service = service;
  }

  // ──────────────────────────────────────────────
  // 界面标识
  // ──────────────────────────────────────────────

  /** 界面种类 */
  public enum Kind {
    /** 玩家出售界面 */
    SELL,
    /** 管理员的可售列表 */
    EDITOR_LIST,
    /** 管理员的条目详情 */
    EDITOR_DETAIL
  }

  /** 自定义 Holder：把界面和它的上下文绑在一起（避免用标题字符串判断界面） */
  public static final class Holder implements InventoryHolder {
    private final Kind kind;
    private final Object payload;
    private Inventory inventory;

    public Holder(Kind kind, @Nullable Object payload) {
      this.kind = kind;
      this.payload = payload;
    }

    public Kind kind() {
      return kind;
    }

    /** 附加上下文（会话 / 条目 id / 编辑器状态），可为 null */
    @Nullable
    public Object payload() {
      return payload;
    }

    public void attach(Inventory inventory) {
      this.inventory = inventory;
    }

    @Override
    public Inventory getInventory() {
      return inventory;
    }
  }

  @Nullable
  public static Holder holderOf(@Nullable Inventory inventory) {
    if (inventory == null) {
      return null;
    }
    InventoryHolder holder = inventory.getHolder();
    return holder instanceof Holder h ? h : null;
  }

  // ──────────────────────────────────────────────
  // 打开与绘制
  // ──────────────────────────────────────────────

  /** 打开出售界面（等同无参 {@code /jsell}） */
  public void open(Player player) {
    // 已经开着出售界面时只刷新，不重开：重开会先触发旧界面的「关闭」，
    // 那等于把里面的东西当成「取消」全退回背包，玩家会以为东西被吞了
    Holder opened = holderOf(player.getOpenInventory().getTopInventory());
    if (opened != null && opened.kind() == Kind.SELL) {
      repaint(player);
      SellSession existing = sessions.get(player);
      if (existing != null) {
        paintSummary(player, player.getOpenInventory().getTopInventory(), existing);
      }
      player.updateInventory();
      return;
    }

    SellSession session = sessions.open(player);
    // 崩服遗留的托管内容可能超过放入区容量：多出来的先还给他，
    // 否则它们会「看不见但被卖掉」——那是最糟糕的一种体验
    int max = JSellMain.CONF.maxSlots();
    int overflow = session.size() - max;
    if (overflow > 0) {
      List<ItemStack> extra = new ArrayList<>();
      for (int i = 0; i < overflow; i++) {
        ItemStack taken = session.take(session.size() - 1, 0);
        if (taken != null) {
          extra.add(taken);
        }
      }
      SellSessionManager.giveOrDrop(player, extra);
      sessions.persist(session);
      player.sendMessage(i18n.tr("jsell.escrow.overflow", extra.size()));
    }
    render(player, session, true);
  }

  /** 重开一个界面（会先关掉旧的，旧的关掉时按取消处理，所以调用方要先保证会话已清空） */
  public void render(Player player, SellSession session, boolean openNew) {
    Holder holder = new Holder(Kind.SELL, session);
    Inventory inv = Bukkit.createInventory(holder, SIZE,
        text(player, i18n.tr("jsell.gui.title")));
    holder.attach(inv);
    paintItems(player, inv, session);
    paintBar(player, inv);
    paintSummary(player, inv, session);
    if (openNew) {
      player.openInventory(inv);
    }
  }

  /**
   * 原地重绘物品区（不重开窗口，避免闪屏与「带着光标物品重开」的丢件风险）
   * <p>
   * <b>只重画放入区</b>：操作栏里的总价由防抖任务负责刷新，这样连续放入不会每次都算一遍总价，
   * 也不会在两次刷新之间看到按钮闪成空白（旧值一直留在那里）。
   */
  public void repaint(Player player) {
    SellSession session = sessions.get(player);
    Inventory open = player.getOpenInventory().getTopInventory();
    Holder holder = holderOf(open);
    if (session == null || holder == null || holder.kind() != Kind.SELL) {
      return;
    }
    paintItems(player, open, session);
    player.updateInventory();
  }

  /** 画放入区（0-44）：有东西的画物品副本，空格按配置铺玻璃板 */
  private void paintItems(Player player, Inventory inv, SellSession session) {
    List<ItemStack> items = session.items();
    int depositSlots = JSellMain.CONF.maxSlots();

    for (int i = 0; i < DEPOSIT_SLOTS; i++) {
      if (i < items.size() && i < depositSlots) {
        inv.setItem(i, displayOf(player, items.get(i)));
      } else if (i >= depositSlots) {
        inv.setItem(i, filler());
      } else if (inv.getItem(i) != null) {
        // 这一格原来是物品、现在被取走了：必须显式清空
        inv.setItem(i, null);
      }
    }
  }

  /** 画操作栏（只在开窗时画一次；除总价外都不变） */
  private void paintBar(Player player, Inventory inv) {
    inv.setItem(CLOSE_SLOT, button(Material.BARRIER,
        i18n.tr("jsell.gui.close"), List.of(i18n.tr("jsell.gui.close_lore"))));
    for (int slot = CLOSE_SLOT + 1; slot < SIZE; slot++) {
      if (slot != INFO_SLOT && slot != CONFIRM_SLOT) {
        inv.setItem(slot, filler());
      }
    }
  }

  /**
   * 绘制「结算预览」与「确认出售」两个按钮
   * <p>
   * 只有这两格会随总价变化，所以防抖刷新只碰它们——放入/取出时的重绘成本几乎为零。
   */
  private void paintSummary(Player player, Inventory inv, SellSession session) {
    double total = service.totalValue(session.items());
    int units = 0;
    int sellableStacks = 0;
    for (ItemStack item : session.items()) {
      if (service.entryOf(item) != null) {
        units += item.getAmount();
        sellableStacks++;
      }
    }
    String unit = JSellMain.CONF.unitName();

    List<String> infoLore = new ArrayList<>();
    infoLore.add(i18n.tr("jsell.gui.info_total", SellService.display(total), unit));
    infoLore.add(i18n.tr("jsell.gui.info_units", units, sellableStacks));
    infoLore.add(i18n.tr("jsell.gui.info_slots", session.size(), JSellMain.CONF.maxSlots()));
    inv.setItem(INFO_SLOT, button(Material.PAPER, i18n.tr("jsell.gui.info_name"), infoLore));

    boolean ready = total > 0.0;
    ItemStack confirm = button(ready ? Material.LIME_CONCRETE : Material.GRAY_CONCRETE,
        i18n.tr(ready ? "jsell.gui.confirm" : "jsell.gui.confirm_disabled",
            SellService.display(total), unit),
        List.of(
            i18n.tr("jsell.gui.confirm_lore", unit),
            i18n.tr("jsell.gui.confirm_hint")));
    ItemMeta meta = confirm.getItemMeta();
    if (meta != null) {
      meta.setEnchantmentGlintOverride(ready ? Boolean.TRUE : null);
      confirm.setItemMeta(meta);
    }
    inv.setItem(CONFIRM_SLOT, confirm);
  }

  /**
   * 生成「展示用」物品
   * <p>
   * 一定是 {@code clone()}：追加 Lore、给不可售物品改名都只发生在这份副本上。
   * 这是「展示物品与实际放入物品互不影响」的落点。
   */
  private ItemStack displayOf(Player player, ItemStack real) {
    ItemStack display = real.clone();
    SellEntry entry = entries.find(real);
    ItemMeta meta = display.getItemMeta();
    if (meta == null) {
      return display;
    }
    List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());

    if (entry == null) {
      // 不可售：名字换成醒目的提示，原名字放进 Lore 里，免得玩家不知道这是什么东西
      Component original = meta.displayName() == null
          ? legacy("§f" + prettyName(real))
          : meta.displayName();
      meta.displayName(legacy(i18n.tr("jsell.gui.unsellable_name")));
      lore.add(Component.empty());
      lore.add(legacy(i18n.tr("jsell.gui.unsellable_original")).append(original));
      for (String line : i18n.tr("jsell.gui.unsellable_lore").split("\n")) {
        lore.add(legacy(line));
      }
    } else if (JSellMain.CONF.appendLore()) {
      double unitValue = entry.unitValue();
      double stackValue = SellService.round(unitValue * real.getAmount());
      String unit = JSellMain.CONF.unitName();
      lore.add(Component.empty());
      lore.add(legacy(i18n.tr("jsell.gui.value_line",
          SellService.display(unitValue), unit)));
      if (real.getAmount() > 1) {
        lore.add(legacy(i18n.tr("jsell.gui.value_stack",
            real.getAmount(), SellService.display(stackValue), unit)));
      }
      if (entry.limitCount() > 0) {
        lore.add(legacy(entry.limitSeconds() > 0
            ? i18n.tr("jsell.gui.limit_window", entry.limitCount(), entry.limitSeconds(), unit)
            : i18n.tr("jsell.gui.limit_lifetime", entry.limitCount(), unit)));
      }
    }
    meta.lore(lore);
    display.setItemMeta(meta);
    return display;
  }

  // ──────────────────────────────────────────────
  // 点击处理
  // ──────────────────────────────────────────────

  /**
   * 处理出售界面里的点击
   * <p>
   * 上半区只认三件事：<b>存入</b>（空位 + 光标有东西）、<b>取回</b>（点已有物品的格子）、
   * <b>按钮</b>。其余（右键拆分、数字键交换、双击收拢、Q 丢弃）一律拦掉——
   * 界面内容由插件独占管理，原版的搬运语义在这里只会造成「物品被吞」。
   *
   * @return true 表示事件已被处理（调用方不必再管）
   */
  public boolean handleClick(InventoryClickEvent event, Player player, Holder holder) {
    if (!(holder.payload() instanceof SellSession session)) {
      return true;
    }
    Inventory top = player.getOpenInventory().getTopInventory();
    Inventory clicked = event.getClickedInventory();
    boolean inTop = clicked != null && clicked.equals(top);

    if (!inTop) {
      // 背包侧：只放行 Shift+左键（存入），其余照常（整理背包是玩家自由）
      if (event.getClick() == ClickType.SHIFT_LEFT && event.getSlot() >= 0) {
        event.setCancelled(true);
        depositFromInventory(player, session, event.getSlot());
        return true;
      }
      if (event.getClick() == ClickType.DOUBLE_CLICK) {
        // 双击会把光标物品与界面里的同类物品合并，会碰到上半区
        event.setCancelled(true);
      }
      return true;
    }

    event.setCancelled(true);
    int slot = event.getRawSlot();

    if (slot == CLOSE_SLOT) {
      close(player, session);
      return true;
    }
    if (slot == CONFIRM_SLOT) {
      confirm(player, session);
      return true;
    }
    if (slot < 0 || slot >= DEPOSIT_SLOTS) {
      return true;
    }

    ItemStack cursor = event.getCursor();
    if (cursor != null && !cursor.getType().isAir()) {
      int amount = event.isRightClick() ? 1 : cursor.getAmount();
      depositFromCursor(player, session, event, amount);
      return true;
    }

    // 空格子 + 空光标：什么都不做
    if (slot >= session.size()) {
      return true;
    }
    int take = event.isRightClick() ? 1 : 0;
    withdraw(player, session, slot, take);
    return true;
  }

  /** 把光标上的物品放进界面（左键整摞 / 右键 1 个） */
  private void depositFromCursor(Player player, SellSession session, InventoryClickEvent event,
      int amount) {
    ItemStack cursor = event.getCursor();
    if (cursor == null || cursor.getType().isAir()) {
      return;
    }
    SellEntry entry = entries.find(cursor);
    if (entry == null && !JSellMain.CONF.allowUnsellableInGui()) {
      player.sendMessage(i18n.tr("jsell.gui.reject_unsellable"));
      return;
    }
    int take = Math.min(Math.max(1, amount), cursor.getAmount());
    if (!hasRoom(session, cursorsRoomNeed(cursor, take))) {
      player.sendMessage(i18n.tr("jsell.gui.full", JSellMain.CONF.maxSlots()));
      return;
    }
    ItemStack part = cursor.clone();
    part.setAmount(take);
    session.add(part);

    int left = cursor.getAmount() - take;
    final ItemStack rest = left <= 0 ? null : cursor.clone();
    if (rest != null) {
      rest.setAmount(left);
    }
    // 事件被取消后，服务端会把光标还原成事件前的样子，所以光标必须下一 tick 再改
    Bukkit.getScheduler().runTask(Joyous.plugin, () -> {
      if (player.isOnline()) {
        player.setItemOnCursor(rest);
      }
    });
    afterChange(player, session);
  }

  /** Shift+左键背包物品：整摞存入 */
  private void depositFromInventory(Player player, SellSession session, int slot) {
    ItemStack stack = player.getInventory().getItem(slot);
    if (stack == null || stack.getType().isAir()) {
      return;
    }
    SellEntry entry = entries.find(stack);
    if (entry == null && !JSellMain.CONF.allowUnsellableInGui()) {
      player.sendMessage(i18n.tr("jsell.gui.reject_unsellable"));
      return;
    }
    if (!hasRoom(session, cursorsRoomNeed(stack, stack.getAmount()))) {
      player.sendMessage(i18n.tr("jsell.gui.full", JSellMain.CONF.maxSlots()));
      return;
    }
    session.add(stack.clone());
    player.getInventory().setItem(slot, null);
    afterChange(player, session);
  }

  /** 取回：左键整格 / 右键 1 个（放回背包，放不下掉在脚下） */
  private void withdraw(Player player, SellSession session, int index, int amount) {
    ItemStack taken = session.take(index, amount);
    if (taken == null) {
      return;
    }
    SellSessionManager.giveOrDrop(player, List.of(taken));
    afterChange(player, session);
  }

  /** 关闭（= 取消）：把东西还给他 */
  public void close(Player player, SellSession session) {
    session.closingByPlugin(true);
    player.closeInventory();
    sessions.cancel(player, true);
  }

  /** 确认出售 */
  private void confirm(Player player, SellSession session) {
    if (session.settled()) {
      return;
    }
    double amount = service.sellGui(player, session);
    if (amount > 0.0) {
      session.closingByPlugin(true);
      // 会话已经空了（内容交给事务了），从活跃表里摘掉，下次打开才是全新的
      sessions.cancel(player, false);
      player.closeInventory();
    } else {
      // 没卖成（例如全是不可售物品）：重画一遍，别让按钮停在「已点击」的状态
      repaint(player);
    }
  }

  // ──────────────────────────────────────────────
  // 变更后：整理 + 落库 + 防抖刷新
  // ──────────────────────────────────────────────

  /**
   * 放入/取出之后的统一收尾
   * <ol>
   *   <li>整理（同种物品合并、按价值从高到低排好）；</li>
   *   <li>立刻写库（断线/崩服都不丢）；</li>
   *   <li>全量重绘（物品格变了），并<b>防抖</b>地刷新总价按钮。</li>
   * </ol>
   */
  private void afterChange(Player player, SellSession session) {
    tidy(session);
    sessions.persist(session);
    repaint(player);
    scheduleSummaryRefresh(player, session);
  }

  /** 整理：合并同种可堆叠物品，然后按「价值高 → 数量多 → 名称」排序 */
  private void tidy(SellSession session) {
    List<ItemStack> sorted = new ArrayList<>(session.items());
    // 合并：同类型 + 可堆叠 + 模板命中结果相同（避免把「可售」和「不可售」的同类物品糊在一起）
    List<ItemStack> merged = new ArrayList<>();
    outer:
    for (ItemStack item : sorted) {
      for (ItemStack exist : merged) {
        if (exist.isSimilar(item) && exist.getAmount() < exist.getMaxStackSize()) {
          int room = exist.getMaxStackSize() - exist.getAmount();
          int move = Math.min(room, item.getAmount());
          exist.setAmount(exist.getAmount() + move);
          if (move < item.getAmount()) {
            ItemStack rest = item.clone();
            rest.setAmount(item.getAmount() - move);
            merged.add(rest);
          }
          continue outer;
        }
      }
      merged.add(item);
    }
    merged.sort(Comparator
        .comparingDouble((ItemStack item) -> -service.stackValue(item))
        .thenComparing(item -> item.getType().name())
        .thenComparingInt(ItemStack::getAmount));

    session.reset(merged);
  }

  /**
   * 防抖刷新总价
   * <p>
   * 连点放入时每一下都排一个任务没有意义，所以同一个玩家只会有一个待执行任务；
   * 真正执行时再确认「界面还开着、会话还是同一个」。
   */
  private void scheduleSummaryRefresh(Player player, SellSession session) {
    UUID id = player.getUniqueId();
    if (pendingRefresh.containsKey(id)) {
      return;
    }
    long delay = JSellMain.CONF.debounceTicks();
    int task = Bukkit.getScheduler().runTaskLater(Joyous.plugin, () -> {
      pendingRefresh.remove(id);
      SellSession current = sessions.get(player);
      Holder holder = holderOf(player.getOpenInventory().getTopInventory());
      if (current != session || holder == null || holder.kind() != Kind.SELL) {
        return;
      }
      paintSummary(player, player.getOpenInventory().getTopInventory(), current);
      player.updateInventory();
    }, delay).getTaskId();
    pendingRefresh.put(id, task);
  }

  /** 插件停用/玩家退出时取消挂起的刷新 */
  public void cancelPendingUpdates() {
    for (int task : pendingRefresh.values()) {
      Bukkit.getScheduler().cancelTask(task);
    }
    pendingRefresh.clear();
  }

  public void cancelPendingUpdate(Player player) {
    Integer task = pendingRefresh.remove(player.getUniqueId());
    if (task != null) {
      Bukkit.getScheduler().cancelTask(task);
    }
  }

  // ──────────────────────────────────────────────
  // 工具
  // ──────────────────────────────────────────────

  /** 这一摞需要占几格（按最大堆叠拆分后的格数） */
  private static int cursorsRoomNeed(ItemStack item, int amount) {
    int max = Math.max(1, item.getMaxStackSize());
    return (amount + max - 1) / max;
  }

  /** 会话里是否还放得下这么多格（简单保守：只算格数，不做同种合并腾挪） */
  private static boolean hasRoom(SellSession session, int need) {
    return session.size() + need <= JSellMain.CONF.maxSlots();
  }

  /** 构造一个按钮物品 */
  public static ItemStack button(Material material, String name, List<String> lore) {
    ItemStack item = new ItemStack(material);
    ItemMeta meta = item.getItemMeta();
    if (meta != null) {
      meta.displayName(text(null, name));
      if (lore != null && !lore.isEmpty()) {
        List<Component> lines = new ArrayList<>(lore.size());
        for (String line : lore) {
          lines.add(text(null, line));
        }
        meta.lore(lines);
      }
      item.setItemMeta(meta);
    }
    return item;
  }

  /** 界面里的弱占位（玻璃板），避免快捷移动把物品塞进无主空槽 */
  public static ItemStack filler() {
    ItemStack item = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
    ItemMeta meta = item.getItemMeta();
    if (meta != null) {
      meta.displayName(Component.empty().decoration(TextDecoration.ITALIC, false));
      item.setItemMeta(meta);
    }
    return item;
  }

  /** 物品原名（没有自定义名时用类型名的可读形式） */
  static String prettyName(ItemStack item) {
    String name = item.getType().name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
    return name;
  }

  /**
   * 文本 → Component
   * <p>
   * 物品名/Lore 的原版默认样式是<b>斜体</b>，而 {@code deserialize} 出来的装饰是 NOT_SET（＝继承＝开启），
   * 所以这里把未显式设置的装饰显式关掉，否则每个按钮都会是斜体。
   */
  public static Component text(@Nullable Player player, String raw) {
    String parsed = raw == null ? "" : raw;
    if (player != null) {
      parsed = i18n.getPHparsed(player, parsed);
    }
    return legacy(com.github.streackmc.StreackLib.utils.MCColor.parse(parsed));
  }

  /** 带 {@code §} 颜色码的文本 → Component（装饰已重置） */
  public static Component legacy(String colorized) {
    Component base = LegacyComponentSerializer.legacySection()
        .deserialize(colorized == null ? "" : colorized);
    return base.style(style -> {
      for (TextDecoration decoration : TextDecoration.values()) {
        if (base.style().decoration(decoration) == TextDecoration.State.NOT_SET) {
          style.decoration(decoration, false);
        }
      }
    });
  }
}
