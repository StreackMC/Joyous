package com.github.streackmc.Joyous.JwardChest;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import org.bukkit.entity.Player;

import com.github.streackmc.Joyous.i18n;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

/**
 * 聊天栏输入
 * <p>
 * 编辑界面里需要玩家「打字」的字段（预设代号、标题、数量、权重）统一走这里：
 * 发一条提示，其中「粘贴旧值」是可点击的（点击即把旧值填进聊天框），
 * 输入 {@code cancel} 取消；预设代号额外支持输入 {@code unset} 改回默认。
 * 输入由 {@link WardListener} 在聊天事件里截获。
 *
 * @author kdxiaoyi
 * @since 0.7.0
 */
public final class WardChatInput {

  /** 预设代号 */
  public static final String TYPE_CODE = "code";
  /** 容器标题 */
  public static final String TYPE_TITLE = "title";
  /** 最小数量 */
  public static final String TYPE_MIN = "min";
  /** 最大数量 */
  public static final String TYPE_MAX = "max";
  /** 整数权重 */
  public static final String TYPE_WEIGHT = "weight";

  /** 一条待处理的输入 */
  private record Pending(String type, String oldValue, long expiresAt, Consumer<String> onAccept) {
  }

  private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();
  private final WardProfileStore store;

  public WardChatInput(WardProfileStore store, WardEditorGui editor) {
    this.store = store;
  }

  /**
   * 发起一次聊天栏输入
   *
   * @param player   目标玩家
   * @param type     字段类型，见 {@link #TYPE_CODE} 等常量
   * @param oldValue 旧值，可为 null（用于「粘贴旧值」与提示）
   * @param onAccept 接受回调
   */
  public void request(Player player, String type, @Nullable String oldValue, Consumer<String> onAccept) {
    long ttl = JwardChestMain.CONF.chatInputTimeoutSeconds() * 1000L;
    // 先登记，再关界面：关闭事件里会检查「是否正在等待输入」，避免白发一条"已退出编辑"
    pending.put(player.getUniqueId(), new Pending(type, oldValue == null ? "" : oldValue,
        System.currentTimeMillis() + ttl, onAccept));
    // 玩家要打字就必须离开容器界面，这里替他关掉，省得他手动按 Esc
    player.closeInventory();
    player.sendMessage(buildPrompt(type, oldValue));
  }

  /**
   * 是否有待处理的输入（无任何副作用、不会提示超时）
   * <p>
   * 供「界面关闭」路径查询：等待输入期间界面被关掉是正常流程，不该触发退出提示。
   */
  public boolean isWaiting(Player player) {
    Pending p = pending.get(player.getUniqueId());
    if (p == null) {
      return false;
    }
    if (System.currentTimeMillis() > p.expiresAt()) {
      pending.remove(player.getUniqueId());
      return false;
    }
    return true;
  }

  /** 是否有待处理的输入 */
  public boolean hasPending(Player player) {
    Pending p = pending.get(player.getUniqueId());
    if (p == null) {
      return false;
    }
    if (System.currentTimeMillis() > p.expiresAt()) {
      pending.remove(player.getUniqueId());
      player.sendMessage(i18n.tr("jwardchest.input.expired"));
      return false;
    }
    return true;
  }

  /** 取消待处理输入 */
  public void cancel(Player player) {
    if (pending.remove(player.getUniqueId()) != null) {
      player.sendMessage(i18n.tr("jwardchest.input.cancelled"));
    }
  }

  /** 玩家退出时清理 */
  public void clear(Player player) {
    pending.remove(player.getUniqueId());
  }

  /**
   * 提交一次输入（由聊天事件调用）
   *
   * @param player  玩家
   * @param message 聊天内容
   * @return true 表示这条消息已被消费（不应再作为普通聊天发出去）
   */
  public boolean submit(Player player, String message) {
    if (!hasPending(player)) {
      return false;
    }
    Pending p = pending.get(player.getUniqueId());
    pending.remove(player.getUniqueId());

    String value = message == null ? "" : message.trim();

    if (value.equalsIgnoreCase("cancel")) {
      player.sendMessage(i18n.tr("jwardchest.input.cancelled"));
      return true;
    }

    if (TYPE_CODE.equals(p.type()) && (value.equalsIgnoreCase("unset")
        || value.equalsIgnoreCase(WardProfile.UNSET_CODE))) {
      p.onAccept().accept(WardProfile.UNSET_CODE);
      store.flush();
      player.sendMessage(i18n.tr("jwardchest.input.code_unset"));
      return true;
    }

    p.onAccept().accept(value);
    // 聊天栏输入是「一次一个动作」，直接落盘；界面里的连点编辑才靠关闭时统一落盘
    store.flush();
    return true;
  }

  /** 组装提示消息 */
  private static Component buildPrompt(String type, @Nullable String oldValue) {
    String label = switch (type) {
      case TYPE_CODE -> i18n.tr("jwardchest.input.field.code");
      case TYPE_TITLE -> i18n.tr("jwardchest.input.field.title");
      case TYPE_MIN -> i18n.tr("jwardchest.input.field.min");
      case TYPE_MAX -> i18n.tr("jwardchest.input.field.max");
      case TYPE_WEIGHT -> i18n.tr("jwardchest.input.field.weight");
      default -> type;
    };

    Component head = WardGui.legacy(i18n.tr("jwardchest.input.prompt", label));

    if (oldValue != null && !oldValue.isBlank()) {
      head = head.append(Component.newline())
          .append(WardGui.legacy(i18n.tr("jwardchest.input.paste_hint"))
              .clickEvent(ClickEvent.suggestCommand(oldValue))
              .hoverEvent(HoverEvent.showText(Component.text(oldValue, NamedTextColor.GRAY)))
              .decoration(TextDecoration.UNDERLINED, true));
    }

    head = head.append(Component.newline())
        .append(WardGui.legacy(i18n.tr("jwardchest.input.cancel_hint")));

    if (TYPE_CODE.equals(type)) {
      head = head.append(Component.newline())
          .append(WardGui.legacy(i18n.tr("jwardchest.input.unset_hint")));
    }
    return head;
  }
}
