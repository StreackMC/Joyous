package com.github.streackmc.Joyous.EnvExport;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

import javax.annotation.Nullable;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import com.github.streackmc.Joyous.Joyous;
import com.github.streackmc.Joyous.logger;
import com.github.streackmc.Joyous._Model.JoyousModel;
import com.github.streackmc.Joyous._Model.JoyousPHAPIhandler;
import com.github.streackmc.StreackLib.types.SConfig;
import com.github.streackmc.StreackLib.utils.SFile;

public class EnvExport extends JoyousModel {
  public String MODEL_NAME() {
    return "EnvExport";
  }

  public static volatile Path CONF_PATH = Joyous.dataPath.toPath().resolve(NAMES.CONF_FILE);

  public static final class NAMES {
    public static final String CONF_FILE = "models/EnvExport.yml";
    public static final String PERMISSION_PREFIX = "joyous.envexport.";

    public static String PERMISSION_PREFIX(String txt) {
      return PERMISSION_PREFIX + txt;
    }
  }

  public static volatile JoyousPHAPIhandler PlaceholderService = new EnvExport.EnvExportPHAPI();
  public static volatile EnvExportCommand CommandService = new EnvExportCommand();
  public static SConfig conf;

  @Override
  public void onEnable() {
    CommandService.register();
    Joyous.PlaceholderService.registerParser(PlaceholderService);
    if (Files.notExists(CONF_PATH)) {
      try {
        logger.debug("检查到 %s 不存在，自动新建默认文件", CONF_PATH);
        SFile.mv(Joyous.getResourceAsFile("/" + NAMES.CONF_FILE), CONF_PATH.toFile());
      } catch (Exception e) {
        logger.err("警告：无法写入 %s ： %s", NAMES.CONF_FILE, e.getLocalizedMessage(), e);
      }
    }
    conf = new SConfig(CONF_PATH, "yml");
    conf.setAutoReload(true);
  }

  @Override
  public void onDisable() {
    Joyous.PlaceholderService.unregisiterParser(PlaceholderService);
  }

  public static String getEnv(String key, String defaultV) {
    String fallback = Objects.requireNonNullElse(defaultV, "");
    return conf.getString(key, fallback);
  }

  public static void setEnv(String key, @Nullable String value) {
    Objects.requireNonNull(key);
    if (value == null) {
      conf.remove(key);
    } else {
      conf.putString(key, value);
    }
  }

  public EnvExport() {};

  public static class EnvExportPHAPI implements JoyousPHAPIhandler {// 这个模块比较简单就不解耦合了
    EnvExportPHAPI() {
    }

    public String onPlaceholderRequest(Player player, @NotNull String params) {
      // %joyous_env_%
      if (params.toLowerCase().startsWith("env_")) {
        logger.debug("PHAPI返回 EnvExport 的环境变量 [%s]", params.substring(4));
        return getEnv(params.substring(4), "");
      }

      // %joyous_envput_%
      if (params.toLowerCase().startsWith("envput_")) {
        String[] v = params.substring(7).split(":", 2);
        if (v.length == 2) {
          logger.debug("PHAPI设置 EnvExport[%s] 的环境变量为 [%s]", v[0], v[1]);
          setEnv(v[0], v[1]);
        } else {
          logger.debug("PHAPI设置 EnvExport[%s] 的环境变量为 []", v[0]);
          setEnv(v[0], null);
        }
        return "v[1]";
      }

      return null; // 未知占位符返回 null
    }
  }

}
