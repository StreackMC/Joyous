package com.github.streackmc.Joyous.SMenu;

import java.nio.file.Files;
import java.nio.file.Path;

import com.github.streackmc.Joyous.Joyous;
import com.github.streackmc.Joyous.logger;
import com.github.streackmc.Joyous._Model.JoyousModel;
import com.github.streackmc.StreackLib.utils.SFile;

public abstract class SMenuMain extends JoyousModel {
  public static final String MODEL_NAME = "SMenu";

  public SMenuMain() {
  };

  volatile static Path MENU_PATH = Joyous.dataPath.toPath().resolve(NAMES.MENU_PATH);

  public final static class NAMES {
    /** 菜单目录 */
    public final static String MENU_PATH = "models/SMenu/";
    /** 默认菜单文件名 */
    public final static String MENU_FILE_DEFAULT = "models/SMenu.default.json";
    /** 权限前缀 */
    public final static String PERMISSION_PREFIX = "joyous.smenu.";
  };

  @Override
  public void onEnable() throws Exception {
    if (Files.notExists(MENU_PATH.resolve("example.jmenu"))) {
      try {
        SFile.mv(Joyous.getResourceAsFile("/" + NAMES.MENU_PATH), MENU_PATH.resolve("example.jmenu").toFile());
      } catch (Exception e) {
        logger.err("警告：无法写入 %s ： %s", NAMES.MENU_PATH + "example.jmenu", e.getLocalizedMessage(), e);
      }
    }
  };

  @Override
  public void onDisable() throws Exception {
  };
}