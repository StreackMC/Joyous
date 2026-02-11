package com.github.streackmc.Joyous.PlayerTitle;

import java.nio.file.Files;
import java.nio.file.Path;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.github.streackmc.Joyous.Joyous;
import com.github.streackmc.Joyous.logger;
import com.github.streackmc.StreackLib.utils.SConfig;
import com.github.streackmc.StreackLib.utils.SFile;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;

/**
 * 玩家称号
 * 
 * @author kdxiaoyi
 * @since 0.0.1
 */
public class PlayerTitleMain {

  final static String CONF_NAME = "playerTitles.yml";
  final static Path CONF_PATH = Joyous.dataPath.toPath().resolve(CONF_NAME);

  public static PlayerTitlePHAPI PlaceholderService = null;
  public static SConfig titleList;

  public static final void onEnable() {
    PlaceholderService = new PlayerTitleMain.PlayerTitlePHAPI();
    PlaceholderService.register();
    if (Files.notExists(CONF_PATH)) {
      try {
        logger.debug("检查到 %s 不存在，自动新建默认文件", CONF_PATH);
        SFile.mv(Joyous.getResourceAsFile(CONF_NAME), CONF_PATH.toFile());
      } catch (Exception e) {
        logger.err("警告：无法写入 %s ： %s", CONF_NAME, e.getLocalizedMessage(), e);
      }
    }
    titleList = new SConfig(CONF_PATH, "yml");
    titleList.putString("titles.empty", "");
  }

  public static final void onDisable() {
    PlaceholderService.unregister();
  }

  /** 获取玩家绑定的称号 */
  public static final String getTitle(Player player) {
    return "";
  }

  /** 设置称号 */
  public static final void setTitle(Player player, String id) {
  }

  /** 判断是否具有权限 */
  public static final boolean checkTitlePermission(Player player, String id) {
    return false;
  }

  /** PlaceholderAPI注册类 */
  public static final class PlayerTitlePHAPI extends PlaceholderExpansion {
    public PlayerTitlePHAPI() {
    }

    @Override
    @NotNull
    public String getIdentifier() {
      return "joyous"; // %joyous.xxx% 的前缀
    }

    @Override
    @NotNull
    public String getAuthor() {
      return "kdxiaoyi & StreackMC Team";
    }

    @Override
    @NotNull
    public String getVersion() {
      return Joyous.getVersion();
    }

    @Override
    public boolean persist() {
      return true; // 插件重载时不卸载此占位符
    }

    @Override
    public boolean canRegister() {
      return true; // 是否可以注册
    }

    @Override
    @Nullable
    public String onPlaceholderRequest(Player player, @NotNull String params) {
      if (player == null)
        return ""; // 显然没有玩家就没有称号

      // %joyous.title% → 返回玩家称号
      if (params.equalsIgnoreCase("name")) {
        return getTitle(player);
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
}
