package com.github.streackmc.Joyous.JSell;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import com.github.streackmc.Joyous.Joyous;
import com.github.streackmc.Joyous.i18n;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

/**
 * 聊天栏输入（管理员编辑用）
 * <p>
 * 与 JwardChest 的同名组件同一套做法：发一条提示（「点击此处填入旧值」），玩家在聊天栏打字，
 * 由 {@link JSellListener} 在聊天事件里截获。输入 {@code cancel} / {@code 取消} 放弃。
 * <p>
 * 因为要打字就必须先把界面关掉，所以每个请求都带一个「收尾动作」——提交、取消、超时三种结局
 * 都会执行<b>一次</b>，而且一定在主线程上（聊天事件是异步的，在那里直接开界面会被服务端拒绝）。
 *
 * @author kdxiaoyi
 * @since 0.8.0
 */
public final class JSellChatInput {

  /** 跳转到指定页 */
  public static final String TYPE_PAGE = "page";
  /** 搜索关键词 */
  public static final String TYPE_SEARCH = "search";
  /** 单价 */
  public static final String TYPE_VALUE = "value";
  /** 窗口内最大卖出数量 */
  public static final String TYPE_LIMIT = "limit";
  /** 限额窗口秒数 */
  public static final String TYPE_WINDOW = "window";
  /** 卖出后执行命令（{@code |} 分隔多条） */
  public static final String TYPE_COMMANDS = "commands";
  /** 逐件依次执行命令（{@code |} 分隔多条） */
  public static final String TYPE_PER_ITEM = "per-item";
  /** 删除确认 */
  public static final String TYPE_CONFIRM_DELETE = "confirm-delete";

  private record Pending(String type, String oldValue, String label, long expiresAt,
      Consumer<String> onAccept, @Nullable Runnable onFinish) {
  }

  private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();
  private JSellEditorGui editor;

  public JSellChatInput() {
  }

  /** 循环依赖解除：编辑器要用聊天输入，聊天输入要通知编辑器重开界面 */
  void bind(JSellEditorGui editor) {
    this.editor = editor;
  }

  /**
   * 发起一次输入
   *
   * @param type     字段类型，见 {@link #TYPE_PAGE} 等
   * @param oldValue 旧值（「点击填入」用），可为 null
   * @param label    提示里显示的字段名（已翻译），可为 null（会按类型取默认）
   * @param onAccept 接受回调
   * @param onFinish 收尾动作（通常是「把界面开回来」），可为 null
   */
  public void request(Player player, String type, @Nullable String oldValue, @Nullable String label,
      Consumer<String> onAccept, @Nullable Runnable onFinish) {
    long ttl = JSellMain.CONF.chatInputTimeoutSeconds() * 1000L;
    // 先登记再关界面：关闭事件里会检查「是否正在等待输入」，避免误发「已取消」
    pending.put(player.getUniqueId(), new Pending(type, oldValue == null ? "" : oldValue,
        label == null ? defaultLabel(type) : label, System.currentTimeMillis() + ttl, onAccept,
        onFinish));
    player.closeInventory();
    player.sendMessage(buildPrompt(type, oldValue, label));
  }

  /** 是否正在等待该玩家输入（界面临时关闭是正常流程，不该提示「已退出」） */
  public boolean isWaiting(Player player) {
    Pending p = pending.get(player.getUniqueId());
    return p != null && System.currentTimeMillis() <= p.expiresAt();
  }

  /** 是否有待处理输入（顺带处理过期） */
  public boolean hasPending(Player player) {
    Pending p = pending.get(player.getUniqueId());
    if (p == null) {
      return false;
    }
    if (System.currentTimeMillis() > p.expiresAt()) {
      pending.remove(player.getUniqueId());
      runOnMain(player, p, () -> player.sendMessage(i18n.tr("jsell.input.expired")));
      return false;
    }
    return true;
  }

  public void cancel(Player player) {
    Pending p = pending.remove(player.getUniqueId());
    if (p != null) {
      runOnMain(player, p, () -> player.sendMessage(i18n.tr("jsell.input.cancelled")));
    }
  }

  public void clear(Player player) {
    pending.remove(player.getUniqueId());
  }

  /**
   * 提交一次输入（由聊天事件调用）
   *
   * @return true 表示这条消息已被消费（不该再作为普通聊天发出去）
   */
  public boolean submit(Player player, String message) {
    if (!hasPending(player)) {
      return false;
    }
    Pending p = pending.remove(player.getUniqueId());
    if (p == null) {
      return false;
    }
    String value = message == null ? "" : message.trim();
    if (value.equalsIgnoreCase("cancel") || value.equals("取消")) {
      runOnMain(player, p, () -> player.sendMessage(i18n.tr("jsell.input.cancelled")));
      return true;
    }
    runOnMain(player, p, () -> p.onAccept().accept(value));
    return true;
  }

  /** 统一在主线程上「执行动作 → 收尾」 */
  private static void runOnMain(Player player, Pending p, Runnable action) {
    Runnable job = () -> {
      try {
        action.run();
      } catch (Exception e) {
        player.sendMessage(i18n.tr("jsell.input.failed", String.valueOf(e.getMessage())));
      }
      if (p.onFinish() != null && player.isOnline()) {
        p.onFinish().run();
      }
    };
    if (Bukkit.isPrimaryThread()) {
      job.run();
    } else {
      Bukkit.getScheduler().runTask(Joyous.plugin, job);
    }
  }

  private static String defaultLabel(String type) {
    return i18n.tr("jsell.input.field." + type.replace('-', '_'));
  }

  private static Component buildPrompt(String type, @Nullable String oldValue,
      @Nullable String label) {
    String name = label == null ? defaultLabel(type) : label;
    Component head = JSellGui.legacy(i18n.tr("jsell.input.prompt", name));

    if (oldValue != null && !oldValue.isBlank()) {
      head = head.append(Component.newline())
          .append(JSellGui.legacy(i18n.tr("jsell.input.paste_hint"))
              .clickEvent(ClickEvent.suggestCommand(oldValue))
              .hoverEvent(HoverEvent.showText(Component.text(oldValue, NamedTextColor.GRAY)))
              .decoration(TextDecoration.UNDERLINED, true));
    }
    head = head.append(Component.newline())
        .append(JSellGui.legacy(i18n.tr("jsell.input.cancel_hint")));

    if (TYPE_COMMANDS.equals(type) || TYPE_PER_ITEM.equals(type)) {
      head = head.append(Component.newline())
          .append(JSellGui.legacy(i18n.tr("jsell.input.multi_hint")));
    }
    if (TYPE_WINDOW.equals(type)) {
      head = head.append(Component.newline())
          .append(JSellGui.legacy(i18n.tr("jsell.input.window_hint")));
    }
    if (TYPE_CONFIRM_DELETE.equals(type)) {
      head = head.append(Component.newline())
          .append(JSellGui.legacy(i18n.tr("jsell.input.delete_hint")));
    }
    return head;
  }
}
