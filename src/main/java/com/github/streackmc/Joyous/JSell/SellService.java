package com.github.streackmc.Joyous.JSell;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nullable;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import com.github.streackmc.Joyous.i18n;
import com.github.streackmc.Joyous.jlogger;

/**
 * 出售的核心逻辑：计价、限额、结算
 *
 * <h3>结算协议（为什么这么绕）</h3>
 * 「物品消失」与「钱到账」之间必然存在一个非原子窗口，因为给钱是执行一条外部命令
 * （Essentials、CMI、自研经济插件……），它不可能回滚。于是把整单拆成几个<b>有明确语义</b>的阶段，
 * 每个阶段只做一件事，并且每次状态变更都先落库：
 * <pre>
 *   PREPARED   只写了日志        → 崩服：整单退回（什么都没发生）
 *   COMMITTED  接管了物品        → 崩服：整单退回（钱还没给、退款还没交）
 *   REFUNDED   不可售部分已交还  → 崩服：按已售处理（钱可能已给）
 *   PAID       命令可能已执行    → 崩服：按已售处理，留给管理员核查
 * </pre>
 * 这样<b>物品维度</b>是确定的：要么原样回来，要么已经卖掉——没有任何路径能让同一件东西既在玩家手上
 * 又还在账上。唯一不确定的是「钱到底给没给」，那属于外部副作用，用事务号 + 管理员核查兜底。
 * <p>
 * 退款（不可售 / 超出限购的部分）也在事务里被记录，所以「崩服 → 重启」之后它不会凭空消失。
 *
 * @author kdxiaoyi
 * @since 0.8.0
 */
public final class SellService {

  private final JSellMain module;
  private final SellEntryStore entries;
  private final SellStore store;
  private final SellSessionManager sessions;

  public SellService(JSellMain module, SellEntryStore entries, SellStore store,
      SellSessionManager sessions) {
    this.module = module;
    this.entries = entries;
    this.store = store;
    this.sessions = sessions;
  }

  // ──────────────────────────────────────────────
  // 计价
  // ──────────────────────────────────────────────

  /** 命中该物品的可售条目；不可售返回 null */
  @Nullable
  public SellEntry entryOf(@Nullable ItemStack item) {
    return entries.find(item);
  }

  /** 单件价值（一个物品值多少） */
  public double unitValue(@Nullable ItemStack item) {
    SellEntry entry = entries.find(item);
    return entry == null ? 0.0 : entry.unitValue();
  }

  /** 这一摞的总价值（单价 × 数量） */
  public double stackValue(@Nullable ItemStack item) {
    if (item == null || item.getType().isAir()) {
      return 0.0;
    }
    SellEntry entry = entries.find(item);
    return entry == null ? 0.0 : round(entry.unitValue() * item.getAmount());
  }

  /** 一组物品的总价值 */
  public double totalValue(@Nullable Collection<ItemStack> items) {
    if (items == null) {
      return 0.0;
    }
    double total = 0.0;
    for (ItemStack item : items) {
      total += stackValue(item);
    }
    return round(total);
  }

  /** 金额 → 命令行里用的纯数字文本（不带千分位、不带货币符号） */
  public static String amountText(double amount) {
    int decimals = JSellMain.CONF.decimals();
    BigDecimal value = BigDecimal.valueOf(amount).setScale(decimals, RoundingMode.HALF_UP);
    return value.stripTrailingZeros().toPlainString();
  }

  /** 金额 → 界面上用的展示文本（带千分位） */
  public static String display(double amount) {
    int decimals = Math.max(0, Math.min(6, JSellMain.CONF.decimals()));
    StringBuilder pattern = new StringBuilder("#,##0");
    if (decimals > 0) {
      pattern.append('.');
      pattern.append("0".repeat(decimals));
    }
    return new DecimalFormat(pattern.toString()).format(amount);
  }

  /** 统一保留 {@code decimals} 位，避免 0.1+0.2 之类的浮点尾巴进入经济系统 */
  public static double round(double amount) {
    return BigDecimal.valueOf(amount)
        .setScale(Math.max(0, Math.min(6, JSellMain.CONF.decimals())), RoundingMode.HALF_UP)
        .doubleValue();
  }

  // ──────────────────────────────────────────────
  // 计划
  // ──────────────────────────────────────────────

