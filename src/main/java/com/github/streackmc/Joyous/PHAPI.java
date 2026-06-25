package com.github.streackmc.Joyous;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import com.github.streackmc.Joyous._Model.JoyousPHAPIhandler;
import com.github.streackmc.StreackLib.StreackLib;

public class PHAPI {
  protected final List<JoyousPHAPIhandler> usableParser = new ArrayList<>();
  public final boolean available;
  public final PHAPI_Backend expansion;

  public void registerParser(JoyousPHAPIhandler handler) {
    usableParser.add(handler);
  }

  public void unregisiterParser(JoyousPHAPIhandler handler) {
    usableParser.remove(usableParser.indexOf(handler));
  }

  public PHAPI(boolean usable) {
    this.available = usable;
    if (usable) {
      this.expansion = new PHAPI_Backend();
      this.expansion.register();
    } else {
      this.expansion = null;
    }
  }

  /** 以指定处理器处理 Placeholder */
  public String parse(JoyousPHAPIhandler provider, Player player, @NotNull String params) {
    if (provider == null)
      return null;
    return provider.onPlaceholderRequest(player, params);
  }

  // 通用的处理一些占位符
  public class commonPHAPI implements JoyousPHAPIhandler {
    @Override
    public String onPlaceholderRequest(Player player, @NotNull String params) {
      // %joyous_time_% 以及 %joyous_time%
      if (params.toLowerCase().startsWith("time_")) {
        try {
          String timeFormat = params.substring(5);
          return StreackLib.formatTime(null, timeFormat);
        } catch (Exception ignored) {
        }
      } else if (params.toLowerCase().startsWith("time")) {
        try {
          return StreackLib.formatTime(null, "yyyy-MM-dd HH:mm:ss");
        } catch (Exception ignored) {
        }
      }

      // %joyous_player_% / %joyous_p_% 处理
      if (params.toLowerCase().startsWith("player_") && params.length() > 7 && player != null) {
        String playerResult = resolvePlayerMeta(player, params.substring(7));
        if (playerResult != null) { return playerResult; }
      } else if (params.toLowerCase().startsWith("p_") && params.length() > 2 && player != null) {
        String playerResult = resolvePlayerMeta(player, params.substring(2));
        if (playerResult != null) { return playerResult; }
      }

      // 无效
      return null;
    }
    
    private String resolvePlayerMeta(Player player, String filteredParam) {
      switch (filteredParam.toLowerCase()) {
        case "name":
          return player.getName();
        case "uuid":
          return player.getUniqueId().toString();
        case "exp": case "xp":
          return String.valueOf(player.getExp());
        case "level":
          return String.valueOf(player.getExpToLevel());
        case "exp_cooldown": case "xp_cooldown": case "exp_cd": case "xp_cd":
          return String.valueOf(player.getExpCooldown());
        case "health":
          return String.valueOf(player.getHealth());
        case "air":
          return String.valueOf(player.getRemainingAir());
        case "food":
          return String.valueOf(player.getFoodLevel());
        case "saturation":
          return String.valueOf(player.getSaturation());
        case "exhaustion":
          return String.valueOf(player.getExhaustion());
        default:
          return null;
      }
    }
  }
}
