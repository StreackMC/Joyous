package com.github.streackmc.Joyous.JSell;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import com.github.streackmc.Joyous.Joyous;
import com.github.streackmc.Joyous.JSell.admin.JSellEditorGui;
import com.github.streackmc.Joyous.JSell.data.SellSessionManager;
import com.github.streackmc.Joyous.JSell.user.JSellSellPage;

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
 * @since 0.3.0
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
   * 命名遵循项目统一规则（0.9.0 起）：
   * <ul>
   *   <li><b>命令</b> —— {@code joyous.commands.<模型>.<命令>[.<子类型>]}</li>
   *   <li><b>其它</b> —— {@code joyous.<模型>.[子动作]}</li>
   * </ul>
   * 出售是面向玩家的功能，但仍然需要节点：有些服务器要按身份/世界禁用这套系统，
   * 靠权限（而不是改配置重启）来开关最省事。分组权限见 {@code SellGroups}。
   */
  public static final class Perm {
    /** {@code /jsell}（无参 / gui）——打开出售界面 */
    public static final String SELL_GUI = "joyous.commands.jsell.gui";
    /** {@code /jsell hand} */
    public static final String SELL_HAND = "joyous.commands.jsell.hand";
    /** {@code /jsell inv} */
    public static final String SELL_INV = "joyous.commands.jsell.inv";
    /** {@code /jsell all} */
    public static final String SELL_ALL = "joyous.commands.jsell.all";
    /** {@code /jsellworth} ——玩家总览 */
    public static final String WORTH = "joyous.commands.jsellworth";
    /** {@code /jsellset} ——打开编辑界面 */
    public static final String ADMIN = "joyous.commands.jsellset";
    /** {@code /jsellset reload} */
    public static final String ADMIN_RELOAD = "joyous.commands.jsellset.reload";
    /** {@code /jsellset pending} */
    public static final String ADMIN_PENDING = "joyous.commands.jsellset.pending";
    /** {@code /jsellset restore <tx>} */
    public static final String ADMIN_RESTORE = "joyous.commands.jsellset.restore";
    /** {@code /jsellset <单价> [物品]} */
    public static final String ADMIN_VALUE = "joyous.commands.jsellset.value";
    /** {@code /jsellset <组名>} */
    public static final String ADMIN_GROUP = "joyous.commands.jsellset.group";
    /** 非命令：接收「存档异常 / 事务未走完」这类管理提示 */
    public static final String ADMIN_NOTICE = "joyous.jsell.admin";

    private Perm() {
    }
  }
}