  /** 一件「确定要卖」的物品，连带它命中的条目（逐件命令要用） */
  public record Sale(ItemStack item, SellEntry entry) {
  }

  /** 一次结算的完整计划 */
  public static final class Plan {
    /** 会被卖掉的部分 */
    public final List<Sale> sold = new ArrayList<>();
    /** 不可售 / 超出限购，需要原样还给玩家的部分 */
    public final List<ItemStack> refunds = new ArrayList<>();
    /** 每个条目卖出的数量（用于限额流水与「卖出后执行命令」） */
    public final Map<SellEntry, Integer> units = new LinkedHashMap<>();
    /** 本次结算金额 */
    public double amount;

    public boolean sellable() {
      return !sold.isEmpty() && amount > 0;
    }

    /** 条目数（不是件数） */
    public int entryCount() {
      return units.size();
    }

    /** 总件数（按堆叠数量算） */
    public int units() {
      int total = 0;
      for (int value : units.values()) {
        total += value;
      }
      return total;
    }
  }

  /**
   * 计算「这些物品里哪些能卖、能卖多少」
   * <p>
   * 限购按<b>条目</b>算，同一批物品里同一路径的条目共享一份剩余额度，先到先得（按界面顺序）。
   *
   * @param player 出售者（限购按玩家统计）
   * @param stacks 待结算物品（不会被修改）
   */
  public Plan plan(UUID player, @Nullable List<? extends ItemStack> stacks) {
    Plan plan = new Plan();
    if (stacks == null || stacks.isEmpty()) {
      return plan;
    }
    Map<SellEntry, Integer> quota = new HashMap<>();
    for (ItemStack stack : stacks) {
      if (stack == null || stack.getType().isAir() || stack.getAmount() <= 0) {
        continue;
      }
      SellEntry entry = entries.find(stack);
      if (entry == null) {
        plan.refunds.add(stack.clone());
        continue;
      }
      int allowed = quota.computeIfAbsent(entry, e -> remainingQuota(player, e));
      if (allowed <= 0) {
        plan.refunds.add(stack.clone());
        continue;
      }
      int take = Math.min(allowed, stack.getAmount());
      ItemStack part = stack.clone();
      part.setAmount(take);
      plan.sold.add(new Sale(part, entry));
      plan.units.merge(entry, take, Integer::sum);
      plan.amount += entry.unitValue() * take;
      if (take < stack.getAmount()) {
        ItemStack rest = stack.clone();
        rest.setAmount(stack.getAmount() - take);
        plan.refunds.add(rest);
      }
      quota.put(entry, allowed - take);
    }
    plan.amount = round(plan.amount);
    return plan;
  }

  /**
   * 该玩家在这个条目上还剩多少额度
   * <p>
   * {@code limitCount <= 0} 表示不限量；{@code limitSeconds <= 0} 表示「终身累计」，
   * 否则只看最近一个窗口。数据库出错时保守地返回 0（本次卖不掉），而不是放行——
   * 宁可少卖一次，也不要在存档不可用时把限购打穿。
   */
  private int remainingQuota(UUID player, SellEntry entry) {
    if (entry.limitCount() <= 0) {
      return Integer.MAX_VALUE;
    }
    long since = entry.limitSeconds() > 0
        ? System.currentTimeMillis() - entry.limitSeconds() * 1000L
        : 0L;
    try {
      long used = store.usageSum(player, entry.id(), since);
      long remaining = entry.limitCount() - used;
      return (int) Math.max(0L, Math.min(Integer.MAX_VALUE, remaining));
    } catch (SellStore.SellStoreException e) {
      module.reportStorageError("查询限购额度", e);
      return 0;
    }
  }

  // ──────────────────────────────────────────────
  // 结算：界面
  // ──────────────────────────────────────────────

