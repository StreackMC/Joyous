package com.github.streackmc.Joyous.PlayerTitle;

import java.nio.file.Files;
import java.nio.file.Path;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.github.streackmc.Joyous.Joyous;
import com.github.streackmc.Joyous.logger;
import com.github.streackmc.StreackLib.utils.MCColor;
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

  final static Path CONF_PATH = Joyous.dataPath.toPath().resolve(NAMES.CONF_FILE);

  public final static class NAMES {
    /** 配置文件名 */
    public final static String CONF_FILE = "playerTitles.yml";
    /** 权限前缀 */
    public final static String PERMISSION_PREFIX = "joyous.titles.";
    /** 玩家正在使用的称号 */
    public final static String PLAYER_USING_TITLE = "score";
    /** 玩家正在使用的称号 */
    static NamespacedKey PLAYER_USING_TITLE_NAMESPACED;
  };

  public static PlayerTitlePHAPI PlaceholderService = null;
  /** 称号列表 */
  public static SConfig titleList;

  public static final void onEnable() {
    PlaceholderService = new PlayerTitleMain.PlayerTitlePHAPI();
    NAMES.PLAYER_USING_TITLE_NAMESPACED = new NamespacedKey(Joyous.plugin, NAMES.PLAYER_USING_TITLE);
    PlaceholderService.register();
    if (Files.notExists(CONF_PATH)) {
      try {
        logger.debug("检查到 %s 不存在，自动新建默认文件", CONF_PATH);
        SFile.mv(Joyous.getResourceAsFile(NAMES.CONF_FILE), CONF_PATH.toFile());
      } catch (Exception e) {
        logger.err("警告：无法写入 %s ： %s", NAMES.CONF_FILE, e.getLocalizedMessage(), e);
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
    PersistentDataContainer pdc = player.getPersistentDataContainer();

    // 先获取玩家设置的称号
    String titleId = pdc.get(NAMES.PLAYER_USING_TITLE_NAMESPACED, PersistentDataType.STRING);
    if (titleId.equals(null) || titleId.equals("empty") || titleId.isEmpty()) {
      // 没有设置就返回
      return "";
    }

    // 检查是否过期
    if (checkTitlePermission(player, titleId)) {
      player.sendMessage(Joyous.i18n.get("titles.outdated"));
      logger.info("玩家 [%s] 持有的称号 [%s] 已过期", player.getName(), titleId);
      pdc.set(NAMES.PLAYER_USING_TITLE_NAMESPACED, PersistentDataType.STRING, "empty");
      return "";
    }

    // 获取称号
    String title = titleList.getString("titles." + titleId, "");
    if (title.isEmpty()) {
      title = "";
      player.sendMessage(Joyous.i18n.get("titles.missing"));
      logger.warn("找不到玩家 [%s] 持有的称号 [%s]", player.getName(), titleId);
      pdc.set(NAMES.PLAYER_USING_TITLE_NAMESPACED, PersistentDataType.STRING, "empty");
    } else {
      title = String.format("&r&7%s&r&f%s&r&7%s&r",
          Joyous.conf.getString("PlayerTitle.prefix", "「"),
          title,
          Joyous.conf.getString("PlayerTitle.suffix", "」"));
      logger.debug("玩家 [%s] 持有的称号 [%s] 为 [%s]", player.getName(), titleId, title);
    }
    return MCColor.parse(title);
  }

  /** 设置称号 */
  public static final void setTitle(Player player, String titleId) {
  }

  /** 判断是否具有权限 <p> 仅检查不移除 */
  public static final boolean checkTitlePermission(Player player, String titleId) {
    if (!player.hasPermission(NAMES.PERMISSION_PREFIX + titleId)) {
      return false;
    } else {
      return true;
    }
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
