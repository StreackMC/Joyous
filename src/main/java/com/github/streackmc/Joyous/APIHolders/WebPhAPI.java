package com.github.streackmc.Joyous.APIHolders;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

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
import com.github.streackmc.StreackLib.StreackLib;
import com.github.streackmc.StreackLib.types.SConfig;

public class WebPhAPI {
  /** 启用对PlaceholderAPI的查询支持 */
  @SuppressWarnings("unchecked")
  static void enablePH(String path) throws Exception {
    APIHoldersMain.httpServer.registerHandler(path, session -> {
      try {
        /* 仅处理 GET */
        if (!Method.GET.equals(session.getMethod())) {
          return Response.newFixedLengthResponse(Status.METHOD_NOT_ALLOWED,
              NanoHTTPD.MIME_PLAINTEXT, "Method GET Allowed Only.");
        }

        /* 提取参数 payload 的原始查询字符串，作为 JSON5 解析 */
        Map<String, List<String>> params = session.getParameters();
        List<String> queryStringList = params.get("payload");
        if (queryStringList == null) return newErrorResponse("Missing 'payload' argument.");
        String queryString = queryStringList.getLast();
        SConfig input = new SConfig(queryString, "jsonc", null);
        jlogger.debug("处理 Placeholder 查询：", queryString);

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
        long timestamp = System.currentTimeMillis();
        jlogger.debug("返回 Placeholder 查询请求：", StreackLib.formatTime(timestamp, null), " → ", results);

        JSONObject body = new JSONObject();
        body.put("timestamp", timestamp);
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
   * 未通过白名单/黑名单过滤的查询返回 "[forbidden]"。
   *
   * @param key    占位符键（可能已含 % 或未含）
   * @param target 目标玩家名，null 表示服务端上下文
   */
  private static String resolvePlaceholder(String key, String target) {
    if (key == null || key.isBlank()) return "";

    // 提取裸的 placeholder 名称（去除首尾 %）
    String bare = key;
    if (bare.startsWith("%")) bare = bare.substring(1);
    if (bare.endsWith("%")) bare = bare.substring(0, bare.length() - 1);

    // 名单过滤
    if (!isAllowed(bare)) return "[forbidden]";

    // 智能 % 包裹：两侧都有 % 则保持原样，否则包裹
    String wrapped;
    if (key.startsWith("%") && key.endsWith("%")) {
      wrapped = key;
    } else {
      wrapped = "%" + bare + "%";
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

  /**
   * 检查 placeholder 裸名称是否允许查询。
   *
   * @param bare 裸名称（不含 %）
   * @return true 允许
   */
  private static boolean isAllowed(String bare) {
    boolean whiteMode = Joyous.conf.getBoolean("APIHolders.white-mode", true);
    List<String> list = Joyous.conf.getListOfString("APIHolders.ph_list");

    // 空名单：白名单默认全部拒绝，黑名单默认全部允许
    if (list == null || list.isEmpty()) {
      return !whiteMode;
    }

    boolean matchAny = list.stream().anyMatch(rule -> {
      if (rule.startsWith("regex:")) {
        try {
          return Pattern.compile(rule.substring(6)).matcher(bare).find();
        } catch (PatternSyntaxException ignored) {
          jlogger.debug("忽略正则表达式错误：" + ignored.getLocalizedMessage(), ignored);
          return false;
        }
      }
      // 普通字符串：忽略大小写全匹配
      return rule.equalsIgnoreCase(bare);
    });

    return whiteMode ? matchAny : !matchAny;
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