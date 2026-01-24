package com.github.streackmc.Joyous.APIHolders;

import static fi.iki.elonen.NanoHTTPD.newFixedLengthResponse;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.json.simple.JSONObject;

import com.github.streackmc.Joyous.Joyous;
import com.github.streackmc.Joyous.logger;
import com.github.streackmc.StreackLib.utils.HTTPServer;

import fi.iki.elonen.NanoHTTPD;
import me.clip.placeholderapi.PlaceholderAPI;

/**
 * APIHolders 主类
 * 继承自 StreackMC/APIHolders 这一独立项目的代码
 * @author KimiAI 编写
 * @author kdxiaoyi 审计
 * @since 0.0.1
 */
public class APIHoldersMain {

  public static HTTPServer httpServer;
  public static List<?> rawList;
  public static boolean whiteMode;
  public static String corsHeader;
  public static String phPath;
  public static String statusPath;

  /**
   * 
   * @throws Exception
   * @since 0.0.1
  */
 public static void onEnable() throws Exception {
   logger.info("[APIHolders] 正在启用……");
   rawList = Joyous.conf.getList("APIHolders.whitelist");
    whiteMode = Joyous.conf.getBoolean("APIHolders.whitelist", false);
    corsHeader = Joyous.conf.getString("APIHolders.cors", "*");
    phPath = Joyous.conf.getString("APIHolders.path.ph", "/api/placeholder");
    statusPath = Joyous.conf.getString("APIHolders.path.status", "/api/status");
    try {
      if (!phPath.isEmpty()) {
        logger.info("[APIHolders] 正在启用PlaceholderAPI查询处理器…… @ " + phPath);
        enablePH(phPath);
      } else {
        logger.info("[APIHolders] 没有启用PlaceholderAPI查询处理器");
      }
    } catch (Exception e) {
      throw new Exception("无法注册PlaceholderAPI查询处理器：" + e.getLocalizedMessage(), e);
    }
    try {
      if (!statusPath.isEmpty()) {
        logger.info("[APIHolders] 正在启用StatusAPI查询处理器…… @ " + statusPath);
        enableStatus(statusPath);
      } else {
        logger.info("[APIHolders] 没有启用StatusAPI查询处理器");
      }
    } catch (Exception e) {
      throw new Exception("无法注册StatusAPI查询处理器：" + e.getLocalizedMessage(), e);
    }
    logger.info("[APIHolders] 已启用");
  }

  /**
   * 
   * @throws Exception 
   * @since 0.0.1
   */
  public static void onDisable() throws Exception {
    logger.info("[APIHolders] 正在禁用……");
    try {
      httpServer.removeHandler(phPath);
      httpServer.removeHandler(statusPath);
    } catch (Exception e) {
      throw new Exception("[APIHolders] 无法移除事件处理器：" + e.getLocalizedMessage(), e);
    }
    logger.info("[APIHolders] 已禁用");
  }
  
  private static void enablePH(String path) throws Exception {
    // 启用对PlaceholderAPI的查询支持
    httpServer.registerHandler(path, session -> {
      try {
        /* 仅处理 GET */
        if (!NanoHTTPD.Method.GET.equals(session.getMethod())) {
          return newFixedLengthResponse(NanoHTTPD.Response.Status.METHOD_NOT_ALLOWED,
            NanoHTTPD.MIME_PLAINTEXT, "Method GET Allowed Only.");
        }
        /* 读取参数 */
        Map<String, List<String>> param = session.getParameters();
        String query;
        String target;
        if (param.get("query") == null && param.get("text") == null) {
          return newPlaceholderJsonResponse(400, "Bad Request: Missing parameter [query] or [text].", null, null, null);
        } //TODO:添加查询缓存机制
        if (param.get("query") != null) {
          query = String.join("", param.get("query"));
          if (!query.startsWith("%")) {
            query = "%" + query;
          }
          if (!query.endsWith("%")) {
            query = query + "%";
          }
        } else {
          query = String.join("", param.get("text"));
          if (!Joyous.conf.getBoolean("APIHolders.allow_blurred_ph", false)) {
            return newPlaceholderJsonResponse(403, "Forbidden: The Query [text] was forbidden by server admin. Contact them for help.", null, null, null);
          }
        }
        if (param.get("target") == null) {
          target = null;
        } else {
          target = String.join("", param.get("target"));
        }
        /* 名单过滤 */
        if (!isUsableHolder(query)) {
          return newPlaceholderJsonResponse(403, "Forbidden: The Placeholder was forbidden by server admin. Contact them for help.", null, null, null);
        }
        /* 解析目标并返回 */
        String parsed;
        if (target == null || target.equalsIgnoreCase("server") || target.equalsIgnoreCase("console")) {
          parsed = PlaceholderAPI.setPlaceholders(null, query);
          return newPlaceholderJsonResponse(200, "OK: Operation has been completed successfully.", parsed, null, null);
          // TODO: 兼容离线玩家处理并接入StreackLib的玩家
        } else {
          Player targetPlayer = Bukkit.getPlayer(target);
          parsed = PlaceholderAPI.setPlaceholders(targetPlayer, query);
          if (targetPlayer == null) {
            return newPlaceholderJsonResponse(200, "OK: Operation has been completed successfully.", parsed, null, null);
          } else {
            return newPlaceholderJsonResponse(203, "OK: Notice that your target player is offline or can't be found.", parsed, null, null);
          }
        }
      } catch (Exception e) {
        e.printStackTrace();
        return newPlaceholderJsonResponse(500, "Internal Server Error: Unknown error emerged.", null, null, null);
      }
    });
    logger.info("[APIHolders] 已注册PlaceholderAPI查询处理器： " + path);
  }

