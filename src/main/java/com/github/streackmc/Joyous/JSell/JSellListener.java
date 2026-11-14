package com.github.streackmc.Joyous.JSell;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import com.github.streackmc.Joyous.Joyous;

/**
 * JSell 的玩家生命周期监听
 * <p>
 * 界面层的点击 / 拖拽 / 关闭全部由 {@code utils.interact} 路由到 {@link JSellSellPage}、
 * {@link JSellListPage}、{@link JSellDetailPage}，这里不再订阅任何界面事件，
 * 只保留两件必须跟着玩家走的事：
 * <ul>
 *   <li><b>上线</b>：交付上次崩服遗留的托管物品；</li>
 *   <li><b>退出</b>：取消托管（把东西还给他）、清理浏览状态。</li>
 * </ul>
 *
 * @author kdxiaoyi
 * @since 0.8.0
 */
public final class JSellListener implements Listener {

  private final JSellMain module;
  private final SellSessionManager sessions;
  private final JSellEditorGui editor;

  public JSellListener(JSellMain module, SellSessionManager sessions, JSellEditorGui editor) {
    this.module = module;
    this.sessions = sessions;
    this.editor = editor;
  }

  @EventHandler
  public void onQuit(PlayerQuitEvent event) {
    Player player = event.getPlayer();
    // 断线 = 取消：先把东西还给他（放进背包 / 掉在脚下），绝不能留在内存里
    sessions.cancel(player, false);
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
