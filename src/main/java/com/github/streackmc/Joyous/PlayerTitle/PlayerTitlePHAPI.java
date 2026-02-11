package com.github.streackmc.Joyous.PlayerTitle;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.github.streackmc.Joyous.Joyous;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;

public class PlayerTitlePHAPI extends PlaceholderExpansion {
  public PlayerTitlePHAPI() {
  }

  @Override
  @NotNull
  public String getIdentifier() {
    return "joyous"; // %joyous.xxx% 的前缀
  }

  @Override
  @NotNull
  public String getAuthor() {
    return "kdxiaoyi & StreackMC Team";
  }

  @Override
  @NotNull
  public String getVersion() {
    return Joyous.getVersion();
  }

  @Override
  public boolean persist() {
    return true; // 插件重载时不卸载此占位符
  }

  @Override
  public boolean canRegister() {
    return true; // 是否可以注册
  }

  @Override
  @Nullable
  public String onPlaceholderRequest(Player player, @NotNull String params) {
    if (player == null)
      return ""; // 显然没有玩家就没有称号

    // %joyous.title% → 返回玩家称号
    if (params.equalsIgnoreCase("name")) {
      return PlayerTitleMain.getTitle(player);
    }

    // 这里是没删掉的教程示例代码，懒就没删
    // %joyous.kills_<type>% → 带参数
    // if (params.startsWith("kills_")) {
    // String type = params.substring(6);
    // return String.valueOf(Joyous.plugin.getKills(player, type));
    // }
    return null; // 未知占位符返回 null
  }
}
