package com.github.streackmc.APIHolders;

import com.github.streackmc.StreackLib.StreackLib;
import com.github.streackmc.StreackLib.debugentry;
import com.github.streackmc.StreackLib.utils.HTTPServer;
import fi.iki.elonen.NanoHTTPD;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import java.util.Locale;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.json.simple.JSONObject;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import static fi.iki.elonen.NanoHTTPD.newFixedLengthResponse;

public class plugin extends JavaPlugin {
  private final Long CONFIG_VERSION = 000200L;

  public HTTPServer httpServer;
  public FileConfiguration conf;
  public String path;
  public Boolean whiteMode;
  public List<?> rawList;
  public String corsHeader;
  // public LiteralArgumentBuilder<CommandSourceStack> commandTree = Commands.literal("api-holders");

  @Override
  public void onEnable() {
    getLogger().info("正在启用APIHolders...");
    /* 检测 StreackLib */
    Plugin StreackLib_paper = Bukkit.getPluginManager().getPlugin("StreackLib");
    if (StreackLib_paper == null || !StreackLib_paper.isEnabled()) {
      getLogger().severe("启用失败：未检测到StreackLib");
      getServer().getPluginManager().disablePlugin(this);
      return;
    }
    /* 检测 PlaceholderAPI */
    if (!Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
      getLogger().severe("启用失败：未检测到PlaceholderAPI");
      getServer().getPluginManager().disablePlugin(this);
      return;
    }
    /* 读入 StreackLib:HTTPServer */
    httpServer = StreackLib.getHttpServer();
    if (httpServer == null) {
      getLogger().severe("启用失败：StreackLib的HTTPServer模块无法启用或无法与之通信。");
      getServer().getPluginManager().disablePlugin(this);
      return;
    }
    /* 读取配置 */
    reloadConf();
    /* 开启HTTPServer */
    registerHttpHandler();
  }
  @Override
  public void onDisable() {
    getLogger().info("正在禁用APIHolders...");
    try {
      httpServer.removeHandler(path);
    } catch (Exception ignored) {
    }
  }

  /**
   * 载入配置并格式化
   * @return void
   */
  private void reloadConf() {
    CheckConfigUpdate();
    if (path != null) {
      httpServer.removeHandler(path);
    }
    saveDefaultConfig();
    conf = getConfig();
    path = conf.getString("path", "/api/placeholder");
    whiteMode = conf.getBoolean("white-mode", true);
    rawList = conf.getList("list");
    corsHeader = conf.getString("cors-allowed", "*");
  }

    private void CheckConfigUpdate() {
    getLogger().info("正在检查配置文件：" + new File(getDataFolder(), "config.yml").getPath());
    if (StreackLib.conf.getLong("version", 0L) > CONFIG_VERSION) {
      getLogger().warning("你的配置文件版本过高？请勿自行修改或强行应用高版本配置文件，否则可能引发意料之外的错误。当前版本：" + StreackLib.conf.getInt("version", 0) + "，适配版本：" + CONFIG_VERSION);
    }
    if (StreackLib.conf.getLong("version", 0L) < CONFIG_VERSION) {
      getLogger().severe("注意：你的配置文件版本过低，请参阅config.new.yml修改你的配置文件；现在未配置的项将使用默认值。当前版本：" + StreackLib.conf.getInt("version", 0) + "，适配版本：" + CONFIG_VERSION);
      try(
        InputStream is = this.getResource("config.yml");
        OutputStream os = Files.newOutputStream(new File(getDataFolder(), "config.new.yml").toPath());
      ) {
          byte[] buffer = new byte[1024];
          int length;
          while ((length = is.read(buffer)) > 0) {
            os.write(buffer, 0, length);
          }
          os.close();
      } catch (Exception e) {
        getLogger().severe("配置文件更新失败：" + e.getMessage());
      }
    }
  }

