package com.github.streackmc.Joyous.Entroprix;

import java.nio.file.Files;
import java.nio.file.Path;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;

import com.github.streackmc.Joyous.Joyous;
import com.github.streackmc.Joyous.Joyous.PermDef;
import com.github.streackmc.Joyous.logger;
import com.github.streackmc.StreackLib.utils.SConfig;
import com.github.streackmc.StreackLib.utils.SFile;

public class EntroprixMain {
  final static Path CONF_PATH = Joyous.dataPath.toPath().resolve(NAMES.CONF_FILE);

  public final static class NAMES {
    /** 配置文件名 */
    public final static String CONF_FILE = "models/Entroprix.yml";
    /** 权限前缀 */
    public final static String PERMISSION_PREFIX = "joyous.entroprix.";
    /** 附加权限前缀 */
    public final static String PERMISSION_PREFIX(String txt) {
      return PERMISSION_PREFIX + txt;
    };
    /** 玩家正在使用的称号 */
    public final static String PLAYER_GUARNTEE = "entroprix.guarntee";
    /** 玩家正在使用的称号 */
    static NamespacedKey PLAYER_GUARNTEE_NAMESPACED;
  };

  // 服务实例
  public static final EntroprixPHAPI PlaceholderService = new EntroprixPHAPI();
  public static final EntroprixCommand CommandService = new EntroprixCommand();

  /** 卡池列表 */
  public static SConfig poolList;

  public static final void onEnable() {
    NAMES.PLAYER_GUARNTEE_NAMESPACED = new NamespacedKey(Joyous.plugin, NAMES.PLAYER_GUARNTEE);
    Joyous.addPermissions(PermDef.none("joyous.titles", "用于决定一个玩家是否具有指定称号"));
    if (Files.notExists(CONF_PATH)) {
      try {
        logger.debug("检查到 %s 不存在，自动新建默认文件", CONF_PATH);
        SFile.mv(Joyous.getResourceAsFile("/" + NAMES.CONF_FILE), CONF_PATH.toFile());
      } catch (Exception e) {
        logger.err("警告：无法写入 %s ： %s", NAMES.CONF_FILE, e.getLocalizedMessage(), e);
      }
    }
    poolList = new SConfig(CONF_PATH, "yml");
    poolList.putString("titles.empty", "");
    PlaceholderService.register();
    CommandService.register();
  }

  public static final void onDisable() {
    PlaceholderService.unregister();
  }

  public static void roll(Player player, String poolName) {
  }

  public static class guarntee {
    public static void add(Player player, Integer i) {
    }

    public static Integer get(Player player) {
    }

    public static void remove(Player player) {
    }

    public static void remove(Player player, Integer i) {
    }
  }

}