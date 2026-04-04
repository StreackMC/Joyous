package com.github.streackmc.Joyous.EnvExport;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import com.github.streackmc.Joyous.PHAPI;
import com.github.streackmc.Joyous.logger;

public class EnvExport {
    public static final class NAMES {
    public static final String CONF_FILE = "models/EnvExport.yml";
    public static final String PERMISSION_PREFIX = "joyous.envexport.";

    public static String PERMISSION_PREFIX(String txt) {
      return PERMISSION_PREFIX + txt;
    }
  }

  // 服务实例
  public static PHAPI.ModelsPHAPI PlaceholderService;

  public void onEnable() {
    PlaceholderService = new EnvExport.EnvExportPHAPI();
  }

  public void onDisable() {
  }
  
  private EnvExport() {};

  public class EnvExportPHAPI implements PHAPI.ModelsPHAPI {// 这个模块比较简单就不解耦合了
    EnvExportPHAPI() {
    }

    public String onPlaceholderRequest(Player player, @NotNull String params) {
      // %joyous_entroprix_guarntee_counts_%
      if (params.toLowerCase().startsWith("entroprix_guarantee_counts_")) {
        logger.debug("PHAPI返回 EnvExport 的环境变量 [%s]", params.substring(27));
        return "";
      }

      return null; // 未知占位符返回 null
    }
  }

}
