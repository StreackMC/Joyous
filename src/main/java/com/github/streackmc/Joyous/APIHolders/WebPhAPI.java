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

import fi.iki.elonen.NanoHTTPD;
import me.clip.placeholderapi.PlaceholderAPI;

public class WebPhAPI {
  /** 启用对PlaceholderAPI的查询支持 */
  static void enablePH(String path) throws Exception {
    APIHoldersMain.httpServer.registerHandler(path, session -> {
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
        } // TODO:添加查询缓存机制
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
            return newPlaceholderJsonResponse(403,
                "Forbidden: The Query [text] was forbidden by server admin. Contact them for help.", null, null, null);
          }
        }
        if (param.get("target") == null) {
          target = null;
        } else {
          target = String.join("", param.get("target"));
        }
        /* 名单过滤 */
        if (!isUsableHolder(query)) {
          return newPlaceholderJsonResponse(403,
              "Forbidden: The Placeholder was forbidden by server admin. Contact them for help.", null, null, null);
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
            return newPlaceholderJsonResponse(200, "OK: Operation has been completed successfully.", parsed, null,
                null);
          } else {
            return newPlaceholderJsonResponse(203, "OK: Notice that your target player is offline or can't be found.",
                parsed, null, null);
          }
        }
      } catch (Exception e) {
        logger.err("无法处理PlaceholderAPI查询：" + e.getLocalizedMessage(), e);
        return newPlaceholderJsonResponse(500, "Internal Server Error: Unknown error emerged.", null, null, null);
      }
    });
    logger.info("已注册PlaceholderAPI查询处理器： " + path);
  }

  /**
   * 判定输入的Placeholder是否允许使用
   * 
   * @param placeholder 要判断的PlaceholderAPI
   * @return boolean
   * @since 0.0.1
   */
  private static boolean isUsableHolder(String placeholder) {
    if (APIHoldersMain.CONF.rawList() == null || APIHoldersMain.CONF.rawList().isEmpty()) {
      /* 空名单：白名单默认拒绝，黑名单默认通过 */
      return !APIHoldersMain.CONF.whiteMode();
    }
    boolean matchAny = APIHoldersMain.CONF.rawList().stream().anyMatch(obj -> {
      if (obj instanceof String) {
        String str = (String) obj;
        /* 正则 */
        if (str.startsWith("regex:")) {
          try {
            return Pattern.compile(str.substring(6)).matcher(placeholder).find();
          } catch (PatternSyntaxException ignored) {
            logger.debug("忽略一处正则表达式错误：" + ignored.getLocalizedMessage(), ignored);
            return false;
          }
        }
        /* 普通字符串 */
        return str.toLowerCase().contains(placeholder.toLowerCase());
      }
      return false;
    });
    return APIHoldersMain.CONF.whiteMode() ? matchAny : !matchAny;
  }

  /**
   * 快速封装适用于Placeholder的JSON响应
   * 
   * @param int    code: HTTP状态码，默认500
   * @param String info: 对状态码的解释，默认空
   * @param String mc: 以MC格式返回的结果，默认空
   * @param Long   expire_at: 缓存过期时间戳，默认当前时间戳
   * @return 封装完毕的JSON对象
   * @since 0.0.1
   */
  @SuppressWarnings("unchecked")
  private static NanoHTTPD.Response newPlaceholderJsonResponse(Integer code, String info, String mc, Long timestamp,
      Long expire_at) {
    Long FALLBACK_TIMESTAMP = (Long) System.currentTimeMillis();

    // 处理参数
    if (code == null) {
      code = 500;
    }
    if (mc == null) {
      mc = "";
    }
    if (info == null) {
      info = "";
    }
    if (timestamp == null) {
      timestamp = FALLBACK_TIMESTAMP;
    }
    if (expire_at == null) {
      expire_at = FALLBACK_TIMESTAMP;
    }

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
    rsp.addHeader("Access-Control-Allow-Origin", APIHoldersMain.CONF.corsHeader());
    return rsp;
  }
}
