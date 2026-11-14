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
 * 只处理<b>管理员界面</b>与玩家生命周期两件事：
 * <ul>
 *   <li><b>管理员界面</b>：点击/拖拽/关闭全部路由到 {@link JSellEditorGui}（列表 ↔ 详情）；</li>
 *   <li><b>上线</b>：交付上次崩服遗留的托管物品；<b>退出</b>：取消托管、清理缓存。</li>
 * </ul>
 * 玩家出售界面已经迁到 {@link JSellSellPage}，由 {@code GuiRouter} 统一路由，
 * 关闭即取消退款也在页面的 {@code onDetach} 里完成——这里不再处理。
 *
 * @author kdxiaoyi
 * @since 0.8.0
 */
public final class JSellListener implements Listener {

  private final JSellMain module;
  private final SellSessionManager sessions;
  private final JSellEditorGui editor;
  private final JSellChatInput input;

  public JSellListener(JSellMain module, SellSessionManager sessions,
      JSellEditorGui editor, JSellChatInput input) {
    this.module = module;
    this.sessions = sessions;
    this.editor = editor;
    this.input = input;
  }

  // ──────────────────────────────────────────────
  // 管理员界面：点击
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
      Inventory top = player.getOpenInventory().getTopInventory();
      Inventory clicked = event.getClickedInventory();
      boolean inTop = clicked != null && clicked.equals(top);
      if (holder.kind() == JSellGui.Kind.EDITOR_DETAIL || inTop) {
        // 详情页整页独占；列表页只拦上半区，下半区留给管理员整理背包
        event.setCancelled(true);
      }
      editor.handleClick(event, player, holder);
    } catch (Exception e) {
      jlogger.warn("JSell 处理界面点击时出错：%s", e.getLocalizedMessage(), e);
    }
  }

  // ──────────────────────────────────────────────
  // 管理员界面：拖拽
  // ──────────────────────────────────────────────

  /**
   * 管理员列表：拖进来的物品被登记成新条目（拖拽本身取消，物品不消耗）
   * <p>
   * 详情页与其它情况一律取消：一次拖拽会碰多个槽位，与「一次一格」的模型冲突。
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
      if (holder.kind() == JSellGui.Kind.EDITOR_LIST
          && editor.handleDrag(event, player, holder)) {
        return;
      }
      event.setCancelled(true);
    } catch (Exception e) {
      event.setCancelled(true);
      jlogger.warn("JSell 处理拖拽时出错：%s", e.getLocalizedMessage(), e);
    }
  }

  // ──────────────────────────────────────────────
  // 管理员界面：关闭 / 退出 / 上线
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
      if (JSellGui.holderOf(player.getOpenInventory().getTopInventory()) != null) {
        // 已经在我们自己的界面里了（多半是动作本身跳过去的）
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
  // 聊天栏输入（管理员界面用；出售界面没有输入）
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