  /**
   * 结算界面里的全部内容
   * <p>
   * 不可售与超出限购的部分会立刻退回玩家（放不下就掉在脚下），其余走事务流程。
   * 结算完成后会话被清空，界面由调用方负责关掉。
   *
   * @return 实际卖出的金额；0 表示什么都没卖（物品仍在会话里，调用方可以继续编辑）
   */
  public double sellGui(Player player, SellSession session) {
    if (session.settled()) {
      return 0.0;
    }
    Plan plan = plan(player.getUniqueId(), session.snapshot());
    if (plan.sold.isEmpty()) {
      player.sendMessage(i18n.tr("jsell.sell.nothing"));
      return 0.0;
    }
    if (plan.amount <= 0.0) {
      player.sendMessage(i18n.tr("jsell.sell.zero_value"));
      return 0.0;
    }

    int unsellable = plan.refunds.size();

    session.settled(true);
    try {
      commit(player, plan.sold, plan.refunds, plan.units, plan.amount, true);
    } catch (SellStore.SellStoreException e) {
      // 事务没建立起来：什么都不该变。会话内容仍在，界面重画后物品还在，
      // 但「托管行」可能已被删掉一半，所以再落一次库把状态补回去。
      session.settled(false);
      sessions.persist(session);
      module.reportStorageError("写出售事务", e);
      player.sendMessage(i18n.tr("jsell.sell.failed"));
      return 0.0;
    }
    // 成功：物品已被事务接管，界面内容清空（退款部分已经由事务流程交还）
    session.drain();

    player.sendMessage(i18n.tr("jsell.sell.success",
        display(plan.amount), JSellMain.CONF.unitName(), plan.units(), plan.entryCount()));
    if (unsellable > 0) {
      player.sendMessage(i18n.tr("jsell.sell.refunded", unsellable));
    }
    return plan.amount;
  }

  // ──────────────────────────────────────────────
  // 结算：背包
  // ──────────────────────────────────────────────

  /**
   * 卖背包里的指定槽位（主手 / inv / all 走这里）
   * <p>
   * 与界面路径的区别：不可售的东西<b>原地不动</b>（仍在背包里），所以不需要「退回」这一步；
   * 超出限购的部分也只扣掉能卖的那一截，剩下的留在原格。
   * <p>
   * ⚠️ 这里的「扣除物品」发生在事务建立之后、同一个 tick 内。若服务器恰好死在这几条语句之间，
   * 物品可能已经离开背包但事务还没落库——这是所有背包直售插件共同的窗口。
   * 走界面（先把物品交出来）才有完整的崩溃保护。
   *
   * @param slots 要尝试出售的槽位（越界会自动忽略）
   * @return 实际卖出的金额
   */
  public double sellSlots(Player player, int[] slots) {
    PlayerInventory inventory = player.getInventory();
    List<Sale> sold = new ArrayList<>();
    Map<SellEntry, Integer> units = new LinkedHashMap<>();
    Map<SellEntry, Integer> quota = new HashMap<>();
    List<Runnable> rollback = new ArrayList<>();
    double amount = 0.0;

    for (int slot : slots) {
      if (slot < 0 || slot >= inventory.getSize()) {
        continue;
      }
      ItemStack stack = inventory.getItem(slot);
      if (stack == null || stack.getType().isAir() || stack.getAmount() <= 0) {
        continue;
      }
      SellEntry entry = entries.find(stack);
      if (entry == null) {
        continue;
      }
      int allowed = quota.computeIfAbsent(entry, e -> remainingQuota(player.getUniqueId(), e));
      if (allowed <= 0) {
        continue;
      }
      int take = Math.min(allowed, stack.getAmount());
      ItemStack part = stack.clone();
      part.setAmount(take);
      sold.add(new Sale(part, entry));
      units.merge(entry, take, Integer::sum);
      amount += entry.unitValue() * take;
      quota.put(entry, allowed - take);

      // 记录如何回滚（事务建立失败时原样塞回去）
      final int targetSlot = slot;
      final int taken = take;
      final ItemStack before = stack.clone();
      rollback.add(() -> {
        ItemStack current = inventory.getItem(targetSlot);
        if (current == null || current.getType().isAir()) {
          inventory.setItem(targetSlot, before);
        } else {
          ItemStack merged = current.clone();
          merged.setAmount(current.getAmount() + taken);
          inventory.setItem(targetSlot, merged);
        }
      });
      if (take >= stack.getAmount()) {
        inventory.setItem(slot, null);
      } else {
        ItemStack rest = stack.clone();
        rest.setAmount(stack.getAmount() - take);
        inventory.setItem(slot, rest);
      }
    }

    if (sold.isEmpty()) {
      player.sendMessage(i18n.tr("jsell.sell.nothing"));
      return 0.0;
    }
    amount = round(amount);
    if (amount <= 0.0) {
      for (Runnable undo : rollback) {
        undo.run();
      }
      player.sendMessage(i18n.tr("jsell.sell.zero_value"));
      return 0.0;
    }
    player.updateInventory();

    try {
      commit(player, sold, List.of(), units, amount, false);
    } catch (SellStore.SellStoreException e) {
      for (Runnable undo : rollback) {
        undo.run();
      }
      player.updateInventory();
      module.reportStorageError("写出售事务", e);
      player.sendMessage(i18n.tr("jsell.sell.failed"));
      return 0.0;
    }

    player.sendMessage(i18n.tr("jsell.sell.success",
        display(amount), JSellMain.CONF.unitName(), units.values().stream().mapToInt(i -> i).sum(),
        units.size()));
    return amount;
  }

