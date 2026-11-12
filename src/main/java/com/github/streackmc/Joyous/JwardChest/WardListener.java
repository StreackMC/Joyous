package com.github.streackmc.Joyous.JwardChest;

import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;

import com.github.streackmc.Joyous.utils.i18n;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;

/**
 * JwardChest 事件监听
 * <p>
 * 只负责「方块侧」的三件事：右键打开（或潜行右键进编辑）、阻止直接破坏、玩家退出时收尾。
 * <p>
 * <b>界面内的点击 / 拖拽 / 关闭一律不在这里处理</b>——它们已经由 {@code utils.interact} 的
 * {@code GuiRouter} 统一路由到对应页面，取消策略由页面声明。这个类因此不再认识任何
 * {@code InventoryHolder}，也不再订阅聊天事件。
 *
 * @author kdxiaoyi
 * @since 0.7.0
 */
public final class WardListener implements Listener {

  private final WardProfileStore store;
  private final WardSessionManager sessions;
  private final WardSearchController search;
  private final WardEditorGui editor;

  public WardListener(WardProfileStore store, WardSessionManager sessions,
      WardSearchController search, WardEditorGui editor) {
    this.store = store;
    this.sessions = sessions;
    this.search = search;
    this.editor = editor;
  }

  // ──────────────────────────────────────────────
  // 绑定方块：打开
  // ──────────────────────────────────────────────

  @EventHandler(priority = EventPriority.HIGH)
  public void onInteract(PlayerInteractEvent event) {
    if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
      return;
    }
    if (event.getHand() != EquipmentSlot.HAND) {
      return;
    }
    Block block = event.getClickedBlock();
    if (block == null) {
      return;
    }
    UUID profileId = WardBinding.read(block);
    if (profileId == null) {
      return;
    }
    // 是我们绑定的方块：一律拦截原版交互
    event.setCancelled(true);

    Player player = event.getPlayer();

    // 潜行 + 编辑权限 → 打开编辑界面
    if (player.isSneaking() && player.hasPermission(Perm.EDIT)) {
      editor.openEditor(player, profileId, block.getLocation());
      return;
    }

    if (!player.hasPermission(Perm.USE)) {
      player.sendMessage(i18n.tr("jwardchest.container.no_permission"));
      return;
    }

    WardProfile profile = store.effective(profileId);
    WardSession session = sessions.getOrCreate(
        WardSessionManager.blockKey(block.getWorld(), block.getX(), block.getY(), block.getZ()),
        profileId, block.getWorld(), block.getX(), block.getY(), block.getZ(),
        profile.slots(), false);
    search.open(player, profile, session);
  }

  // ──────────────────────────────────────────────
  // 破坏保护
  // ──────────────────────────────────────────────

  /**
   * 阻止直接破坏已绑定的方块
   * <p>
   * 无编辑权限的玩家只得到一句"无权限"；有权限的玩家会收到一条可点击的确认提示，
   * 点击后由 {@code /jwardchest break <坐标>} 代为破坏并解除绑定。
   */
  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void onBlockBreak(BlockBreakEvent event) {
    Block block = event.getBlock();
    if (WardBinding.read(block) == null) {
      return;
    }
    event.setCancelled(true);

    Player player = event.getPlayer();
    if (!player.hasPermission(Perm.EDIT)) {
      player.sendMessage(i18n.tr("jwardchest.protect.no_permission"));
      return;
    }

    String command = "/" + JwardChestMain.COMMAND + " break "
        + block.getX() + " " + block.getY() + " " + block.getZ();
    Component tip = com.github.streackmc.Joyous.utils.interact.Text
        .legacy(i18n.tr("jwardchest.protect.tip"))
        .clickEvent(ClickEvent.runCommand(command))
        .hoverEvent(HoverEvent.showText(com.github.streackmc.Joyous.utils.interact.Text
            .legacy(i18n.tr("jwardchest.protect.tip_hover"))));
    player.sendMessage(tip);
  }

  // ──────────────────────────────────────────────
  // 退出
  // ──────────────────────────────────────────────

  @EventHandler
  public void onQuit(PlayerQuitEvent event) {
    Player player = event.getPlayer();
    search.stop(player);
    WardItemEditorPage.clearWorking(player);
    // 从他的视角把所有容器登记摘掉，避免"正在被查看"判定把过期会话一直续命
    for (WardSession session : sessions.all()) {
      session.unbindViewer(player.getUniqueId());
    }
    // 编辑到一半直接退出/掉线，别把改动丢掉
    store.flush();
  }

  /** 权限节点 */
  public static final class Perm {
    /** 绑定与编辑 */
    public static final String EDIT = "joyous.jwardchest.edit";
    /** 正常使用容器 */
    public static final String USE = "joyous.jwardchest.use";
    /** 重置临时数据 */
    public static final String RESET = "joyous.jwardchest.reset";

    private Perm() {
    }
  }
}
