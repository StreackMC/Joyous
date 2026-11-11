package com.github.streackmc.Joyous.JSell;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;

import com.github.streackmc.Joyous.Joyous;
import com.github.streackmc.Joyous.utils.i18n;
import com.github.streackmc.Joyous.utils.jlogger;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/**
 * JSell 事件监听
 * <p>
 * 三条主线：
 * <ul>
 *   <li><b>点击/拖拽</b>：全部路由到界面自己的处理器；出售界面一进一出都由插件独占管理，
 *       原版的搬运语义（拿在手上、数字键交换、拖拽）在这里只会造成物品被吞；</li>
 *   <li><b>关闭/退出</b>：出售界面关闭＝取消（物品原样返还），管理员界面关闭＝收尾（详情回列表）；</li>
 *   <li><b>上线</b>：交付上次崩服遗留的托管物品。</li>
 * </ul>
 *
 * @author kdxiaoyi
 * @since 0.8.0
 */
public final class JSellListener implements Listener {

  private final JSellMain module;
  private final SellSessionManager sessions;
  private final JSellGui gui;
  private final JSellEditorGui editor;
  private final JSellChatInput input;

  public JSellListener(JSellMain module, SellSessionManager sessions, JSellGui gui,
      JSellEditorGui editor, JSellChatInput input) {
    this.module = module;
    this.sessions = sessions;
    this.gui = gui;
    this.editor = editor;
    this.input = input;
  }

  // ──────────────────────────────────────────────
  // 点击
  // ──────────────────────────────────────────────

  @EventHandler(priority = EventPriority.HIGH)
  public void onClick(InventoryClickEvent event) {
    if (!(event.getWhoClicked() instanceof Player player)) {
      return;
    }
    JSellGui.Holder holder = JSellGui.holderOf(player.getOpenInventory().getTopInventory());
    if (holder == null) {
      return;
    }
    try {
      switch (holder.kind()) {
        case SELL -> {
          Inventory top = player.getOpenInventory().getTopInventory();
          Inventory clicked = event.getClickedInventory();
          boolean inTop = clicked != null && clicked.equals(top);
          if (inTop) {
            event.setCancelled(true);
            gui.handleClick(event, player, holder);
            return;
          }
          // 背包侧：只在「会碰到上半区」的情况下拦，其余让玩家自由整理背包
          gui.handleClick(event, player, holder);
        }
        case EDITOR_LIST -> {
          Inventory top = player.getOpenInventory().getTopInventory();
          Inventory clicked = event.getClickedInventory();
          if (clicked != null && clicked.equals(top)) {
            event.setCancelled(true);
          }
          editor.handleClick(event, player, holder);
        }
        case EDITOR_DETAIL -> {
          event.setCancelled(true);
          editor.handleClick(event, player, holder);
        }
      }
    } catch (Exception e) {
      jlogger.warn("JSell 处理界面点击时出错：%s", e.getLocalizedMessage(), e);
    }
  }

  // ──────────────────────────────────────────────
  // 拖拽
  // ──────────────────────────────────────────────

  /**
   * 出售界面：拖拽一律拒绝
   * <p>
   * 一次拖拽会同时碰多个槽位，与「一次一格事务」的托管模型冲突（部分成功最难处理），
   * 所以只允许「点空格放入」与「Shift+左键批量放入」。
   * <p>
   * 管理员界面：拖进来的物品被登记成新条目（拖拽本身取消，物品不消耗）。
   */
  @EventHandler(priority = EventPriority.HIGH)
  public void onDrag(InventoryDragEvent event) {
    if (!(event.getWhoClicked() instanceof Player player)) {
      return;
    }
    JSellGui.Holder holder = JSellGui.holderOf(player.getOpenInventory().getTopInventory());
    if (holder == null) {
      return;
    }
    try {
      if (holder.kind() == JSellGui.Kind.EDITOR_LIST) {
        if (editor.handleDrag(event, player, holder)) {
          return;
        }
      }
      event.setCancelled(true);
    } catch (Exception e) {
      event.setCancelled(true);
      jlogger.warn("JSell 处理拖拽时出错：%s", e.getLocalizedMessage(), e);
    }
  }

  // ──────────────────────────────────────────────
  // 关闭 / 退出 / 上线
  // ──────────────────────────────────────────────

