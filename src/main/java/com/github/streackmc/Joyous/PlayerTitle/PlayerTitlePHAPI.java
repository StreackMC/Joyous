package com.github.streackmc.Joyous.PlayerTitle;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import com.github.streackmc.Joyous.jlogger;
import com.github.streackmc.Joyous._Model.JoyousPHAPIhandler;

public class PlayerTitlePHAPI implements JoyousPHAPIhandler {
  PlayerTitlePHAPI() {
  }

  public String onPlaceholderRequest(Player player, @NotNull String params) {
    if (player == null)
      return null; // 显然没有玩家就没有称号

    // %joyous_title2% → 返回玩家称号，带有兼容性的
    if (params.toLowerCase().startsWith("title2")) {
      jlogger.debug("PHAPI请求寻找玩家的称号");
      return PlayerTitleMain.getTitle(player).replaceAll("§", "&");
    }

    // %joyous_title% → 返回玩家称号
    if (params.toLowerCase().startsWith("title")) {
      jlogger.debug("PHAPI请求寻找玩家的称号");
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
