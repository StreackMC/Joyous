package com.github.streackmc.Joyous.Common;

import java.io.FileNotFoundException;
import java.nio.file.Path;

import com.github.streackmc.Joyous.Joyous;
import com.github.streackmc.Joyous.utils.model_abstract.JoyousModel;
import com.github.streackmc.StreackLib.types.SConfig;

public class CommonMain extends JoyousModel {
  public String MODEL_NAME() {
    return "Common";
  }

  public CommonMain() {
  };

  private static volatile Path CONF_PATH = Joyous.dataPath.toPath().resolve(NAMES.CONF_FILE);

  public static final class NAMES {
    public static final String CONF_FILE = "models/Common.yml";
    public static final String CMD_PERMISSION_PREFIX = "joyous.commands.common.";
    public static final String PERMISSION_PREFIX = "joyous.common.";

    public static String PERMISSION_PREFIX(String txt) {
      return PERMISSION_PREFIX + txt;
    }

    public static String CMD_PERMISSION_PREFIX(String txt) {
      return CMD_PERMISSION_PREFIX + txt;
    }
  }

  // 服务实例
  public static volatile CommonPhapi PlaceholderService = new CommonPhapi();
  public static volatile CommonCmd CommandService = new CommonCmd();

  // 配置数据
  public static volatile SConfig conf = null;

  // ------------------------------------------------------------------------
  // 生命周期
  // ------------------------------------------------------------------------

  @Override
  public void onEnable() throws FileNotFoundException, Exception {
    // 载入配置文件
    if (!Joyous.requireFileAndCheck(NAMES.CONF_FILE, NAMES.CONF_FILE, null)) {
      throw new FileNotFoundException("无法找到文件" + CONF_PATH);
    }
    conf = new SConfig(Joyous.dataPath.toPath().resolve(NAMES.CONF_FILE), SConfig.TYPES.YAML);
    conf.setAutoReload(true);

    // 注册服务
    Joyous.PlaceholderService.registerParser(PlaceholderService);
    CommandService.register();
  }

  @Override
  public void onDisable() {
    Joyous.PlaceholderService.unregisiterParser(PlaceholderService);
    PlaceholderService = null;
  }
}