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

  private static void enableStatus(String path) throws Exception {
    // 启用对StatusAPI的查询支持
    httpServer.registerHandler(path, session -> {
      try {
        /* 仅处理 GET */
        if (!NanoHTTPD.Method.GET.equals(session.getMethod())) {
          return newFixedLengthResponse(NanoHTTPD.Response.Status.METHOD_NOT_ALLOWED,
              NanoHTTPD.MIME_PLAINTEXT, "Method GET Allowed Only.");
        }
        /* 构建状态数据（合并后的单一方法） */
        JSONObject statusData = buildServerStatusData();
        /* 返回JSON响应 */
        NanoHTTPD.Response rsp = newFixedLengthResponse(
            NanoHTTPD.Response.Status.OK,
            "application/json",
            statusData.toJSONString());
        rsp.addHeader("Access-Control-Allow-Origin", corsHeader);
        return rsp;
      } catch (Exception e) {
        e.printStackTrace();
        return newPlaceholderJsonResponse(500, "Internal Server Error: " + e.getMessage(), null, null, null);
      }
    });
    logger.info("[APIHolders] 已注册StatusAPI查询处理器： " + path);
  }

  /**
   * 构建服务器完整状态数据
   * 包含：online, retrieved_at, expires_at, version, players, motd, tps
   * 
   * @return 符合result.json结构的JSONObject（精简版）
   * @since 0.0.2
   */
  @SuppressWarnings("unchecked")
  private static JSONObject buildServerStatusData() {
    JSONObject data = new JSONObject();
    org.bukkit.Server server = Bukkit.getServer();
    long timestamp = System.currentTimeMillis();

    /* 基础状态 */
    data.put("online", true);
    data.put("retrieved_at", timestamp);
    data.put("expires_at", timestamp + 30000); // 30秒缓存

    /* 版本信息（移除protocol） */
    JSONObject version = new JSONObject();
    String rawVersion = server.getVersion();
    version.put("mc", "§f" + rawVersion);
    version.put("text", rawVersion);
    version.put("html", minecraftColorsToHtml("§f" + rawVersion));
    data.put("version", version);

    /* 玩家信息 */
    JSONObject players = new JSONObject();
    java.util.Collection<? extends org.bukkit.entity.Player> onlinePlayers = server.getOnlinePlayers();
    players.put("online", onlinePlayers.size());
    players.put("max", server.getMaxPlayers());

    org.json.simple.JSONArray sampleList = new org.json.simple.JSONArray();
    onlinePlayers.stream().limit(5).forEach(player -> {
      JSONObject p = new JSONObject();
      p.put("uuid", player.getUniqueId().toString());
      p.put("mc", player.getDisplayName());
      JSONObject pn = new JSONObject();
      pn.put("text", stripMinecraftColors(player.getDisplayName()));
      pn.put("html", minecraftColorsToHtml(player.getDisplayName()));
      p.put("name", pn);
      sampleList.add(p);
    });
    players.put("list", sampleList);
    data.put("players", players);

    /* MOTD信息 */
    JSONObject motd = new JSONObject();
    String rawMotd = server.getMotd();
    motd.put("mc", rawMotd);
    motd.put("text", stripMinecraftColors(rawMotd));
    motd.put("html", minecraftColorsToHtml(rawMotd));
    data.put("motd", motd);

    /* TPS信息（新增：live, avg_60s, avg_300s） */
    data.put("tps", getTPSDataAsJSON());

    logger.debug("[APIHolders] status数据构建完成：" + data.toString());

    return data;
  }

  /**
   * 获取服务器TPS数据（独立方法，使用反射兼容多版本）
   * 返回包含 live(实时), avg_60s(60秒平均), avg_300s(300秒平均) 的JSON对象
   * 
   * @return JSONObject 包含TPS数据，获取失败时返回默认值20.0
   * @author KimiAI
   * @author kdxiaoyi 审计
   * @since 0.0.2
   */
  @SuppressWarnings("unchecked")
  public static JSONObject getTPSDataAsJSON() {
    JSONObject tps = new JSONObject();
    try {
      double[] getTps = (double[]) Joyous.getServerTPS();
      tps.put("live", getTps[0]);
      tps.put("avg_1m", getTps[1]);
      tps.put("avg_5m", getTps[2]);
      tps.put("avg_15m", getTps[3]);
    } catch (Exception e) {
      logger.error("无法获取TPS：" + e.getLocalizedMessage());
      e.printStackTrace();
      tps.put("live", -1.0);
      tps.put("avg_1m", -1.0);
      tps.put("avg_5m", -1.0);
      tps.put("avg_15m", -1.0);
    }
    return tps;
  }

  /**
   * 将Minecraft颜色代码(§)转换为HTML
   * 支持：颜色代码、粗体(§l)、斜体(§o)、下划线(§n)、删除线(§m)、随机(§k)、重置(§r)
   * @param text 要处理的文本
   * @return 处理后的文本
   * @since 0.0.1
   */
  public static String minecraftColorsToHtml(String text) {
    if (text == null || text.isEmpty())
      return "<span></span>";

    StringBuilder html = new StringBuilder("<span>");
    StringBuilder currentText = new StringBuilder();

    boolean bold = false, italic = false, underline = false, strikethrough = false, obfuscated = false;
    String color = null;

    java.util.Map<Character, String> colors = new java.util.HashMap<>();
    colors.put('0', "#000000");
    colors.put('1', "#0000AA");
    colors.put('2', "#00AA00");
    colors.put('3', "#00AAAA");
    colors.put('4', "#AA0000");
    colors.put('5', "#AA00AA");
    colors.put('6', "#FFAA00");
    colors.put('7', "#AAAAAA");
    colors.put('8', "#555555");
    colors.put('9', "#5555FF");
    colors.put('a', "#55FF55");
    colors.put('b', "#55FFFF");
    colors.put('c', "#FF5555");
    colors.put('d', "#FF55FF");
    colors.put('e', "#FFFF55");
    colors.put('f', "#FFFFFF");

    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);

      if (c == '§' && i + 1 < text.length()) {
        char code = Character.toLowerCase(text.charAt(i + 1));

        if (currentText.length() > 0) {
          html.append(wrapSpan(currentText.toString(), color, bold, italic, underline, strikethrough, obfuscated));
          currentText = new StringBuilder();
        }

        if (code == 'r') {
          bold = italic = underline = strikethrough = obfuscated = false;
          color = null;
        } else if (colors.containsKey(code)) {
          color = colors.get(code);
        } else if (code == 'l')
          bold = true;
        else if (code == 'o')
          italic = true;
        else if (code == 'n')
          underline = true;
        else if (code == 'm')
          strikethrough = true;
        else if (code == 'k')
          obfuscated = true;

        i++;
      } else if (c == '\n') {
        if (currentText.length() > 0) {
          html.append(wrapSpan(currentText.toString(), color, bold, italic, underline, strikethrough, obfuscated));
          currentText = new StringBuilder();
        }
        html.append("<br>");
      } else {
        currentText.append(c);
      }
    }

    if (currentText.length() > 0) {
      html.append(wrapSpan(currentText.toString(), color, bold, italic, underline, strikethrough, obfuscated));
    }

    html.append("</span>");
    logger.debug("Minecraft颜色代码转换为HTML: " + text + " -> " + html.toString());
    return html.toString();
  }
  
  /**
   * 包装文本为带样式的span标签
   * @param text 要包装的文本
   * @param color 颜色代码（如#RRGGBB），null表示默认颜色
   * @param bold 是否加粗
   * @param italic 是否斜体
   * @param underline 是否下划线
   * @param strikethrough 是否删除线
   * @param obfuscated 是否乱码
   * @return 包装好的span标签
   * @since 0.0.1
   */
  public static String wrapSpan(String text, String color, boolean bold, boolean italic,
      boolean underline, boolean strikethrough, boolean obfuscated) {
    StringBuilder style = new StringBuilder();
    if (obfuscated)
      style.append("class=\"minecraft-format-obfuscated\" ");
    if (color != null)
      style.append("color: ").append(color).append(";");
    if (bold)
      style.append("font-weight: bold;");
    if (italic)
      style.append("font-style: italic;");

    String decoration = "";
    if (strikethrough && underline)
      decoration = "text-decoration: line-through underline;";
    else if (strikethrough)
      decoration = "text-decoration: line-through;";
    else if (underline)
      decoration = "text-decoration: underline;";
    style.append(decoration);

    return String.format("<span %s>%s</span>",
        style.length() > 0 ? "style=\"" + style.toString() + "\"" : "",
        text);
  }

  /**
   * 清除所有Minecraft颜色代码
   * @param text 要处理的文本
   * @return 处理后的文本
   * @since 0.0.1
   */
  public static String stripMinecraftColors(String text) {
    return text == null ? "" : text.replaceAll("§[0-9a-fA-Fk-oK-OrR]", "");
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