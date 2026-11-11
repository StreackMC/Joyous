package com.github.streackmc.Joyous.utils.interact;

import javax.annotation.Nullable;

import org.bukkit.entity.Player;

import com.github.streackmc.Joyous.utils.i18n;
import com.github.streackmc.StreackLib.utils.MCColor;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

/**
 * 文本处理
 * <p>
 * 交互层里所有「把带颜色码的字符串变成组件」的地方都走这里，容器界面与文本输入共用一份。
 *
 * <p>
 * <b>为什么必须显式关掉斜体</b>：{@code §r} 只把装饰设成 {@code NOT_SET}，而原版渲染物品名、
 * 悬浮文本与聊天消息时会把「未设置」按「启用」处理，于是文本莫名其妙变斜体。
 * 这里把未显式设置的装饰一律写成 {@code false}；显式写了 {@code §l} 之类的仍然保留。
 *
 * @author Neonai
 * @since 0.7.0
 */
public final class Text {

  private Text() {
  }

  /**
   * 把带 {@code &} 或 {@code §} 颜色码的字符串转成组件
   *
   * @param colorized 原始文本，可为 null
   */
  public static Component legacy(@Nullable String colorized) {
    return ensureReset(
        LegacyComponentSerializer.legacySection().deserialize(colorized == null ? "" : colorized));
  }

  /**
   * 界面标题等需要解析占位符的文本：先过 PAPI，再转组件
   *
   * @param player 用于解析玩家相关占位符，可为 null
   * @param raw    原始文本
   */
  public static Component title(@Nullable Player player, @Nullable String raw) {
    return legacy(MCColor.parse(i18n.getPHparsed(player, raw == null ? "" : raw)));
  }

  /** 确保未显式设置的文字装饰为 false */
  private static Component ensureReset(Component component) {
    var style = component.style();
    return component.style(s -> {
      for (TextDecoration dec : TextDecoration.values()) {
        if (style.decoration(dec) == TextDecoration.State.NOT_SET) {
          s.decoration(dec, false);
        }
      }
    });
  }
}
