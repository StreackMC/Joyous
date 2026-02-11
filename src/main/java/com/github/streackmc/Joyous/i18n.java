package com.github.streackmc.Joyous;

import java.io.File;
import java.nio.file.Files;

import com.github.streackmc.StreackLib.utils.SConfig;
import com.github.streackmc.StreackLib.utils.SFile;

public class i18n extends SConfig {
  public SConfig defaultMap;

  public i18n(File parent) {
    // 初始化翻译文件
    super(parent.toPath().resolve("language.yml"), "yml");
    this.startAutoReload();

    // 载入缺省翻译
    try {
      defaultMap = new SConfig(Joyous.getResourceAsFile("language.yml"), "yml");
    } catch (Exception e) {
      logger.severe("无法载入默认语言文件：" + e.getLocalizedMessage(), e);
      defaultMap = null;
      Joyous.plugin.getPluginLoader().disablePlugin(Joyous.plugin);
    }

    // 如果没有释放文件则释放一个
    if (Files.notExists(Joyous.dataPath.toPath().resolve("language.yml"))) {
      try {
        SFile.mv(defaultMap.getFile(), this.getFile());
      } catch (Exception e) {
        logger.severe("无法释放语言文件：" + e.getLocalizedMessage(), e);
      }
    }
  }

  /**
   * 获取一个翻译
   * 
   * @param key
   * @return
   */
  public String get(String key) {
    String result = this.getString("key", "");
    if (result.isEmpty()) {
      result = defaultMap.getString(key, "[MISSING_TRANSLATION]");
      try {
        SFile.mv(defaultMap.getFile(), Joyous.dataPath.toPath().resolve("language.new.yml").toFile());
      } catch (Exception e) {
        logger.severe("未能更新翻译文件：" + e.getLocalizedMessage(), e);
      }
    }
    return result;
  }

}
