package com.github.streackmc.Joyous;

import java.io.File;
import java.nio.file.Files;

import org.bukkit.entity.Player;

import com.github.streackmc.StreackLib.types.SConfig;
import com.github.streackmc.StreackLib.utils.MCColor;
import com.github.streackmc.StreackLib.utils.SFile;

public class i18n extends SConfig {
  public SConfig defaultMap;

  public i18n(File parent) {
    // 初始化翻译文件
    super(parent.toPath().resolve("language.yml"), "yml");
    this.setAutoReload(true);

    // 载入缺省翻译
    try {
      defaultMap = new SConfig(Joyous.getResourceAsFile("/language.yml"), "yml");
    } catch (Exception e) {
      logger.severe("无法载入默认语言文件：" + e.getLocalizedMessage(), e);
      defaultMap = null;
      Joyous.plugin.getPluginLoader().disablePlugin(Joyous.plugin);
    }

    // 如果没有释放文件则释放一个
    if (Files.notExists(Joyous.dataPath.toPath().resolve("language.yml"))) {
      try {
        SFile.cp(defaultMap.getFile(), this.getFile());
      } catch (Exception e) {
        logger.severe("无法释放语言文件：" + e.getLocalizedMessage(), e);
      }
    }
  }

  /**
   * 获取一个翻译
   * 
   * @param key
   * @param args 多余的参数用来 String.format
   * @return
   */
  public String tr(String key, Object... args) {
    String result = this.getString(key, "");
    if (result.isEmpty()) {
      result = defaultMap.getString(key, "[MISSING_TRANSLATION]");
      // 差量更新
      this.putString(key, result);
    }
    // 如果有额外参数，使用 String.format 格式化
    if (args.length > 0) {
      result = String.format(result, args);
    }

    // 经过 Placeholder 和 Color 映射后输出
    return MCColor.parse(Joyous.i18n.getPHparsed(null, result));
  }

  /** 安全获取 Placeholder 替换 */
  public String getPHparsed(Player p, String t) {
    if (Joyous.PHAPI_available && Joyous.PlaceholderService.expansion != null) {
      return Joyous.PlaceholderService.expansion.parseText(t, p);
    } else {
      return t;
    }
  }

}
