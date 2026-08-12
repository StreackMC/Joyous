package com.github.streackmc.Joyous.APIHolders;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.json.simple.JSONObject;
import org.nanohttpd.protocols.http.NanoHTTPD;
import org.nanohttpd.protocols.http.request.Method;
import org.nanohttpd.protocols.http.response.Response;
import org.nanohttpd.protocols.http.response.Status;

import com.github.streackmc.Joyous.Joyous;
import com.github.streackmc.Joyous.i18n;
import com.github.streackmc.Joyous.jlogger;
import com.github.streackmc.StreackLib.types.SConfig;

public class WebPhAPI {
  /** 启用对PlaceholderAPI的查询支持 */
  static void enablePH(String path) throws Exception {
    APIHoldersMain.httpServer.registerHandler(path, session -> {
      try {
        /* 仅处理 GET */
        if (!Method.GET.equals(session.getMethod())) {
          return Response.newFixedLengthResponse(Status.METHOD_NOT_ALLOWED,
              NanoHTTPD.MIME_PLAINTEXT, "Method GET Allowed Only.");
        }

        /* 提取 URL ? 之后的原始查询字符串，作为 JSON5 解析 */
        String uri = session.getUri();
        String queryString = "";
        int qIndex = uri.indexOf('?');
        if (qIndex < 0 || qIndex + 1 >= uri.length()) {
          return newErrorResponse("Missing query string. Expected JSON5 after '?'.");
        }
        queryString = URLDecoder.decode(uri.substring(qIndex + 1), StandardCharsets.UTF_8);

        SConfig input;
        try {
          input = new SConfig(queryString, "jsonc");
        } catch (Exception e) {
          return newErrorResponse("Invalid JSON5 query: " + e.getLocalizedMessage());
        }

        // 解析 target (可为 String 或 null)
        String outerTarget = input.getString("target");
        if (outerTarget != null && outerTarget.isBlank()) outerTarget = null;

        // 解析 query 数组
        List<Object> rawQuery = input.getList("query");
        if (rawQuery == null || rawQuery.isEmpty()) {
          return newErrorResponse("Missing 'query' array.");
        }

        // 逐项处理
        List<String> results = new ArrayList<>();
        for (Object item : rawQuery) {
          if (item instanceof String text) {
            results.add(resolvePlaceholder(text, outerTarget));
          } else if (item instanceof Map<?, ?> map) {
            String innerTarget = map.containsKey("target") ? toStringOrNull(map.get("target")) : null;
            Object keyObj = map.get("key");
            if (keyObj == null) {
              results.add("");
            } else {
              String key = keyObj.toString();
              results.add(resolvePlaceholder(key, innerTarget));
            }
          } else {
            // 未知类型 → 空结果
            results.add("");
          }
        }

        JSONObject body = new JSONObject();
        body.put("timestamp", System.currentTimeMillis());
        body.put("result", results);
        Response rsp = Response.newFixedLengthResponse(Status.OK,
            "application/json", body.toJSONString());
        rsp.addHeader("Access-Control-Allow-Origin", APIHoldersMain.CONF.corsHeader());
        return rsp;

      } catch (Exception e) {
        jlogger.err("无法处理PlaceholderAPI查询：" + e.getLocalizedMessage(), e);
        return newErrorResponse("Internal Server Error: " + e.getLocalizedMessage());
      }
    });
    jlogger.info("已注册PlaceholderAPI查询处理器： " + path);
  }

  /**
   * 解析单个 Placeholder 并返回结果。
   *
   * @param key    占位符键（可能已含 % 或未含）
   * @param target 目标玩家名，null 表示服务端上下文
   */
  private static String resolvePlaceholder(String key, String target) {
    if (key == null || key.isBlank()) return "";

    // 智能 % 包裹：两侧都有 % 则保持原样，否则包裹
    String wrapped;
    if (key.startsWith("%") && key.endsWith("%")) {
      wrapped = key;
    } else {
      wrapped = "%" + key + "%";
    }

    // 确定解析目标
    Player targetPlayer = null;
    if (target != null && !target.isBlank()) {
      targetPlayer = Bukkit.getPlayer(target);
    }

    return i18n.getPHparsed(targetPlayer, wrapped);
  }

  /** 将 Object 安全转为 String，null / blank 视为 null */
  private static String toStringOrNull(Object obj) {
    if (obj == null) return null;
    String s = obj.toString();
    return s.isBlank() ? null : s;
  }

  /** 返回错误 JSON 响应体 */
  @SuppressWarnings("unchecked")
  private static Response newErrorResponse(String message) {
    JSONObject body = new JSONObject();
    body.put("timestamp", System.currentTimeMillis());
    body.put("error", message);
    Response rsp = Response.newFixedLengthResponse(Status.BAD_REQUEST,
        "application/json", body.toJSONString());
    rsp.addHeader("Access-Control-Allow-Origin", APIHoldersMain.CONF.corsHeader());
    return rsp;
  }
}