package com.github.streackmc.Joyous.PlayerTitle;

import java.nio.file.Files;
import java.nio.file.Path;

import javax.annotation.Nullable;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import com.github.streackmc.Joyous.Joyous;
import com.github.streackmc.Joyous.Joyous.PermDef;
import com.github.streackmc.Joyous.logger;
import com.github.streackmc.StreackLib.utils.MCColor;
import com.github.streackmc.StreackLib.utils.SConfig;
import com.github.streackmc.StreackLib.utils.SFile;

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

  // 服务实例
  public static final PlayerTitlePHAPI PlaceholderService = new PlayerTitlePHAPI();
  public static final PlayerTitleCommand CommandService = new PlayerTitleCommand();

  /** 称号列表 */
  public static SConfig titleList;

  public static final void onEnable() {
    NAMES.PLAYER_USING_TITLE_NAMESPACED = new NamespacedKey(Joyous.plugin, NAMES.PLAYER_USING_TITLE);
    Joyous.addPermissions(PermDef.none("joyous.titles", "用于决定一个玩家是否具有指定称号"));
    if (Files.notExists(CONF_PATH)) {
      try {
        logger.debug("检查到 %s 不存在，自动新建默认文件", CONF_PATH);
        SFile.mv(Joyous.getResourceAsFile("/" + NAMES.CONF_FILE), CONF_PATH.toFile());
      } catch (Exception e) {
        logger.err("警告：无法写入 %s ： %s", NAMES.CONF_FILE, e.getLocalizedMessage(), e);
      }
    }
    titleList = new SConfig(CONF_PATH, "yml");
    titleList.putString("titles.empty", "");
    PlaceholderService.register();
    CommandService.register();
  }
  
  public static final void onDisable() {
    PlaceholderService.unregister();
  }

  /** 获取指定称号 */
  public static String getTitle(String titleId) {
    String title = titleList.getString(titleId, "");
    if (title.isEmpty()) {
      return "";
    } else {
      return String.format("&r&7%s&r&f%s&r&7%s&r",
          Joyous.conf.getString("PlayerTitle.prefix", "「"),
          title,
          Joyous.conf.getString("PlayerTitle.suffix", "」"));
    }
  }

  /** 获取玩家绑定的称号 */
  public static final String getTitle(Player player) {
    PersistentDataContainer pdc = player.getPersistentDataContainer();

    // 先获取玩家设置的称号
    String titleId = pdc.get(NAMES.PLAYER_USING_TITLE_NAMESPACED, PersistentDataType.STRING);
    if (titleId == null || titleId.equals("empty") || titleId.isEmpty()) {
      // 没有设置就返回
      return "";
    }

    // 检查是否过期
    if (!checkTitlePermission(player, titleId)) {
      player.sendMessage(Joyous.i18n.tr("titles.status.outdated"));
      logger.info("玩家 [%s] 持有的称号 [%s] 已过期", player.getName(), titleId);
      pdc.set(NAMES.PLAYER_USING_TITLE_NAMESPACED, PersistentDataType.STRING, "empty");
      return "";
    }

    // 获取称号
    String title = titleList.getString(titleId, "");
    if (title.isEmpty()) {
      title = "";
      player.sendMessage(Joyous.i18n.tr("titles.status.missing"), titleId);
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
  public static final void setTitle(Player player,@Nullable String titleId,@Nullable Boolean slience,@Nullable Boolean forced) throws IllegalArgumentException {
    PersistentDataContainer pdc = player.getPersistentDataContainer();

    // 如果是移除模式
    if (titleId == null || titleId.isEmpty() || titleId.isBlank()) {
      pdc.set(NAMES.PLAYER_USING_TITLE_NAMESPACED, PersistentDataType.STRING, "empty");
      return;
    }

    if (forced == null || !forced) {
      // 检查是否存在
      if (titleList.getString(titleId, null) == null)
        throw new IllegalArgumentException(Joyous.i18n.tr("titles.set.unknown"));

      // 检查是否持有
      if (!checkTitlePermission(player, titleId))
        throw new IllegalArgumentException(Joyous.i18n.tr("titles.status.not_have_yet"));
    }
    
    // 设置
    pdc.set(NAMES.PLAYER_USING_TITLE_NAMESPACED, PersistentDataType.STRING, titleId);

    // 通知目标玩家
    if (!(slience == null || slience)) player.sendMessage(Joyous.i18n.tr("titles.set.done"), getTitle(player));
  }

  /** 判断是否具有权限 <p> 仅检查不移除 */
  public static final boolean checkTitlePermission(Player player, String titleId) {
    if (!player.hasPermission(NAMES.PERMISSION_PREFIX + titleId)) {
      return false;
    } else {
      return true;
    }
  }
}
