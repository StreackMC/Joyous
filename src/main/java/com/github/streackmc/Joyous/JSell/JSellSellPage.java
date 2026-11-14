package com.github.streackmc.Joyous.JSell;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nullable;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import com.github.streackmc.Joyous.Joyous;
import com.github.streackmc.Joyous.utils.i18n;
import com.github.streackmc.Joyous.utils.interact.DetachReasonEnum;
import com.github.streackmc.Joyous.utils.interact.IPageContext;
import com.github.streackmc.Joyous.utils.interact.PageStateEnum;
import com.github.streackmc.Joyous.utils.interact.chestui.AbstractGuiPage;
import com.github.streackmc.Joyous.utils.interact.chestui.Canvas;
import com.github.streackmc.Joyous.utils.interact.chestui.Click;
import com.github.streackmc.Joyous.utils.interact.chestui.ClickPolicy;
import com.github.streackmc.Joyous.utils.interact.chestui.InteractContainerEnum;

import net.kyori.adventure.text.Component;

/**
 * 玩家的出售界面（6 行 × 9 列）
 * <p>
 * 布局：前 5 行（0-44）是放入区，最后一行是操作栏——首格关闭、末格确认出售、中间显示结算预览。
 *
 * <h2>两条不变量</h2>
 * <ul>
 *   <li><b>界面上的物品永远是副本</b>：真身在 {@link SellSession#items()} 里。所以给展示物品追加
 *       「售出价值」Lore、把不可售物品改名成「不接受此物品」都不会污染真正被卖掉的东西；</li>
 *   <li><b>托管内容每次变动都立刻落库</b>：这样断线、崩服都不会让界面里的东西凭空消失。</li>
 * </ul>
 *
 * <h2>不可保留</h2>
 * {@link #keepable()} 为 {@code false}：这个界面的「关闭」本身就是业务动作——把托管的东西原样还回去。
 * 若允许它被挂起（{@code INSTANT}），玩家会看到「界面没了但东西也没了」，
 * 所以仲裁器会把它降级成 {@code REPLACE}，终止时走 {@link #onDetach} 的退款路径。
 *
 * <h2>为什么不再需要 closingByPlugin</h2>
 * 旧实现用一个布尔量区分「插件自己关的」与「玩家关的」。现在两者的区别只剩一件事：
 * 结算是否已经完成（{@link SellSession#settled()}）。结算成功的会话已经从活跃表里摘掉，
 * 终止时再调一次 {@code cancel} 是空操作，所以不需要任何标记。
 *
 * @author Neonai
 * @since 0.8.0
 */
final class JSellSellPage extends AbstractGuiPage {

  static final String KEY = "jsell.sell";

  /** 界面总行数 */
  static final int ROWS = 6;
  /** 放入区最后一格的序号（不含） */
  static final int DEPOSIT_SLOTS = 45;
  /** 关闭按钮 */
  static final int CLOSE_SLOT = 45;
  /** 结算预览 */
  static final int INFO_SLOT = 49;
  /** 确认出售按钮 */
  static final int CONFIRM_SLOT = 53;

  private final SellEntryStore entries;
  private final SellSessionManager sessions;
  private final SellService service;
  private final SellSession session;

  /** 防抖：本页只服务一个玩家，一个待执行任务即可 */
  private int pendingRefresh = -1;

  JSellSellPage(SellEntryStore entries, SellSessionManager sessions,
      SellService service, SellSession session) {
    this.entries = entries;
    this.sessions = sessions;
    this.service = service;
    this.session = session;
  }

  /** 重画当前页（已经开着同一页时由 {@link JSellGui} 调用，避免重开导致误判成取消） */
  void refresh(IPageContext ctx) {
    repaint(ctx);
  }

  @Override
  public String key() {
    return KEY;
  }

