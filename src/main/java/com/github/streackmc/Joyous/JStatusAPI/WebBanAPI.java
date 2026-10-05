package com.github.streackmc.Joyous.JStatusAPI;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import org.bukkit.Bukkit;
import org.bukkit.ban.IpBanList;
import org.bukkit.ban.ProfileBanList;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.nanohttpd.protocols.http.IHTTPSession;
import org.nanohttpd.protocols.http.NanoHTTPD;
import org.nanohttpd.protocols.http.request.Method;
import org.nanohttpd.protocols.http.response.Response;
import org.nanohttpd.protocols.http.response.Status;

import com.github.streackmc.Joyous.jlogger;
import com.github.streackmc.StreackLib.self.manager;
import com.github.streackmc.StreackLib.types.SConfig;

import io.papermc.paper.ban.BanListType;

public class WebBanAPI extends JStatusApiSubhandler {
  /** 未指定 target（或显式传入 *）时，代表查询全部封禁条目 */
  private static final String ALL_TARGETS = "*";

  @Override
  String getPath() {
    return "ban";
  }

  @Override
  String getSubhandlerName() {
    return "BanAPI";
  }

  @SuppressWarnings("unchecked")
  @Override
  Response handleRequest(IHTTPSession session) throws Exception {
    try {
      // 仅允许 GET
      if (!Method.GET.equals(session.getMethod())) {
        return Response.newFixedLengthResponse(Status.METHOD_NOT_ALLOWED,
            NanoHTTPD.MIME_PLAINTEXT, "Method GET Allowed Only.");
      }

      // 根据参数 target 来分情况获取
      Map<String, List<String>> params = session.getParameters();
      List<String> queryStringList = params.get("target");
      // 缓存键：全量查询与具体目标互不干扰，多个目标排序后合并以复用缓存
      String cacheKey = buildCacheKey(queryStringList);

      // 声明式缓存：同一查询在缓存有效期内直接复用，由基类维护 retrieved_at / expires_at
      JSONObject body = this.cached(cacheKey, () -> {
        JSONArray results = new JSONArray();
        if (cacheKey.equals(ALL_TARGETS)) {
          // 没有 target 参数，获取所有封禁信息
          results.addAll(entriesToJson(getBanEntry(null)));
        } else {
          // 有 target 参数，获取指定目标的封禁信息
          for (int i = 0; i < queryStringList.size(); i++) {
            String target = queryStringList.get(i);
            results.addAll(entriesToJson(getBanEntry(target)));
          }
        }

        JSONObject data = new JSONObject();
        data.put("result", results);
        data.put("response", "200 OK");
        return data;
      });

      // 封装
      Response rsp = Response.newFixedLengthResponse(
          Status.OK,
          "application/json",
          body.toJSONString());
      rsp.addHeader(CORS_HEADER, JStatusAPIMain.CONF.corsHeader());
      return rsp;
    } catch (Exception e) {
      // 出错
      jlogger.err("无法处理 BanAPI 查询：" + e.getLocalizedMessage(), e);
      long timestamp = System.currentTimeMillis();
      JSONObject statusData = new JSONObject();
      statusData.put("online", false);
      statusData.put("response", "500 Internal Server Error");
      statusData.put("retrieved_at", timestamp);
      statusData.put("expires_at", timestamp);
      Response rsp = Response.newFixedLengthResponse(
          Status.INTERNAL_ERROR,
          "application/json",
          statusData.toJSONString());
      rsp.addHeader(CORS_HEADER, JStatusAPIMain.CONF.corsHeader());
      return rsp;
    }
  }

  /**
   * 计算缓存键。
   * <p>
   * 无 target 参数、参数为空或包含 {@value #ALL_TARGETS} 时统一视为「查全部」；
   * 否则将目标列表排序后合并，使 target=a&target=b 与 target=b&target=a 共享缓存。
   *
   * @param queryStringList 原始 target 参数列表，可为 null
   * @return 缓存键
   */
  private static String buildCacheKey(@Nullable List<String> queryStringList) {
    if (queryStringList == null || queryStringList.isEmpty() || queryStringList.contains(ALL_TARGETS)) {
      return ALL_TARGETS;
    }
    List<String> sorted = new ArrayList<>(queryStringList);
    Collections.sort(sorted);
    return String.join(",", sorted);
  }