  /** 卖主手 */
  public double sellHand(Player player) {
    int slot = player.getInventory().getHeldItemSlot();
    return sellSlots(player, new int[] { slot });
  }

  /** 卖背包（不含快捷栏） */
  public double sellInventory(Player player) {
    int[] slots = new int[27];
    for (int i = 0; i < slots.length; i++) {
      slots[i] = 9 + i;
    }
    return sellSlots(player, slots);
  }

  /** 卖全部（背包 + 快捷栏 + 副手，不含穿在身上的装备） */
  public double sellAll(Player player) {
    int[] slots = new int[37];
    for (int i = 0; i < 36; i++) {
      slots[i] = i;
    }
    slots[36] = 40;
    return sellSlots(player, slots);
  }

  // ──────────────────────────────────────────────
  // 事务
  // ──────────────────────────────────────────────

  /**
   * 走完一整套结算协议，见类注释
   *
   * @param fromGui 是否来自界面（需要先把托管行删掉，物品所有权才转到事务上）
   */
  private void commit(Player player, List<Sale> sold, List<ItemStack> refunds,
      Map<SellEntry, Integer> units, double amount, boolean fromGui)
      throws SellStore.SellStoreException {

    if (!store.available()) {
      // 没有数据库：功能照常，但没有任何崩溃保护（管理员没配 dbprofile 时才会到这里）
      jlogger.warn("JSell 未配置数据库，本次结算没有事务保护（玩家 %s）", player.getName());
      if (fromGui) {
        // 界面内容已经在内存里被接管，直接执行
      }
      runCommands(player, sold, units, amount);
      return;
    }

    String txId = UUID.randomUUID().toString();
    String note = summarize(sold, amount);
    long now = System.currentTimeMillis();

    List<ItemStack> soldItems = new ArrayList<>(sold.size());
    for (Sale sale : sold) {
      soldItems.add(sale.item());
    }

    // ① 落日志：此时崩溃 → 整单退回，什么都没发生
    store.createTx(new SellStore.Tx(txId, player.getUniqueId(), SellStore.TX_PREPARED,
        amount, soldItems, refunds, note, now));

    // ② 接管物品：托管行删掉，物品只存在于事务里
    try {
      if (fromGui) {
        sessions.releaseEscrow(player.getUniqueId());
      }
      store.markStatus(txId, SellStore.TX_COMMITTED);
    } catch (SellStore.SellStoreException e) {
      // 没接管成功 → 撤销事务，物品还在托管/背包里
      try {
        store.deleteTx(txId);
      } catch (SellStore.SellStoreException ignored) {
        // 删不掉就留着，恢复流程会以托管为准，不会重复补发
      }
      throw e;
    }

    // ── 从这里开始已经「承诺出售」──────────────────────────
    // 任何异常都不能再往回退：物品已经算卖掉了，回退就等于复制。
    // 所以下面每一步都只记录、不上抛，事务停在哪个阶段就留给恢复流程/管理员。
    try {
      // ③ 交还不可售 / 超限的部分（已记录在事务里，崩了也能补）
      SellSessionManager.giveOrDrop(player, refunds);
      store.markStatus(txId, SellStore.TX_REFUNDED);

      // ④ 给钱 + 附加命令（dispatch 内部已吞异常，这里只是保险）
      runCommands(player, sold, units, amount);
      store.markStatus(txId, SellStore.TX_PAID);

      // ⑤ 记销量（限额统计）
      long at = System.currentTimeMillis();
      for (Map.Entry<SellEntry, Integer> e : units.entrySet()) {
        store.addUsage(player.getUniqueId(), e.getKey().id(), e.getValue(), at);
      }

      // ⑥ 收尾：日志可以删了
      store.deleteTx(txId);
    } catch (Exception e) {
      module.reportStorageError("完成出售事务", e);
      jlogger.severe("JSell 事务 %s（玩家 %s）未走完，物品按已售处理；若玩家没收到款项请人工核查",
          txId, player.getName());
      player.sendMessage(i18n.tr("jsell.sell.command_failed", txId));
    }
  }