  /** 出售会话是每个玩家独立的一份，用空 purpose 即可（同一玩家不会同时有两个） */
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
    return ROWS;
  }

  @Override
  public Component title(Player viewer) {
    return Canvas.title(viewer, i18n.tr("jsell.gui.title"));
  }

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
    if (player == null) {
      return;
    }
    paintItems(player, canvas);
    paintBar(canvas);
    paintSummary(player, canvas, session);
  }

  /** 画放入区（0-44）：有东西的画物品副本，空格按配置铺玻璃板 */
  private void paintItems(Player player, Canvas canvas) {
    List<ItemStack> items = session.items();
    int depositSlots = JSellMain.CONF.maxSlots();
    // 额度提示要查库：同一次绘制里同一件条目只查一次（一整包物品通常也就一两条条目）
    Map<UUID, SellService.Quota> quotas = new HashMap<>();

    for (int i = 0; i < DEPOSIT_SLOTS; i++) {
      if (i < items.size() && i < depositSlots) {
        canvas.set(i, displayOf(player, items.get(i), quotas));
      } else if (i >= depositSlots) {
        canvas.set(i, JSellGui.filler());
      } else {
        // 这一格原来是物品、现在被取走了：必须显式清空
        canvas.set(i, null);
      }
    }
  }

  /** 画操作栏 */
  private void paintBar(Canvas canvas) {
    canvas.set(CLOSE_SLOT, JSellGui.button(Material.BARRIER,
        i18n.tr("jsell.gui.close"), List.of(i18n.tr("jsell.gui.close_lore"))));
    for (int slot = CLOSE_SLOT + 1; slot < ROWS * 9; slot++) {
      if (slot != INFO_SLOT && slot != CONFIRM_SLOT) {
        canvas.set(slot, JSellGui.filler());
      }
    }
  }

  /**
   * 绘制「结算预览」与「确认出售」两个按钮
   * <p>
   * 只有这两格会随总价变化，所以防抖刷新只碰它们——放入/取出时的重绘成本几乎为零。
   */
  private void paintSummary(Player player, Canvas canvas, SellSession session) {
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
    canvas.set(INFO_SLOT, JSellGui.button(Material.PAPER, i18n.tr("jsell.gui.info_name"), infoLore));

    boolean ready = total > 0.0;
    ItemStack confirm = JSellGui.button(ready ? Material.LIME_CONCRETE : Material.GRAY_CONCRETE,
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
    canvas.set(CONFIRM_SLOT, confirm);
  }

  /**
   * 生成「展示用」物品
   * <p>
   * 一定是 {@code clone()}：追加 Lore、给不可售物品改名都只发生在这份副本上。
   */
  private ItemStack displayOf(Player player, ItemStack real, Map<UUID, SellService.Quota> quotas) {
    ItemStack display = real.clone();
    SellEntry entry = entries.find(real);
    ItemMeta meta = display.getItemMeta();
    if (meta == null) {
      return display;
    }
    List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());

    if (entry == null) {
      // 不可售：名字换成醒目的提示，原名字放进 Lore 里
      Component original = meta.displayName() == null
          ? JSellGui.legacy("§f" + JSellGui.prettyName(real))
          : meta.displayName();
      meta.displayName(JSellGui.legacy(i18n.tr("jsell.gui.unsellable_name")));
      lore.add(Component.empty());
      lore.add(JSellGui.legacy(i18n.tr("jsell.gui.unsellable_original")).append(original));
      for (String line : i18n.tr("jsell.gui.unsellable_lore").split("\n")) {
        lore.add(JSellGui.legacy(line));
      }
    } else if (JSellMain.CONF.appendLore()) {
      double unitValue = entry.unitValue();
      double stackValue = SellService.round(unitValue * real.getAmount());
      String unit = JSellMain.CONF.unitName();
      lore.add(Component.empty());
      lore.add(JSellGui.legacy(i18n.tr("jsell.gui.value_line",
          SellService.display(unitValue), unit)));
      if (real.getAmount() > 1) {
        lore.add(JSellGui.legacy(i18n.tr("jsell.gui.value_stack",
            real.getAmount(), SellService.display(stackValue), unit)));
      }
      String limit = limitLine(player, entry, quotas);
      if (limit != null) {
        lore.add(JSellGui.legacy(limit));
      }
    }
    meta.lore(lore);
    display.setItemMeta(meta);
    return display;
  }

  /**
   * 玩家视角的限购提示
   * <p>
   * 「每 86400 秒最多 64 个」对玩家毫无意义，他真正想知道的是「还能卖多少、什么时候重置」。
   * 时间到分钟为止、超过一天写「约 N 天」，避免秒数跳动让 Lore 看起来一直在变。
   */
  @Nullable
  private String limitLine(Player player, SellEntry entry, Map<UUID, SellService.Quota> quotas) {
    if (entry.limitCount() <= 0) {
      return null;
    }
    SellService.Quota quota = quotas.computeIfAbsent(entry.id(),
        ignored -> service.quotaFor(player.getUniqueId(), entry));
    String unit = JSellMain.CONF.unitName();
    boolean lifetime = entry.limitSeconds() <= 0;
    long reset = quota.resetInMillis();

    if (quota.exhausted()) {
      if (lifetime || reset <= 0L) {
        return i18n.tr("jsell.gui.limit_empty_lifetime");
      }
      return i18n.tr("jsell.gui.limit_empty_reset", SellService.formatRemaining(reset));
    }
    if (lifetime) {
      return i18n.tr("jsell.gui.limit_lifetime", quota.remaining(), unit);
    }
    if (reset > 0L) {
      return i18n.tr("jsell.gui.limit_reset",
          quota.remaining(), unit, SellService.formatRemaining(reset));
    }
    return i18n.tr("jsell.gui.limit_remaining", quota.remaining(), unit);
  }

  // ──────────────────────────────────────────────
  // 点击
  // ──────────────────────────────────────────────

  /**
   * 上半区只认三件事：<b>存入</b>（空位 + 光标有东西）、<b>取回</b>（点已有物品的格子）、<b>按钮</b>。
   * 其余（右键拆分、数字键交换、双击收拢、Q 丢弃）一律由策略拦掉。
   */
  @Override
  public Verdict onClick(IPageContext ctx, Click click) {
    Player player = click.player();

    if (!click.top()) {
      // 背包侧：Shift+左键 = 整摞存入（策略已取消原版搬运，这里只做登记）
      if (click.click() == ClickType.SHIFT_LEFT) {
        depositFromInventory(ctx, player, click.slot());
      }
      return Verdict.HANDLED;
    }

    int slot = click.slot();
    if (slot == CLOSE_SLOT) {
      ctx.close();
      return Verdict.HANDLED;
    }
    if (slot == CONFIRM_SLOT) {
      confirm(ctx, player);
      return Verdict.HANDLED;
    }
    if (slot < 0 || slot >= DEPOSIT_SLOTS) {
      return Verdict.HANDLED;
    }

    if (click.holding()) {
      int amount = click.right() ? 1 : click.cursor().getAmount();
      depositFromCursor(ctx, player, click, amount);
      return Verdict.HANDLED;
    }

    // 空格子 + 空光标：什么都不做
    if (slot >= session.size()) {
      return Verdict.HANDLED;
    }
    withdraw(ctx, player, slot, click.right() ? 1 : 0);
    return Verdict.HANDLED;
  }

  /** 把光标上的物品放进界面（左键整摞 / 右键 1 个） */
  private void depositFromCursor(IPageContext ctx, Player player, Click click, int amount) {
    ItemStack cursor = click.cursor();
    if (cursor == null || cursor.getType().isAir()) {
      return;
    }
    SellEntry entry = entries.find(cursor);
    if (entry == null && !JSellMain.CONF.allowUnsellableInGui()) {
      player.sendMessage(i18n.tr("jsell.gui.reject_unsellable"));
      return;
    }
    int take = Math.min(Math.max(1, amount), cursor.getAmount());
    if (!hasRoom(cursorsRoomNeed(cursor, take))) {
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
    afterChange(ctx, player);
  }

  /** Shift+左键背包物品：整摞存入 */
  private void depositFromInventory(IPageContext ctx, Player player, int slot) {
    ItemStack stack = player.getInventory().getItem(slot);
    if (stack == null || stack.getType().isAir()) {
      return;
    }
    SellEntry entry = entries.find(stack);
    if (entry == null && !JSellMain.CONF.allowUnsellableInGui()) {
      player.sendMessage(i18n.tr("jsell.gui.reject_unsellable"));
      return;
    }
    if (!hasRoom(cursorsRoomNeed(stack, stack.getAmount()))) {
      player.sendMessage(i18n.tr("jsell.gui.full", JSellMain.CONF.maxSlots()));
      return;
    }
    session.add(stack.clone());
    player.getInventory().setItem(slot, null);
    afterChange(ctx, player);
  }

  /** 取回：左键整格 / 右键 1 个（放回背包，放不下掉在脚下） */
  private void withdraw(IPageContext ctx, Player player, int index, int amount) {
    ItemStack taken = session.take(index, amount);
    if (taken == null) {
      return;
    }
    SellSessionManager.giveOrDrop(player, List.of(taken));
    afterChange(ctx, player);
  }

  /** 确认出售 */
  private void confirm(IPageContext ctx, Player player) {
    if (session.settled()) {
      return;
    }
    double amount = service.sellGui(player, session);
    if (amount > 0.0) {
      // 会话内容已经交给事务，这里只需关掉界面；终止时不会再退款（settled 后取消是空操作）
      ctx.close();
      return;
    }
    // 没卖成（例如全是不可售物品）：重画一遍，别让按钮停在「已点击」的状态
    repaint(ctx);
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
  private void afterChange(IPageContext ctx, Player player) {
    tidy();
    sessions.persist(session);
    repaint(ctx);
    scheduleSummaryRefresh(ctx, player);
  }

  /** 整理：合并同种可堆叠物品，然后按「价值高 → 数量多 → 名称」排序 */
  private void tidy() {
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
   * 连点放入时每一下都排一个任务没有意义，所以本页只会有一个待执行任务；
   * 真正执行时再确认「页面还在显示、会话还是同一个」。
   */
  private void scheduleSummaryRefresh(IPageContext ctx, Player player) {
    if (pendingRefresh != -1) {
      return;
    }
    long delay = JSellMain.CONF.debounceTicks();
    pendingRefresh = Bukkit.getScheduler().runTaskLater(Joyous.plugin, () -> {
      pendingRefresh = -1;
      if (ctx.state() != PageStateEnum.VISIBLE) {
        return;
      }
      var view = inventory();
      if (view == null) {
        return;
      }
      paintSummary(player, new Canvas(view), session);
    }, delay).getTaskId();
  }

  private void cancelPendingRefresh() {
    if (pendingRefresh != -1) {
      Bukkit.getScheduler().cancelTask(pendingRefresh);
      pendingRefresh = -1;
    }
  }

  // ──────────────────────────────────────────────
  // 生命周期
  // ──────────────────────────────────────────────

  /**
   * 不可保留：关闭界面本身就是业务动作（把托管物原样还回去）
   * <p>
   * 如果允许它被挂起，玩家会看到「界面没了但东西也没了」——那是最糟的体验。
   */
  @Override
  public boolean keepable() {
    return false;
  }

  /**
   * 终止 = 取消（除结算成功之外）
   * <p>
   * 结算成功后会话已经被 {@link SellService} 接管并从活跃表摘掉，这里再调 {@code cancel}
   * 是空操作，所以不需要任何「是不是我关的」标记。
   * <p>
   * <b>只在「玩家关掉 / 被别的页面顶掉」时退款</b>：断线与关服走的是另一条路——
   * 托管内容已经在 {@code jsell_escrow} 里，下次上线由 {@code deliverPending} 交付。
   * 如果在关服阶段往玩家背包里塞东西，可能跟玩家数据的保存时序打架，反而丢件。
   */
  @Override
  public void onDetach(IPageContext ctx, DetachReasonEnum why) {
    super.onDetach(ctx, why);
    cancelPendingRefresh();
    if (why != DetachReasonEnum.CLOSED && why != DetachReasonEnum.REPLACED) {
      return;
    }
    Player player = ctx.player();
    if (player == null || session.settled()) {
      return;
    }
    sessions.cancel(player, why == DetachReasonEnum.CLOSED);
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
  private boolean hasRoom(int need) {
    return session.size() + need <= JSellMain.CONF.maxSlots();
  }
}
