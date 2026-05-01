package com.github.streackmc.Joyous.Entroprix;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import com.github.streackmc.Joyous.logger;
import com.github.streackmc.Joyous._Model.JoyousPHAPIhandler;

public class EntroprixPHAPI implements JoyousPHAPIhandler {
  EntroprixPHAPI() {
  }

  public String onPlaceholderRequest(Player player, @NotNull String params) {
    if (player == null)
      return null; // 显然没有玩家怎么读

    // %joyous_entroprix_guarntee_counts_% → 返回玩家保底计数
    if (params.toLowerCase().startsWith("entroprix_guarantee_counts_")) {
      logger.debug("PHAPI返回 Entroprix 的保底[%s]计数", params.substring(27));
      return String.format("%s", EntroprixMain.Guarantee.getCounts(player, params.substring(27)));
    }
    
    // %joyous_entroprix_guarntee_tries_% → 返回玩家保底内抽数
    if (params.toLowerCase().startsWith("entroprix_guarantee_tries_")) {
      logger.debug("PHAPI返回 Entroprix 的保底[%s]内抽数", params.substring(26));
      return String.format("%s", EntroprixMain.Guarantee.getTries(player, params.substring(26)));
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
