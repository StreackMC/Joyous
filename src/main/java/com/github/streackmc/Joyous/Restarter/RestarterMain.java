package com.github.streackmc.Joyous.Restarter;

import java.nio.file.Files;
import java.nio.file.Path;

import com.github.streackmc.Joyous.Joyous;
import com.github.streackmc.Joyous.logger;
import com.github.streackmc.Joyous._Model.JoyousModel;
import com.github.streackmc.StreackLib.utils.SFile;

public class RestarterMain extends JoyousModel {
  public String MODEL_NAME() {
    return "Restarter";
  }

  private static volatile Path CONF_PATH = Joyous.dataPath.toPath().resolve(NAMES.CONF_FILE);

  public RestarterMain() {
  };

  public static final class NAMES {
    public static final String CONF_FILE = "models/Restarter.yml";
    public static final String LOG_FILE = "logs/Restarter";
    public static final String PERMISSION_PREFIX = "joyous.restarter.";

    public static String PERMISSION_PREFIX(String txt) {
      return PERMISSION_PREFIX + txt;
    }
  }

  // 服务实例
  public static volatile RestarterCommand CommandService = new RestarterCommand();

  // ------------------------------------------------------------------------
  // 生命周期
  // ------------------------------------------------------------------------

  @Override
  public void onEnable() {
    if (Files.notExists(CONF_PATH)) {
      try {
        logger.debug("检查到 %s 不存在，自动新建默认文件", CONF_PATH);
        SFile.mv(Joyous.getResourceAsFile("/" + NAMES.CONF_FILE), CONF_PATH.toFile());
      } catch (Exception e) {
        logger.err("警告：无法写入 %s ： %s", NAMES.CONF_FILE, e.getLocalizedMessage(), e);
      }
    }
    CommandService.register();
  }

  @Override
  public void onDisable() {
  }
}