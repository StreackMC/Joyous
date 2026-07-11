package com.github.streackmc.Joyous.APIHolders;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;

import org.bukkit.Bukkit;
import org.json.simple.JSONObject;
import org.nanohttpd.protocols.http.NanoHTTPD;
import org.nanohttpd.protocols.http.request.Method;
import org.nanohttpd.protocols.http.response.Response;
import org.nanohttpd.protocols.http.response.Status;

import com.github.streackmc.Joyous.logger;
import com.github.streackmc.StreackLib.StreackLib;
import com.github.streackmc.StreackLib.utils.MCColor;

public class WebStatusAPI {
  static void enableStatus(String path) throws Exception {
    // 启用对StatusAPI的查询支持
    APIHoldersMain.httpServer.registerHandler(path, session -> {
      try {
        /* 仅处理 GET */
        if (!Method.GET.equals(session.getMethod())) {
          return Response.newFixedLengthResponse(Status.METHOD_NOT_ALLOWED,
              NanoHTTPD.MIME_PLAINTEXT, "Method GET Allowed Only.");
        }
        /* 构建状态数据（合并后的单一方法） */
        JSONObject statusData = buildServerStatusData();
        /* 返回JSON响应 */
        Response rsp = Response.newFixedLengthResponse(
            Status.OK,
            "application/json",
            statusData.toJSONString());
        rsp.addHeader("Access-Control-Allow-Origin", APIHoldersMain.CONF.corsHeader());
        return rsp;
      } catch (Exception e) {
        logger.err("无法处理StatusAPI查询：" + e.getLocalizedMessage(), e);
        return Response.newFixedLengthResponse(Status.INTERNAL_ERROR,
            NanoHTTPD.MIME_PLAINTEXT, "500 Internal Server Error: " + e.getLocalizedMessage());
      }
    });
    logger.info("已注册StatusAPI查询处理器： " + path);
  }

  /** 用于执行缓存策略 */
  private static class cache {
    static JSONObject lastBuilt = null;
    static long lastBuiltTime = -1L;
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
    long timestamp = System.currentTimeMillis();

    // 缓存命中检查
    if (cache.lastBuiltTime + APIHoldersMain.CONF.cache() > 0L
        && cache.lastBuilt != null
        && (cache.lastBuiltTime + APIHoldersMain.CONF.cache()) >= timestamp) {
      cache.lastBuilt.put("retrieved_at", timestamp);
      logger.debug("status请求命中缓存，因为当前时间戳%s已距离上次构建%s时间不足%s：" + cache.lastBuilt.toString(), timestamp, cache.lastBuiltTime, APIHoldersMain.CONF.cache());
      return cache.lastBuilt;
    }
    logger.debug("status请求未命中缓存，因为当前时间戳%s距离上次构建%s时间超过了%s：", timestamp, cache.lastBuiltTime, APIHoldersMain.CONF.cache());

    JSONObject data = new JSONObject();
    org.bukkit.Server server = Bukkit.getServer();

    /* 版本信息（移除protocol） */
    JSONObject version = new JSONObject();
    String rawVersion = server.getVersion();
    version.put("mc", "§f" + rawVersion);
    version.put("text", rawVersion);
    version.put("html", MCColor.toHtml("§f" + rawVersion));
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
      pn.put("text", MCColor.strip(player.getDisplayName()));
      pn.put("html", MCColor.toHtml(player.getDisplayName()));
      p.put("name", pn);
      sampleList.add(p);
    });
    players.put("list", sampleList);
    data.put("players", players);

    /* MOTD信息 */
    JSONObject motd = new JSONObject();
    String rawMotd = server.getMotd();
    motd.put("mc", rawMotd);
    motd.put("text", MCColor.strip(rawMotd));
    motd.put("html", MCColor.toHtml(rawMotd));
    data.put("motd", motd);
    
    /* TPS信息 */
    data.put("tps", getTPSDataAsJSON());

    /* JVM内存信息 */
    MemoryUsage mmxbH = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
    MemoryUsage mmxbNH = ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage();
    JSONObject mem = new JSONObject();
    mem.put("used", mmxbH.getUsed() + mmxbNH.getUsed() / 1024 / 1024);
    mem.put("max", mmxbH.getMax() + mmxbNH.getMax() / 1024 / 1024);
    data.put("memory", mem);

    /* 基础状态 */
    timestamp = System.currentTimeMillis();
    data.put("online", true);
    data.put("retrieved_at", timestamp);
    data.put("expires_at", timestamp + APIHoldersMain.CONF.cache());
    cache.lastBuilt = data;
    cache.lastBuiltTime = timestamp;

    logger.debug("status数据构建完成：" + data.toString());
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
      double[] getTps = (double[]) StreackLib.getServerTPS();
      tps.put("live", getTps[0]);
      tps.put("avg_1m", getTps[1]);
      tps.put("avg_5m", getTps[2]);
      tps.put("avg_15m", getTps[3]);
    } catch (Exception e) {
      logger.error("无法获取TPS：" + e.getLocalizedMessage(), e);
      tps.put("live", -1.0);
      tps.put("avg_1m", -1.0);
      tps.put("avg_5m", -1.0);
      tps.put("avg_15m", -1.0);
    }
    return tps;
  }
}
