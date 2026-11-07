package com.github.streackmc.Joyous.JwardChest;

import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nullable;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import com.github.streackmc.Joyous.Joyous;
import com.github.streackmc.Joyous.i18n;
import com.github.streackmc.Joyous.jlogger;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/**
 * JwardChest 事件监听
 * <p>
 * 运行时容器的交互只有两条路（其余一律取消）：
 * <ul>
 *   <li><b>点击有东西的格子 = 领取</b>（点击即领取，不再有「拿起再放回」这种中间态）；</li>
 *   <li><b>把东西放进空格子</b>：手持物品点空格、Shift+左键背包物品、或直接拖到空格上。</li>
 * </ul>
 * 其余点击（拿起、快速移动、数字键交换、丢出、双击收拢）全部拦掉——容器的内容由插件独占管理，
 * 原版的搬运语义在这里只会造成「物品被吞」或「同一件被两个人拿走」。
 *
 * @author kdxiaoyi
 * @since 0.7.0
 */
public final class WardListener implements Listener {

  private final JwardChestMain module;
  private final WardProfileStore store;
  private final WardSessionManager sessions;
  private final WardSearchController search;
  private final WardEditorGui editor;
  private final WardChatInput chatInput;

  public WardListener(JwardChestMain module, WardProfileStore store, WardSessionManager sessions,
      WardSearchController search, WardEditorGui editor, WardChatInput chatInput) {
    this.module = module;
    this.store = store;
    this.sessions = sessions;
    this.search = search;
    this.editor = editor;
    this.chatInput = chatInput;
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
  // 点击
  // ──────────────────────────────────────────────

  @EventHandler(priority = EventPriority.HIGH)
  public void onClick(InventoryClickEvent event) {
    if (!(event.getWhoClicked() instanceof Player player)) {
      return;
    }
    WardGui.Holder holder = WardGui.holderOf(player.getOpenInventory().getTopInventory());
    if (holder == null) {
      return;
    }

    switch (holder.kind()) {
      case EDITOR, CONTAINER_PICKER, MAX_DRAW, DISTRIBUTION -> {
        // 纯按钮界面：全部取消原版行为
        event.setCancelled(true);
        safeHandle(event, holder);
      }
      case ITEM_EDITOR -> {
        // 待选物品界面：只拦截上半部分（我们的界面），
        // 下半部分必须放行——编辑者要从自己背包里把物品拿起来才能放进物品格
        Inventory top = player.getOpenInventory().getTopInventory();
        boolean inTop = event.getClickedInventory() != null && event.getClickedInventory().equals(top);
        if (inTop) {
          event.setCancelled(true);
          safeHandle(event, holder);
          return;
        }
        // 背包侧的 Shift+左键 = 把这个物品登记成一条待选条目（不消耗原物品）
        if (event.getClick() == org.bukkit.event.inventory.ClickType.SHIFT_LEFT) {
          event.setCancelled(true);
          editor.appendEntry(player, holder, event.getCurrentItem());
          return;
        }
        // 其余「快捷移动 / 双击收拢」仍可能碰上半区，一律挡掉
        if (event.getClick().isShiftClick()
            || event.getClick() == org.bukkit.event.inventory.ClickType.DOUBLE_CLICK) {
          event.setCancelled(true);
        }
      }
      case RUNTIME, PREVIEW -> handleRuntimeClick(event, player, holder);
      default -> {
      }
    }
  }

  /**
   * 运行时容器的点击：只认「领取」与「放入」两件事
   * <p>
   * 上半区（容器）一律取消原版行为；下半区（玩家背包）只在会被原版搬到上半区的情况下才拦
   * （Shift+点击、双击收拢），玩家整理自己背包是允许的。
   */
  private void handleRuntimeClick(InventoryClickEvent event, Player player, WardGui.Holder holder) {
    if (!(holder.payload() instanceof WardSession session)) {
      return;
    }
    Inventory top = player.getOpenInventory().getTopInventory();
    Inventory clicked = event.getClickedInventory();
    boolean inTop = clicked != null && clicked.equals(top);

    if (!inTop) {
      org.bukkit.event.inventory.ClickType type = event.getClick();
      if (type.isShiftClick() && event.getSlot() >= 0) {
        event.setCancelled(true);
        search.placeFromInventory(player, session, event.getSlot());
        return;
      }
      if (type == org.bukkit.event.inventory.ClickType.DOUBLE_CLICK) {
        // 双击会把光标上的物品与「所有界面里的同类物品」合并，会碰到上半区
        event.setCancelled(true);
      }
      return;
    }

    event.setCancelled(true);

    int slot = event.getRawSlot();
    if (slot < 0 || slot >= top.getSize()) {
      return;
    }

    if (session.hasItem(slot)) {
      search.claim(player, session, slot);
      return;
    }

    ItemStack cursor = event.getCursor();
    if (cursor == null || cursor.getType().isAir()) {
      return;
    }
    int amount = event.isRightClick() ? 1 : cursor.getAmount();
    search.placeFromCursor(player, session, slot, amount);
  }

  private void safeHandle(InventoryClickEvent event, WardGui.Holder holder) {
    try {
      editor.handleClick(event, holder);
    } catch (Exception e) {
      jlogger.warn("JwardChest 编辑界面处理点击时出错：%s", e.getLocalizedMessage(), e);
    }
  }

  // ──────────────────────────────────────────────
  // 拖拽
  // ──────────────────────────────────────────────

  /**
   * 拖拽一律取消
   * <p>
   * 拖拽会一次性碰多个槽位，与「一次一格事务」的原子模型冲突（部分成功最难处理），
   * 所以这里统一拒绝；放入请用「点击空格」或「Shift+左键」。
   */
  @EventHandler(priority = EventPriority.HIGH)
  public void onDrag(InventoryDragEvent event) {
    if (!(event.getWhoClicked() instanceof Player player)) {
      return;
    }
    WardGui.Holder holder = WardGui.holderOf(player.getOpenInventory().getTopInventory());
    if (holder != null) {
      event.setCancelled(true);
    }
  }

  // ──────────────────────────────────────────────
  // 关闭 / 退出
  // ──────────────────────────────────────────────

  @EventHandler(priority = EventPriority.MONITOR)
  public void onClose(InventoryCloseEvent event) {
    if (!(event.getPlayer() instanceof Player player)) {
      return;
    }
    WardGui.Holder holder = WardGui.holderOf(event.getInventory());
    if (holder == null) {
      return;
    }
    switch (holder.kind()) {
      case RUNTIME, PREVIEW -> {
        // 取消搜索任务：进行中的倒计时全部重置；已揭开的记录保留在会话里
        search.stop(player);
        if (holder.payload() instanceof WardSession session) {
          session.unbindViewer(player.getUniqueId());
          session.touch();
          if (session.preview()) {
            // 预览是临时演示：关闭即整个丢弃，重开时重新抽取
            sessions.remove(session.key());
          }
        }
      }
      case EDITOR, CONTAINER_PICKER, ITEM_EDITOR, MAX_DRAW, DISTRIBUTION -> {
        // 编辑类界面关闭时统一落盘：一次编辑只写一次文件
        store.flush();
        // 正在等待聊天栏输入时（是我们自己关掉界面让他打字的），什么提示都别发
        if (chatInput.isWaiting(player)) {
          break;
        }
        if (holder.kind() == WardGui.Kind.EDITOR) {
          notifyEditorLeft(player);
        } else {
          backToEditor(player, holder.profileId(),
              holder.payload() instanceof Location l ? l : null, holder.kind());
        }
      }
      default -> {
      }
    }
  }

  /**
   * 真离开编辑界面时给一句反馈
   * <p>
   * 我们自己的界面之间跳转（主界面→子菜单）也会触发关闭事件，
   * 所以延迟一刻再判断「此时是否还有我们的界面开着」，只在真的退干净时才提示。
   */
  private static void notifyEditorLeft(Player player) {
    Bukkit.getScheduler().runTask(Joyous.plugin, () -> {
      if (!player.isOnline()) {
        return;
      }
      if (WardGui.holderOf(player.getOpenInventory().getTopInventory()) == null) {
        player.sendMessage(i18n.tr("jwardchest.editor.closed"));
      }
    });
  }

  /** 需要「关掉后自动回到主界面」的子界面 */
  private static final Set<WardGui.Kind> SUBMENUS = EnumSet.of(
      WardGui.Kind.CONTAINER_PICKER, WardGui.Kind.ITEM_EDITOR,
      WardGui.Kind.MAX_DRAW, WardGui.Kind.DISTRIBUTION);

  /**
   * 子界面被 Esc / E 关掉后回到主编辑界面
   * <p>
   * 延迟一刻再判断「此刻玩家是否还开着我们自己的界面」——因为子界面之间的跳转、以及
   * 「选完就直接开主界面」这类动作本身也会触发关闭事件，那时不该再叠一次开界面。
   */
  private void backToEditor(Player player, UUID profileId, @Nullable Location block,
      WardGui.Kind from) {
    if (!SUBMENUS.contains(from)) {
      return;
    }
    Bukkit.getScheduler().runTask(Joyous.plugin, () -> {
      if (!player.isOnline()) {
        return;
      }
      if (WardGui.holderOf(player.getOpenInventory().getTopInventory()) != null) {
        // 已经在我们自己的界面里了（多半是动作本身跳过去的），不必再回主界面
        return;
      }
      if (from == WardGui.Kind.ITEM_EDITOR) {
        player.sendMessage(i18n.tr("jwardchest.editor.items_unsaved"));
      }
      editor.openEditor(player, profileId, block);
    });
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
    Component tip = WardGui.legacy(i18n.tr("jwardchest.protect.tip"))
        .clickEvent(ClickEvent.runCommand(command))
        .hoverEvent(HoverEvent.showText(WardGui.legacy(i18n.tr("jwardchest.protect.tip_hover"))));
    player.sendMessage(tip);
  }

  @EventHandler
  public void onQuit(PlayerQuitEvent event) {
    Player player = event.getPlayer();
    search.stop(player);
    chatInput.clear(player);
    editor.clearWorking(player);
    // 从他的视角把所有容器登记摘掉，避免"正在被查看"判定把过期会话一直续命
    for (WardSession session : sessions.all()) {
      session.unbindViewer(player.getUniqueId());
    }
    // 编辑到一半直接退出/掉线，别把改动丢掉
    store.flush();
  }

  // ──────────────────────────────────────────────
  // 聊天栏输入
  // ──────────────────────────────────────────────

  @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
  public void onChat(AsyncChatEvent event) {
    Player player = event.getPlayer();
    if (!chatInput.hasPending(player)) {
      return;
    }
    String message = PlainTextComponentSerializer.plainText().serialize(event.message());
    // 先截获，避免玩家的输入被当作普通聊天发出去
    event.setCancelled(true);
    chatInput.submit(player, message);
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