  /**
   * 注册HTTPServer事件监听
   * @return void
   */
  private void registerHttpHandler() {
    try {
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
            return jsonResponse(400, "Bad Request: Missing parameter [query] or [text].", null, null, null);
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
          }
          if (param.get("target") == null) {
            target = null;
          } else {
            target = String.join("", param.get("target"));
          }
          /* 名单过滤 */
          if (!isUsableHolder(query)) {
            return jsonResponse(403, "Forbidden: The Placeholder was forbidden by server admin. Contact them for help.", null, null, null);
          }
          /* 解析目标并返回 */
          String parsed;
          if (target == null || target.equalsIgnoreCase("server") || target.equalsIgnoreCase("console")) {
            parsed = PlaceholderAPI.setPlaceholders(null, query);
            return jsonResponse(200, "OK: Operation has been completed successfully.", parsed, null, null);
            // TODO: 兼容离线玩家处理并接入StreackLib的玩家
          } else {
            Player targetPlayer = Bukkit.getPlayer(target);
            parsed = PlaceholderAPI.setPlaceholders(targetPlayer, query);
            if (targetPlayer == null) {
              return jsonResponse(200, "OK: Operation has been completed successfully.", parsed, null, null);
            } else {
              return jsonResponse(203, "OK: Notice that your target player is offline or can't be found.", parsed, null, null);
            }
          }
        } catch (Exception ex) {
          ex.printStackTrace();
          return jsonResponse(500, "Internal Server Error: Unknown error emerged.", null, null, null);
        }
      });
    } catch (Exception e) {
      getLogger().severe("目标路径已被占用，无法注册处理器：" + e.getMessage());
      e.printStackTrace();
      getServer().getPluginManager().disablePlugin(this);
    }
    getLogger().info("已注册 HTTP 监听路径: " + path);
  }

  /**
   * 判定输入的Placeholder是否允许使用
   * @param placeholder 要判断的PlaceholderAPI
   * @return boolean
   */
  private boolean isUsableHolder(String placeholder) {
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
        return str.equalsIgnoreCase(placeholder);
      }
      return false;
    });
    return whiteMode ? matchAny : !matchAny;
  }

  /**
   * 快速封装 JSON 响应
   * @param int code: HTTP状态码，默认500
   * @param String info: 对状态码的解释，默认空
   * @param String mc: 以MC格式返回的结果，默认空
   * @param Long expire_at: 缓存过期时间戳，默认当前时间戳
   * @return 封装完毕的JSON对象
   */
  @SuppressWarnings("unchecked")
  private NanoHTTPD.Response jsonResponse(Integer code, String info, String mc, Long timestamp, Long expire_at) {
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
  @Override
  public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
    if (!"apiholders".equalsIgnoreCase(command.getName())) {
      return false;
    }
    boolean isConsole = sender instanceof ConsoleCommandSender;
    if (!isConsole && !sender.hasPermission("apiholders.commmand")) {
      sender.sendMessage("§c未知或不存在的命令。");
      return true;
    }
    if (args == null || args.length == 0) {
      sender.sendMessage("§e用法: /apiholders <version|reload|debug>");
      return true;
    }
    String sub = args[0].toLowerCase(Locale.ROOT);
    switch (sub) {
      case "version":
        sender.sendMessage("APIHolders version: " + getDescription().getVersion());
        break;
      case "debug":
        sender.sendMessage(
            "§r§7========§l§aAPIHolders 调试信息§r§7 ========\n" +
            "§f系统信息：" + debugentry.generateDebugInfo()
        );
        break;
      case "reload":
        try {
          reloadConf();
          registerHttpHandler();
          sender.sendMessage("§a配置已重载。");
        } catch (Exception e) {
          sender.sendMessage("§c重载失败: " + e.getMessage());
          getLogger().severe("配置重载时出错: " + e.getMessage());
          e.printStackTrace();
        }
        break;
      default:
        sender.sendMessage("§e未知子命令. 用法: /apiholders <version|reload>");
    }
    return true;
  }

}