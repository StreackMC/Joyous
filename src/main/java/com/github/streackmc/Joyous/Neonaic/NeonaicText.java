package com.github.streackmc.Joyous.Neonaic;

import org.bukkit.command.CommandSender;

import com.github.streackmc.Joyous.Joyous;
import com.github.streackmc.StreackLib.utils.MCColor;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

/**
 * Neonaic 模块的文本工具：把 i18n / legacy 颜色字符串统一转成 Adventure {@link Component}，
 * 避免使用已弃用的 {@code CommandSender#sendMessage(String)}。
 *
 * @since 0.3.0
 */
public final class NeonaicText {

  private NeonaicText() {
  }

  /** i18n 翻译（含 PAPI 与颜色解析）→ 组件 */
  public static Component tr(String key, Object... args) {
    return LegacyComponentSerializer.legacySection().deserialize(Joyous.i18n.tr(key, args));
  }

  /** legacy（{@code &} 颜色码）字符串 → 组件 */
  public static Component of(String legacy) {
    return LegacyComponentSerializer.legacySection().deserialize(MCColor.parse(legacy));
  }

  public static void send(CommandSender target, Component component) {
    target.sendMessage(component);
  }
}
