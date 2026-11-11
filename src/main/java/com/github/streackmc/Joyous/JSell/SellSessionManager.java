package com.github.streackmc.Joyous.JSell;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import com.github.streackmc.Joyous.utils.i18n;
import com.github.streackmc.Joyous.utils.jlogger;

/**
 * 出售会话的托管与恢复
 * <p>
 * 会话（{@link SellSession}）只在内存里，但它的<b>内容</b>每次变动都会同步写进
 * {@code jsell_escrow}，所以：
 * <ul>
 *   <li>玩家按 Esc/E 关闭界面 → 内容原样退回背包（放不下就掉在脚下），库里的记录删掉；</li>
 *   <li>玩家断线 → 同上（当作取消），不会出现「东西留在界面里人间蒸发」；</li>
 *   <li><b>服务器崩溃/被 kill</b> → 库里还留着记录，下次该玩家上线时原样交付并提示。</li>
 * </ul>
 *
 * <h3>交付顺序</h3>
 * 交付分两步：<b>先删库里的记录，再给物品</b>。反过来（先给后删）会在「两条语句之间崩服」时
 * 让同一件东西既在玩家背包里、又还在库里——那是复制，会直接破坏经济。选择这个顺序意味着
 * 同一个小窗口里宁可少给一次（有日志、可人工补），也绝不复制。
 *
 * @author kdxiaoyi
 * @since 0.8.0
 */
public final class SellSessionManager {

  private final JSellMain module;
  private final SellStore store;
  /** 正在进行的会话（玩家 → 会话） */
  private final Map<UUID, SellSession> active = new ConcurrentHashMap<>();
  /** 库里捡到、还没交付给玩家的托管内容（崩服恢复） */
  private final Map<UUID, List<ItemStack>> pending = new ConcurrentHashMap<>();

  public SellSessionManager(JSellMain module, SellStore store) {
    this.module = module;
    this.store = store;
  }

  // ──────────────────────────────────────────────
  // 启动恢复
  // ──────────────────────────────────────────────

  /**
   * 启动时把「上次没结算完」的状态捡回来
   * <p>
   * 两步：
   * <ol>
   *   <li>托管表里的东西 = 玩家放进界面后没来得及结算的物品 → 等他上线交付；</li>
   *   <li>事务表里 {@link SellStore#TX_PREPARED} / {@link SellStore#TX_COMMITTED} 的单子
   *       <b>什么都还没发生</b>（既没给钱也没交还退款），所以整单退回托管；
   *       {@link SellStore#TX_REFUNDED} / {@link SellStore#TX_PAID} 的单子按「已售出」处理，
   *       只记日志留给管理员核查（重复执行命令会变成刷钱）。</li>
   * </ol>
   */
  public void recoverPending() {
    if (store == null || !store.available()) {
      return;
    }
    try {
      Map<UUID, List<ItemStack>> escrows = store.loadEscrows();
      pending.putAll(escrows);
      if (!escrows.isEmpty()) {
        jlogger.info("JSell 发现 %s 份未交付的托管物品，将在对应玩家上线时归还", escrows.size());
      }
    } catch (SellStore.SellStoreException e) {
      module.reportStorageError("读取托管记录", e);
    }

    try {
      List<SellStore.Tx> txs = store.loadTxs();
      for (SellStore.Tx tx : txs) {
        if (tx.cancellable()) {
          // 整单退回：托管优先（防止「托管与事务同时存在」时把同一批物品退两遍）
          List<ItemStack> merged = new ArrayList<>(pending.getOrDefault(tx.player(), List.of()));
          if (merged.isEmpty()) {
            merged.addAll(tx.sold());
            merged.addAll(tx.refunds());
            if (!merged.isEmpty()) {
              pending.put(tx.player(), merged);
              store.saveEscrow(tx.player(), merged);
            }
          } else {
            jlogger.warn("JSell 事务 %s 与托管记录同时存在，已以托管为准（不重复补发）", tx.id());
          }
          store.deleteTx(tx.id());
          jlogger.info("JSell 已回滚未完成事务 %s（%s 件物品将退还 %s）", tx.id(),
              merged.size(), tx.player());
        } else {
          jlogger.warn("JSell 事务 %s 停在付款阶段（%s，金额 %s），为避免重复付款请人工核查：%s",
              tx.id(), tx.player(), tx.amount(), tx.note());
        }
      }
    } catch (SellStore.SellStoreException e) {
      module.reportStorageError("恢复出售事务", e);
    }
  }

  // ──────────────────────────────────────────────
  // 会话
  // ──────────────────────────────────────────────

  /** 打开（或复用）一个会话；有崩服遗留的托管内容时会先装进来 */
  public SellSession open(Player player) {
    SellSession session = active.get(player.getUniqueId());
    if (session != null) {
      return session;
    }
    session = new SellSession(player.getUniqueId());
    List<ItemStack> owed = pending.remove(player.getUniqueId());
    if (owed != null && !owed.isEmpty()) {
      for (ItemStack item : owed) {
        session.add(item);
      }
      player.sendMessage(i18n.tr("jsell.escrow.restored", owed.size()));
    }
    active.put(player.getUniqueId(), session);
    return session;
  }

  @Nullable
  public SellSession get(Player player) {
    return active.get(player.getUniqueId());
  }

