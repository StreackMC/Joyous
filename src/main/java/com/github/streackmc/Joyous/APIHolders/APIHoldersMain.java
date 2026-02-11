package com.github.streackmc.Joyous.APIHolders;

import java.util.List;

import com.github.streackmc.Joyous.Joyous;
import com.github.streackmc.Joyous.logger;
import com.github.streackmc.StreackLib.utils.HTTPServer;

/**
 * APIHolders 主类
 * 继承自 StreackMC/APIHolders 这一独立项目的代码
 * 
 * @author KimiAI 编写
 * @author kdxiaoyi 审计
 * @since 0.0.1
 */
public class APIHoldersMain {

  public static HTTPServer httpServer;

  public static final class CONF {// 动态获取以支持热重载
    public static final boolean whiteMode() {
      return Joyous.conf.getBoolean("APIHolders.whitelist", false);
    }

    public static final String corsHeader() {
      return Joyous.conf.getString("APIHolders.cors", "*");
    }

    public static final List<?> rawList() {
      return Joyous.conf.getListOfString("APIHolders.whitelist");
    }

    public static final String phPath() {
      return Joyous.conf.getString("APIHolders.path.ph", "/api/placeholder");
    }
    public static final String statusPath() {
      return Joyous.conf.getString("APIHolders.path.status", "/api/status");
    }
  }

  /**
   * 
   * @throws Exception
   * @since 0.0.1
   */
  public static void onEnable() throws Exception {
    if (httpServer.equals(null)) {// 防止一些nb的情况
      logger.warn("StreackLib中的 httpServer 服务器已被禁用，无法继续启用");
      return;
    };
    try {
      if (!CONF.phPath().isEmpty()) {
        logger.info("正在启用PlaceholderAPI查询处理器…… @ " + CONF.phPath());
        WebPhAPI.enablePH(CONF.phPath());
      } else {
        logger.info("没有启用PlaceholderAPI查询处理器");
      }
    } catch (Exception e) {
      throw new Exception("无法注册PlaceholderAPI查询处理器：" + e.getLocalizedMessage(), e);
    }
    try {
      if (!CONF.statusPath().isEmpty()) {
        logger.info("正在启用StatusAPI查询处理器…… @ " + CONF.statusPath());
        WebStatusAPI.enableStatus(CONF.statusPath());
      } else {
        logger.info("没有启用StatusAPI查询处理器");
      }
    } catch (Exception e) {
      throw new Exception("无法注册StatusAPI查询处理器：" + e.getLocalizedMessage(), e);
    }
  }

  /**
   * 
   * @throws Exception
   * @since 0.0.1
   */
  public static void onDisable() throws Exception {
    if (httpServer.equals(null)) {
      return;
    }
    try {
      httpServer.removeHandler(CONF.phPath());
      httpServer.removeHandler(CONF.statusPath());
    } catch (Exception e) {
      throw new Exception("无法移除事件处理器：" + e.getLocalizedMessage(), e);
    }
  }

  /**
   * 命令处理器
   */
  // @Override
  // public static boolean onCommand(CommandSender sender, Command command, String
  // label, String[] args) {
  // if (!"apiholders".equalsIgnoreCase(command.getName())) {
  // return false;
  // }
  // boolean isConsole = sender instanceof ConsoleCommandSender;
  // if (!isConsole && !sender.hasPermission("apiholders.commmand")) {
  // sender.sendMessage("§c未知或不存在的命令。");
  // return true;
  // }
  // if (args == null || args.length == 0) {
  // sender.sendMessage("§e用法: /apiholders <version|reload|debug>");
  // return true;
  // }
  // String sub = args[0].toLowerCase(Locale.ROOT);
  // switch (sub) {
  // case "version":
  // sender.sendMessage("APIHolders version: " + getDescription().getVersion());
  // break;
  // case "debug":
  // sender.sendMessage(
  // "§r§7========§l§aAPIHolders 调试信息§r§7 ========\n" +
  // "§f系统信息：" + debugentry.generateDebugInfo()
  // );
  // break;
  // case "reload":
  // try {
  // reloadConf();
  // registerHttpHandler();
  // sender.sendMessage("§a配置已重载。");
  // } catch (Exception e) {
  // sender.sendMessage("§c重载失败: " + e.getLocalizedMessage());
  // logger.severe("配置重载时出错: " + e.getLocalizedMessage(), e);
  // }
  // break;
  // default:
  // sender.sendMessage("§e未知子命令. 用法: /apiholders <version|reload>");
  // }
  // return true;
  // }

}