  private static void enableStatus(String path) throws Exception {
    // 启用对StatusAPI的查询支持
    httpServer.registerHandler(path, session -> {
      try {
        /* 仅处理 GET */
        if (!NanoHTTPD.Method.GET.equals(session.getMethod())) {
          return newFixedLengthResponse(NanoHTTPD.Response.Status.METHOD_NOT_ALLOWED,
            NanoHTTPD.MIME_PLAINTEXT, "Method GET Allowed Only.");
        }
        return null;
      } catch (Exception e) {
        e.printStackTrace();
        return newPlaceholderJsonResponse(500, "Internal Server Error: Unknown error emerged.", null, null, null);
      }
    });
    logger.info("[APIHolders] 已注册StatusAPI查询处理器： " + path);
  }

  /**
   * 判定输入的Placeholder是否允许使用
   * @param placeholder 要判断的PlaceholderAPI
   * @return boolean
   * @since 0.0.1
   */
  private static boolean isUsableHolder(String placeholder) {
    if (rawList == null || rawList.isEmpty()) {
      /* 空名单：白名单默认拒绝，黑名单默认通过 */
      return !whiteMode;
    }
    boolean matchAny = rawList.stream().anyMatch(obj -> {
      if (obj instanceof String) {
        String str = (String) obj;
        /* 正则 */
        if (str.startsWith("regex:")) {
          try {
            return Pattern.compile(str.substring(6)).matcher(placeholder).find();
          } catch (PatternSyntaxException ignore) {
            return false;
          }
        }
        /* 普通字符串 */
        return str.toLowerCase().contains(placeholder.toLowerCase());
      }
      return false;
    });
    return whiteMode ? matchAny : !matchAny;
  }

  /**
   * 快速封装适用于Placeholder的JSON响应
   * @param int code: HTTP状态码，默认500
   * @param String info: 对状态码的解释，默认空
   * @param String mc: 以MC格式返回的结果，默认空
   * @param Long expire_at: 缓存过期时间戳，默认当前时间戳
   * @return 封装完毕的JSON对象
   * @since 0.0.1
   */
  @SuppressWarnings("unchecked")
  private static NanoHTTPD.Response newPlaceholderJsonResponse(Integer code, String info, String mc, Long timestamp, Long expire_at) {
    Long FALLBACK_TIMESTAMP = (Long) System.currentTimeMillis();

    // 处理参数
    if (code == null) { code = 500; }
    if (mc == null) { mc = ""; }
    if (info == null) { info = ""; }
    if (timestamp == null) { timestamp = FALLBACK_TIMESTAMP; }
    if (expire_at == null) { expire_at = FALLBACK_TIMESTAMP; }

    // 时间相关
    JSONObject cache = new JSONObject();
    cache.put("timestamp", timestamp);
    cache.put("expire_at", expire_at);

    // 响应的基本信息
    JSONObject status = new JSONObject();
    status.put("code", code);
    status.put("info", info);

    // 响应的结果
    JSONObject respond = new JSONObject();
    respond.put("mc", mc);
    respond.put("plain", mc.replaceAll("§[0-9a-zA-Z]", ""));

    // 拼接响应
    JSONObject root = new JSONObject();
    root.put("cache", cache);
    root.put("status", status);
    root.put("result", respond);
    NanoHTTPD.Response rsp = newFixedLengthResponse(NanoHTTPD.Response.Status.lookup(code),
      "application/json", root.toJSONString());
    rsp.addHeader("Access-Control-Allow-Origin", corsHeader);
    return rsp;
  }

  /**
   * 命令处理器
   */
  // @Override
  // public static boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
  //   if (!"apiholders".equalsIgnoreCase(command.getName())) {
  //     return false;
  //   }
  //   boolean isConsole = sender instanceof ConsoleCommandSender;
  //   if (!isConsole && !sender.hasPermission("apiholders.commmand")) {
  //     sender.sendMessage("§c未知或不存在的命令。");
  //     return true;
  //   }
  //   if (args == null || args.length == 0) {
  //     sender.sendMessage("§e用法: /apiholders <version|reload|debug>");
  //     return true;
  //   }
  //   String sub = args[0].toLowerCase(Locale.ROOT);
  //   switch (sub) {
  //     case "version":
  //       sender.sendMessage("APIHolders version: " + getDescription().getVersion());
  //       break;
  //     case "debug":
  //       sender.sendMessage(
  //           "§r§7========§l§aAPIHolders 调试信息§r§7 ========\n" +
  //           "§f系统信息：" + debugentry.generateDebugInfo()
  //       );
  //       break;
  //     case "reload":
  //       try {
  //         reloadConf();
  //         registerHttpHandler();
  //         sender.sendMessage("§a配置已重载。");
  //       } catch (Exception e) {
  //         sender.sendMessage("§c重载失败: " + e.getLocalizedMessage());
  //         logger.severe("配置重载时出错: " + e.getLocalizedMessage());
  //         e.printStackTrace();
  //       }
  //       break;
  //     default:
  //       sender.sendMessage("§e未知子命令. 用法: /apiholders <version|reload>");
  //   }
  //   return true;
  // }

}