  /**
   * 将封禁条目列表转换为 JSON 数组。
   *
   * @param entries 封禁条目
   * @return 转换后的 JSON 数组
   */
  @SuppressWarnings("unchecked")
  private static JSONArray entriesToJson(List<SConfig> entries) {
    JSONArray array = new JSONArray();
    for (SConfig entry : entries) {
      array.add(entryToJson(entry));
    }
    return array;
  }

  /**
   * 将单个封禁条目（SConfig）转换为 JSON 对象。
   * <p>
   * 字段与 {@link com.github.streackmc.StreackLib.self.backend.StreackLibDefaultBackend#checkBan(String)}
   * 保持一致：target / type / banned / reason / op / create / expire。
   * 其中 type 仅在条目自带时输出（枚举全部封禁时为 ip / player）。
   *
   * @param entry 封禁条目
   * @return 转换后的 JSON 对象
   */
  @SuppressWarnings("unchecked")
  private static JSONObject entryToJson(SConfig entry) {
    JSONObject json = new JSONObject();
    json.put("target", entry.getString("target", null));
    if (entry.isExist("type")) {
      json.put("type", entry.getString("type", null));
    }
    json.put("banned", entry.getBoolean("banned", false));
    json.put("reason", entry.getString("reason", ""));
    json.put("op", entry.getString("op", ""));
    json.put("create", entry.getLong("create", 0L));
    json.put("expire", entry.getLong("expire", -1L));
    return json;
  }

  /**
   * @see {@link com.github.streackmc.StreackLib.self.backend.StreackLibDefaultBackend#checkBan(String)}
   *      返回值中每个元素的格式
   */
  public static List<SConfig> getBanEntry(@Nullable String target) {
    List<SConfig> result = new ArrayList<>();
    if (target == null || target.isBlank()) {
      // 枚举 IP
      IpBanList ips = Bukkit.getServer().getBanList(BanListType.IP);
      ips.getEntries().forEach((banEntry) -> {
        result.add(fillEntry(newBanEntry(), banEntry, "ip"));
      });

      // 枚举玩家
      ProfileBanList profiles = Bukkit.getServer().getBanList(BanListType.PROFILE);
      profiles.getEntries().forEach((banEntry) -> {
        result.add(fillEntry(newBanEntry(), banEntry, "player"));
      });
      return result;
    } else {
      // 复用 StreackLib
      result.add(manager.backend.checkBan(target));
      return result;
    }
  }

  /**
   * 构造一个空的封禁条目载体。
   * <p>
   * 注意：此处不可改用 {@code manager.backend.checkBan(null)} 取模板。StreackLib 的
   * {@code getDefaultBanEntry(null)} 会以 null 作为 target 调用 {@code putString}，
   * 而 SConfig 内部是 ConcurrentHashMap，写入 null 值会直接抛 NPE。因此按 StreackLib
   * 相同的格式（JSON、MEMORY 模式）自行构造空载体。
   *
   * @return 空的 SConfig 载体
   */
  private static SConfig newBanEntry() {
    return new SConfig("", SConfig.TYPES.JSON, "");
  }

  /**
   * 将 Bukkit 的封禁条目信息填入 SConfig。
   *
   * @param data    待填充的 SConfig（见 {@link #newBanEntry()}）
   * @param banEntry Bukkit 封禁条目
   * @param type    封禁类型，ip / player
   * @return 填充后的 SConfig
   */
  @SuppressWarnings("deprecation"/* 分类型枚举，导致类型已知，无需使用新 API */)
  private static SConfig fillEntry(SConfig data, org.bukkit.BanEntry<?> banEntry, String type) {
    data.putString("target", banEntry.getTarget());
    data.putString("type", type);
    data.putBoolean("banned", true);
    data.putString("reason", banEntry.getReason() == null ? "" : banEntry.getReason());
    data.putString("op", banEntry.getSource() == null ? "" : banEntry.getSource());

    Date created = banEntry.getCreated();
    data.putLong("create", created == null ? 0L : created.getTime());

    Date expires = banEntry.getExpiration();
    // 永久封禁：用 -1 表示
    data.putLong("expire", expires == null ? -1L : expires.getTime());
    return data;
  }
}