  /** 会话内容写库（每次放入/取出后调用，失败只记录不打断界面） */
  public void persist(SellSession session) {
    if (store == null || !store.available()) {
      return;
    }
    List<ItemStack> items = session.snapshot();
    try {
      if (items.isEmpty()) {
        store.deleteEscrow(session.player());
      } else {
        store.saveEscrow(session.player(), items);
      }
    } catch (SellStore.SellStoreException e) {
      module.reportStorageError("保存托管物品", e);
    }
  }

  /** 结算接管：把托管记录删掉（物品的所有权已转进事务日志） */
  public void releaseEscrow(UUID player) throws SellStore.SellStoreException {
    if (store != null && store.available()) {
      store.deleteEscrow(player);
    }
  }

  /**
   * 取消会话：把内容原样还给玩家
   *
   * @param announce 是否给玩家一句「已取消」的提示
   */
  public void cancel(Player player, boolean announce) {
    SellSession session = active.remove(player.getUniqueId());
    if (session == null) {
      return;
    }
    List<ItemStack> items = session.drain();
    if (items.isEmpty()) {
      try {
        releaseEscrow(player.getUniqueId());
      } catch (SellStore.SellStoreException e) {
        module.reportStorageError("清除空托管记录", e);
      }
      return;
    }
    // 先删库再给物品：见类注释「交付顺序」
    try {
      releaseEscrow(player.getUniqueId());
    } catch (SellStore.SellStoreException e) {
      module.reportStorageError("清除托管记录", e);
    }
    giveAndSave(player, items);
    if (announce) {
      player.sendMessage(i18n.tr("jsell.gui.cancelled", items.size()));
    }
  }

  /**
   * 玩家上线时交付崩服遗留的物品
   *
   * @return 交付的件数
   */
  public int deliverPending(Player player) {
    List<ItemStack> items = pending.remove(player.getUniqueId());
    boolean fromDb = false;
    if (items == null) {
      // 内存里没有并不代表库里没有（例如玩家带着界面断线后重连、或插件被 reload）
      if (store == null || !store.available()) {
        return 0;
      }
      try {
        items = store.loadEscrow(player.getUniqueId());
        fromDb = items != null;
      } catch (SellStore.SellStoreException e) {
        module.reportStorageError("读取托管记录", e);
        return 0;
      }
    }
    if (items == null || items.isEmpty()) {
      if (fromDb) {
        try {
          releaseEscrow(player.getUniqueId());
        } catch (SellStore.SellStoreException e) {
          module.reportStorageError("清除空托管记录", e);
        }
      }
      return 0;
    }
    try {
      releaseEscrow(player.getUniqueId());
    } catch (SellStore.SellStoreException e) {
      module.reportStorageError("清除托管记录", e);
    }
    giveAndSave(player, items);
    player.sendMessage(i18n.tr("jsell.escrow.restored", items.size()));
    return items.size();
  }

  /** 插件停用：只清内存，库里的托管记录留给下次上线交付 */
  public void clearMemory() {
    active.clear();
    pending.clear();
  }

  // ──────────────────────────────────────────────
  // 交付
  // ──────────────────────────────────────────────

  /**
   * 把物品塞进玩家背包，塞不下的掉在脚下
   * <p>
   * 合并同种物品由 {@link org.bukkit.inventory.PlayerInventory#addItem(ItemStack...)} 负责，
   * 它返回「实在放不下」的余量，我们再逐件丢到地面上——绝不静默丢弃。
   */
  public static void giveOrDrop(Player player, List<ItemStack> items) {
    if (items == null || items.isEmpty()) {
      return;
    }
    for (ItemStack item : items) {
      if (item == null || item.getType().isAir() || item.getAmount() <= 0) {
        continue;
      }
      Map<Integer, ItemStack> leftover = player.getInventory().addItem(item.clone());
      for (ItemStack drop : leftover.values()) {
        player.getWorld().dropItemNaturally(player.getLocation(), drop);
      }
    }
    player.updateInventory();
  }

  /**
   * 交给玩家并立刻落一次玩家数据
   * <p>
   * 库里那条记录已经删了，如果这次「塞进背包」只存在于内存里，而服务器紧接着保存/退出，
   * 玩家就会既没有库里的记录、也没有背包里的东西。所以这里主动存一次盘：
   * 落地顺序变成「删库 → 进背包 → 存盘」，最坏情况下重复，绝不会两边都没有。
   */
  private static void giveAndSave(Player player, List<ItemStack> items) {
    giveOrDrop(player, items);
    try {
      player.saveData();
    } catch (Exception e) {
      jlogger.debug("JSell 交付后保存玩家数据失败（不影响本次交付）：%s", e.getLocalizedMessage());
    }
  }

  /** 给管理员看的临时补发接口（{@code /jsellset restore}） */
  public void deliverTo(Player player, List<ItemStack> items) {
    giveOrDrop(player, items);
  }

  /** 有待交付内容的玩家（供命令查询） */
  public List<UUID> pendingPlayers() {
    List<UUID> result = new ArrayList<>(pending.keySet());
    return result;
  }

  /** 供占位符/调试使用：当前有会话的人数 */
  public int activeSessions() {
    return active.size();
  }
}