  @EventHandler(priority = EventPriority.MONITOR)
  public void onClose(InventoryCloseEvent event) {
    if (!(event.getPlayer() instanceof Player player)) {
      return;
    }
    JSellGui.Holder holder = JSellGui.holderOf(event.getInventory());
    if (holder == null) {
      return;
    }
    try {
      switch (holder.kind()) {
        case SELL -> {
          if (!(holder.payload() instanceof SellSession session)) {
            return;
          }
          if (session.closingByPlugin()) {
            // 结算完成 / 「关闭」按钮：内容已经被事务接管，不该当成取消
            session.closingByPlugin(false);
            return;
          }
          // 按 E / Esc 关闭 = 取消：物品原样返还
          sessions.cancel(player, true);
          gui.cancelPendingUpdate(player);
        }
        case EDITOR_LIST, EDITOR_DETAIL -> {
          // 等聊天栏输入时是我们主动关的界面，别再开回来
          if (input.isWaiting(player)) {
            return;
          }
          if (holder.kind() == JSellGui.Kind.EDITOR_DETAIL) {
            backToList(player);
          } else {
            module.entries().flush();
            notifyEditorLeft(player);
          }
        }
        default -> {
        }
      }
    } catch (Exception e) {
      jlogger.warn("JSell 处理界面关闭时出错：%s", e.getLocalizedMessage(), e);
    }
  }

  /** 详情界面被 Esc 关掉后回到列表 */
  private void backToList(Player player) {
    Bukkit.getScheduler().runTask(Joyous.plugin, () -> {
      if (!player.isOnline()) {
        return;
      }
      JSellGui.Holder opened = JSellGui.holderOf(player.getOpenInventory().getTopInventory());
      if (opened != null && opened.kind() == JSellGui.Kind.EDITOR_DETAIL) {
        // 已经自己跳回详情了（多半是动作本身在重开界面）
        return;
      }
      if (opened != null) {
        return;
      }
      if (input.isWaiting(player)) {
        return;
      }
      editor.open(player);
    });
  }

  /** 真离开编辑界面时给一句反馈（界面之间跳转也会触发关闭事件，所以延迟一刻再判断） */
  private static void notifyEditorLeft(Player player) {
    Bukkit.getScheduler().runTask(Joyous.plugin, () -> {
      if (!player.isOnline()) {
        return;
      }
      if (JSellGui.holderOf(player.getOpenInventory().getTopInventory()) == null) {
        player.sendMessage(i18n.tr("jsell.editor.closed"));
      }
    });
  }

  @EventHandler
  public void onQuit(PlayerQuitEvent event) {
    Player player = event.getPlayer();
    // 断线 = 取消：先把东西还给他（放进背包 / 掉在脚下），绝不能留在内存里
    sessions.cancel(player, false);
    gui.cancelPendingUpdate(player);
    input.clear(player);
    editor.clear(player);
    module.entries().flush();
  }

  @EventHandler
  public void onJoin(PlayerJoinEvent event) {
    Player player = event.getPlayer();
    // 上一次崩服留在库里的托管物品，现在交还给他
    Bukkit.getScheduler().runTaskLater(Joyous.plugin,
        () -> sessions.deliverPending(player), 1L);
  }

  // ──────────────────────────────────────────────
  // 聊天栏输入
  // ──────────────────────────────────────────────

  @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
  public void onChat(AsyncChatEvent event) {
    Player player = event.getPlayer();
    if (!input.hasPending(player)) {
      return;
    }
    String message = PlainTextComponentSerializer.plainText().serialize(event.message());
    // 先截获，避免管理员的输入被当成普通聊天发出去
    event.setCancelled(true);
    input.submit(player, message);
  }

  // ──────────────────────────────────────────────
  // 权限
  // ──────────────────────────────────────────────

  /**
   * 权限节点
   * <p>
   * 出售是面向玩家的功能，但仍然需要节点：有些服务器要按身份/世界禁用这套系统，
   * 靠权限（而不是改配置重启）来开关最省事。
   */
  public static final class Perm {
    /** 使用出售系统（含四个子命令） */
    public static final String USE = "joyous.jsell.use";
    /** 编辑可售列表、核查事务 */
    public static final String ADMIN = "joyous.jsell.admin";

    private Perm() {
    }
  }
}
