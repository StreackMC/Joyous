package com.github.streackmc.Joyous.SMenu;

import java.nio.file.Files;
import java.nio.file.Path;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;

import com.github.streackmc.Joyous.Joyous;
import com.github.streackmc.Joyous.logger;
import com.github.streackmc.Joyous._Model.JoyousModel;
import com.github.streackmc.StreackLib.utils.SFile;

/**
 * SMenu 模块主类
 * <p>
 * 负责菜单模块的生命周期管理（启用/禁用）、
 * 默认菜单文件释放、监听器和命令的注册。
 *
 * @author kdxiaoyi
 * @since 0.0.1
 */
public class SMenuMain extends JoyousModel {
  public static final String MODEL_NAME = "SMenu";

  public SMenuMain() {
  };

  public String MODEL_NAME() { return MODEL_NAME; };

  /** 菜单文件存储路径（惰性初始化） */
  volatile static Path MENU_PATH;
  /** 菜单物品 PDC 键（用于特化模式标记） */
  volatile static NamespacedKey MENU_ITEM_KEY;

  /** 管理器实例 */
  private volatile SMenuManager manager;
  /** 监听器实例 */
  private volatile SMenuListener listener;
  /** 命令处理器实例 */
  private volatile SMenuCommand command;

  public final static class NAMES {
    /** 菜单目录（相对于插件数据目录） */
    public final static String MENU_PATH = "models/SMenu/";
    /** 默认菜单模板资源路径 */
    public final static String MENU_FILE_DEFAULT = "models/SMenu.default.json";
    /** 权限前缀 */
    public final static String PERMISSION_PREFIX = "joyous.smenu.";
  };

  @Override
  public void onEnable() throws Exception {
    // 1. 初始化路径和 NamespacedKey
    MENU_PATH = Joyous.dataPath.toPath().resolve(NAMES.MENU_PATH);
    MENU_ITEM_KEY = new NamespacedKey(Joyous.plugin, "smenu_path");

    // 2. 确保菜单目录存在
    Files.createDirectories(MENU_PATH);

    // 3. 释放示例菜单文件
    if (Files.notExists(MENU_PATH.resolve("example.jmenu"))) {
      try {
        var tmp = Joyous.getResourceAsFile("/" + NAMES.MENU_FILE_DEFAULT);
        SFile.mv(tmp, MENU_PATH.resolve("example.jmenu").toFile());
        logger.info("已释放示例菜单文件 example.jmenu");
      } catch (Exception e) {
        logger.warn("无法写入 %s ： %s", MENU_PATH.resolve("example.jmenu"), e.getLocalizedMessage());
      }
    }

    // 4. 创建管理器（缓存 TTL：5 分钟）
    long ttl = 1000L * 60 * 5;
    manager = new SMenuManager(Joyous.plugin, ttl);

    // 5. 注册监听器
    listener = new SMenuListener(manager);
    Bukkit.getPluginManager().registerEvents(listener, Joyous.plugin);

    // 6. 注册命令
    command = new SMenuCommand(manager);
    command.register();

    logger.info("SMenu 模块已启用（Floodgate 可用：" + manager.isFloodgateAvailable() + "）");
  };

  @Override
  public void onDisable() throws Exception {
    // 清理所有玩家的活跃菜单
    if (manager != null) {
      manager.ACTIVE_MENUS.clear();
      manager.invalidateAllCache();
    }
    logger.info("SMenu 模块已禁用");
  };
}