package com.github.streackmc.Joyous.JMenu;

import java.nio.file.Files;
import java.nio.file.Path;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;

import com.github.streackmc.Joyous.Joyous;
import com.github.streackmc.Joyous.jlogger;
import com.github.streackmc.Joyous._Model.JoyousModel;
import com.github.streackmc.StreackLib.utils.SFile;

/**
 * JMenu 模块主类
 * <p>
 * 负责菜单模块的生命周期管理（启用/禁用）、
 * 默认菜单文件释放、监听器和命令的注册。
 *
 * @author kdxiaoyi
 * @since 0.2.0
 */
public class JMenuMain extends JoyousModel {
  public static final String MODEL_NAME = "JMenu";

  public JMenuMain() {
  };

  public String MODEL_NAME() {
    return MODEL_NAME;
  };

  /** 菜单文件存储路径（惰性初始化） */
  volatile static Path MENU_PATH;
  /** 菜单物品 PDC 键（用于特化模式标记） */
  volatile static NamespacedKey MENU_ITEM_KEY;

  /** 管理器实例 */
  private volatile JMenuManager manager;
  /** 监听器实例 */
  private volatile JMenuListener listener;
  /** 命令处理器实例 */
  private volatile JMenuCommand command;

  public final static class NAMES {
    /** 菜单目录（相对于插件数据目录） */
    public final static String MENU_PATH = "models/JMenu/";
    /** 默认菜单模板资源路径 */
    public final static String MENU_FILE_DEFAULT = "assets/JMenu.default.json";
    /** 权限前缀 */
    public final static String PERMISSION_PREFIX = "joyous.jmenu.";
  };

  @Override
  public void onEnable() throws Exception {
    // 1. 初始化路径和 NamespacedKey
    MENU_PATH = Joyous.dataPath.toPath().resolve(NAMES.MENU_PATH);
    MENU_ITEM_KEY = new NamespacedKey(Joyous.plugin, "jmenu_path");

    // 2. 确保菜单目录存在
    Files.createDirectories(MENU_PATH);

    // 3. 释放示例菜单文件
    if (Files.notExists(MENU_PATH.resolve("example.jmenu"))) {
      try {
        var tmp = Joyous.getResourceAsFile("/" + NAMES.MENU_FILE_DEFAULT);
        SFile.mv(tmp, MENU_PATH.resolve("example.jmenu").toFile());
        jlogger.info("已释放示例菜单文件 example.jmenu");
      } catch (Exception e) {
        jlogger.warn("无法写入 %s ： %s", MENU_PATH.resolve("example.jmenu"), e.getLocalizedMessage());
      }
    }

    // 4. 创建管理器
    long ttl = Joyous.conf.getLong("JMenu.cache_ttl", 1000L * 60 * 30);
    manager = new JMenuManager(Joyous.plugin, ttl);

    // 5. 注册监听器
    listener = new JMenuListener(manager);
    Bukkit.getPluginManager().registerEvents(listener, Joyous.plugin);

    // 6. 注册命令
    command = new JMenuCommand(manager);
    command.register();

    jlogger.info("JMenu 模块已启用（Floodgate 可用：" + manager.isFloodgateAvailable() + "）");
  };

  @Override
  public void onDisable() throws Exception {
    // 清理所有玩家的活跃菜单
    if (manager != null) {
      manager.ACTIVE_MENUS.clear();
      manager.invalidateAllCache();
    }
    jlogger.info("JMenu 模块已禁用");
  };

  static volatile Boolean isNewerModelUsableCache = null;

  public static boolean isNewerModelUsable() {
    if (isNewerModelUsableCache != null) {
      return isNewerModelUsableCache;
    }

    // 优先用 Paper 的 API 获取纯净版本号（如 "1.21.4" 或 "26.1"）
    String version = Bukkit.getMinecraftVersion();
    if (version == null || version.isEmpty()) {
      // 回退方案：从 Bukkit 版本字符串提取
      version = Bukkit.getBukkitVersion().split("-")[0];
    }

    String[] parts = version.split("\\.");
    if (parts.length < 2) {
      isNewerModelUsableCache = false;
      return false;
    }

    int first;
    try {
      first = Integer.parseInt(parts[0]);
    } catch (NumberFormatException e) {
      isNewerModelUsableCache = false;
      return false; // 无法解析，保守返回 false
    }

    // 新格式（26.x 及以后）：年份开头，天然 ≥ 1.21.4
    if (first >= 26) {
      isNewerModelUsableCache = true;
      return true;
    }

    // 旧格式（1.x.y）：按语义化版本比较
    if (first != 1) {
      isNewerModelUsableCache = first > 1;
      return first > 1; // 处理 2.x 等极端情况
    }

    int minor;
    try {
      minor = Integer.parseInt(parts[1]);
    } catch (NumberFormatException e) {
      isNewerModelUsableCache = false;
      return false;
    }

    int patch = 0;
    if (parts.length > 2) {
      try {
        patch = Integer.parseInt(parts[2]);
      } catch (NumberFormatException ignored) {
        // 忽略无法解析的补丁号，按 0 处理
      }
    }

    if (minor != 21) {
      isNewerModelUsableCache = minor > 21;
      return isNewerModelUsableCache;
    }
    isNewerModelUsableCache = patch >= 4;
    return isNewerModelUsableCache;
  }
}