  /** 事务备注：给人看的摘要（存在 payload 里，不参与任何判断） */
  private static String summarize(List<Sale> sold, double amount) {
    StringBuilder sb = new StringBuilder();
    sb.append("total=").append(amount).append(" items=").append(sold.size()).append(" [");
    int limit = Math.min(sold.size(), 12);
    for (int i = 0; i < limit; i++) {
      if (i > 0) {
        sb.append(", ");
      }
      Sale sale = sold.get(i);
      sb.append(sale.item().getAmount()).append("x").append(sale.item().getType().name());
    }
    if (sold.size() > limit) {
      sb.append(", …");
    }
    sb.append(']');
    return sb.toString();
  }

  /**
   * 执行「给钱」与「卖出后命令」
   * <p>
   * 命令都以控制台身份执行（玩家不该因为卖东西就获得执行任意命令的权限）。
   * 「逐件命令」会为每一件执行一次，所以管理员那边会看到「一次卖太多会卡」的警告；
   * 这里再兜一层上限（{@code command.per-item-limit}），超过就停下并记日志。
   */
  private void runCommands(Player player, List<Sale> sold, Map<SellEntry, Integer> units,
      double amount) {
    String name = player.getName();
    String uuid = player.getUniqueId().toString();
    int totalUnits = units.values().stream().mapToInt(Integer::intValue).sum();

    for (String raw : JSellMain.CONF.economyCommands()) {
      dispatch(substitute(raw, name, uuid, totalUnits, amount));
    }

    for (Map.Entry<SellEntry, Integer> e : units.entrySet()) {
      SellEntry entry = e.getKey();
      int count = e.getValue();
      double subtotal = round(entry.unitValue() * count);
      for (String raw : entry.commands()) {
        dispatch(substitute(raw, name, uuid, count, subtotal));
      }
    }

    int limit = JSellMain.CONF.perItemCommandLimit();
    if (limit <= 0) {
      return;
    }
    int executed = 0;
    for (Sale sale : sold) {
      SellEntry entry = sale.entry();
      if (entry.perItemCommands().isEmpty()) {
        continue;
      }
      for (String raw : entry.perItemCommands()) {
        if (executed >= limit) {
          jlogger.warn("JSell 单次结算的逐件命令超过上限 %s，剩余部分已跳过（玩家 %s）",
              limit, name);
          return;
        }
        double subtotal = round(entry.unitValue() * sale.item().getAmount());
        dispatch(substitute(raw, name, uuid, sale.item().getAmount(), subtotal));
        executed++;
      }
    }
  }

  /** 占位符替换：%player% / %uuid% / %count% / %amount% / %unit% */
  static String substitute(String raw, String player, String uuid, int count, double amount) {
    String result = raw.replace("%player%", player)
        .replace("%uuid%", uuid)
        .replace("%count%", String.valueOf(count))
        .replace("%amount%", amountText(amount))
        .replace("%unit%", JSellMain.CONF.unitName());
    // 兼容易经插件常见的写法
    return result.replace("{player}", player)
        .replace("{amount}", amountText(amount));
  }

  /** 以控制台身份执行；失败只记日志（不能因为一条命令写错就把整单卡住） */
  private static void dispatch(String command) {
    String cmd = command == null ? "" : command.trim();
    if (cmd.isEmpty()) {
      return;
    }
    if (cmd.startsWith("/")) {
      cmd = cmd.substring(1);
    }
    try {
      boolean ok = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
      if (!ok) {
        jlogger.warn("JSell 命令执行失败（可能不存在或参数不对）：%s", cmd);
      }
    } catch (Exception e) {
      jlogger.warn("JSell 命令执行异常：%s → %s", cmd, e.getLocalizedMessage());
    }
  }
}
