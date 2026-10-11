package com.github.streackmc.Joyous.Neonaic.chat;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import com.github.streackmc.Joyous.Joyous;
import com.github.streackmc.StreackLib.utils.MCColor;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

/**
 * 聊天格式渲染（{@code Neonaic.yml} 的 {@code *-msg / *-cover / *-click}）。
 * <p>
 * 占位符：{@code [msg]} 聊天内容、{@code [name]} Bot 名、{@code [subname]} Bot 副名、
 * {@code [user]} 消息来源展示名（群服互联已绑定时为 MC 玩家名）、
 * {@code [nickname]} 消息来源在聊天软件侧的原始昵称；
 * 同时支持 PlaceholderAPI 与 {@code &} 颜色码。
 * <p>
 * 单遍替换：{@code msg} 里若含有形如 {@code [name]} 的文本不会被二次替换。
 *
 * @since 0.3.0
 */
public final class ChatMessageRenderer {

  private static final Pattern TOKEN = Pattern.compile("\\[(msg|name|subname|user|nickname)\\]");

  private ChatMessageRenderer() {
  }

  /**
   * 渲染一条聊天消息为组件。
   *
   * @param player   用于解析 PlaceholderAPI 的上下文玩家（可为 null）
   * @param format   格式串
   * @param msg      聊天内容
   * @param name     Bot 名
   * @param subname  Bot 副名
   * @param user     消息来源展示名（群服互联已绑定时为 MC 玩家名，否则为聊天昵称）
   * @param cover    悬浮提示（多行，可为空；同样支持上述 token 与 PlaceholderAPI）
   * @param clickCmd 点击动作类型（none/url/menu/cmd/op/con/copy），可为空
   * @param clickArg 点击动作参数
   */
  public static Component render(@Nullable Player player, String format, String msg, String name, String subname,
      String user, String cover, String clickCmd, String clickArg) {
    return render(player, format, msg, name, subname, user, "", cover, clickCmd, clickArg);
  }

  /**
   * 渲染一条聊天消息为组件（支持 {@code [nickname]}）。
   *
   * @param nickname 消息来源在聊天软件侧的原始昵称（用于群服互联已绑定时保留可辨识信息），可为空
   */
  public static Component render(@Nullable Player player, String format, String msg, String name, String subname,
      String user, String nickname, String cover, String clickCmd, String clickArg) {
    String safeFormat = format == null ? "" : format;
    Component base = toComponent(player, substitute(safeFormat, msg, name, subname, user, nickname));

    if (cover != null && !cover.isBlank()) {
      Component hover = toComponent(player, substitute(cover, msg, name, subname, user, nickname));
      base = base.hoverEvent(HoverEvent.showText(hover));
    }

    ClickEvent click = buildClick(clickCmd, clickArg);
    if (click != null) {
      base = base.clickEvent(click);
    }
    return base;
  }

  /** 构造点击事件；无动作时返回 null */
  public static ClickEvent buildClick(String cmd, String arg) {
    if (cmd == null || cmd.isBlank() || "none".equalsIgnoreCase(cmd)) return null;
    String a = arg == null ? "" : arg.trim();
    switch (cmd.toLowerCase()) {
      case "url":
        return a.isEmpty() ? null : ClickEvent.openUrl(a);
      case "menu":
        return ClickEvent.runCommand(a.isEmpty() ? "/jmenu" : "/jmenu open " + a);
      case "cmd":
      case "op":
      case "con":
        return a.isEmpty() ? null : ClickEvent.runCommand(a);
      case "copy":
        return a.isEmpty() ? null : ClickEvent.copyToClipboard(a);
      default:
        return a.isEmpty() ? null : ClickEvent.runCommand(a);
    }
  }

  /** 用单遍替换填充 token，避免内容中的伪 token 被二次替换 */
  static String substitute(String format, String msg, String name, String subname, String user) {
    return substitute(format, msg, name, subname, user, "");
  }

  /** 用单遍替换填充 token（含 {@code [nickname]}），避免内容中的伪 token 被二次替换 */
  static String substitute(String format, String msg, String name, String subname, String user, String nickname) {
    Matcher m = TOKEN.matcher(format);
    StringBuilder sb = new StringBuilder();
    while (m.find()) {
      String value = switch (m.group(1)) {
        case "msg" -> msg;
        case "name" -> name;
        case "subname" -> subname;
        case "user" -> user;
        case "nickname" -> nickname;
        default -> "";
      };
      m.appendReplacement(sb, Matcher.quoteReplacement(value == null ? "" : value));
    }
    m.appendTail(sb);
    return sb.toString();
  }

  /** PAPI + 颜色码 → 组件 */
  private static Component toComponent(@Nullable Player player, String text) {
    String parsed = Joyous.i18n.getPHparsed(player, text);
    return LegacyComponentSerializer.legacySection().deserialize(MCColor.parse(parsed));
  }
}
