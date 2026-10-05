package com.github.streackmc.Joyous.JStatusAPI;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;

import org.bukkit.Bukkit;
import org.json.simple.JSONObject;
import org.nanohttpd.protocols.http.IHTTPSession;
import org.nanohttpd.protocols.http.NanoHTTPD;
import org.nanohttpd.protocols.http.request.Method;
import org.nanohttpd.protocols.http.response.Response;
import org.nanohttpd.protocols.http.response.Status;

import com.github.streackmc.Joyous.jlogger;
import com.github.streackmc.StreackLib.StreackLib;
import com.github.streackmc.StreackLib.utils.MCColor;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

public class WebStatusAPI extends JStatusApiSubhandler {
  /** 该端点无参数，使用固定缓存键 */
  private static final String CACHE_KEY = "status";

  @Override
  String getPath() {
    return "status";
  }

  @Override
  String getSubhandlerName() {
    return "StatusAPI";
  }

  @SuppressWarnings("unchecked")
  @Override
  Response handleRequest(IHTTPSession session) throws Exception {
    try {
      // 仅处理 GET
      if (!Method.GET.equals(session.getMethod())) {
        return Response.newFixedLengthResponse(Status.METHOD_NOT_ALLOWED,
            NanoHTTPD.MIME_PLAINTEXT, "Method GET Allowed Only.");
      }

      // 构建状态数据（缓存由基类统一声明维护）
      JSONObject statusData = this.cached(CACHE_KEY, WebStatusAPI::buildServerStatusData);
      // 返回JSON响应
      Response rsp = Response.newFixedLengthResponse(
          Status.OK,
          "application/json",
          statusData.toJSONString());
      rsp.addHeader(/* CORS策略 */CORS_HEADER, JStatusAPIMain.CONF.corsHeader());
      return rsp;
    } catch (Exception e) {
      // 出错
      jlogger.err("无法处理 StatusAPI 查询：" + e.getLocalizedMessage(), e);
      JSONObject statusData = new JSONObject();
      statusData.put("online", false);
      statusData.put("response", "500 Internal Server Error");
      statusData.put("retrieved_at", System.currentTimeMillis());
      statusData.put("expires_at", this.cachedAt(CACHE_KEY));
      Response rsp = Response.newFixedLengthResponse(
          Status.INTERNAL_ERROR,
          "application/json",
          statusData.toJSONString());
      rsp.addHeader(/* CORS策略 */CORS_HEADER, JStatusAPIMain.CONF.corsHeader());
      return rsp;
    }
  }

  /**
   * 构建服务器完整状态数据
   * 包含：online, response, version, players, motd, tps, memory, worlds
   * <p>
   * 注意：retrieved_at / expires_at 由基类的缓存设施统一填写，此处不写入。
   * 
   * @return 符合result.json结构的JSONObject（精简版）
   * @since 0.0.2
   */
  @SuppressWarnings("unchecked")
  private static JSONObject buildServerStatusData() {
    // 准备生成
    JSONObject rspData = new JSONObject();
    org.bukkit.Server server = Bukkit.getServer();

    // 版本信息
    JSONObject version = new JSONObject();
    String rawVersion = server.getVersion();
    version.put("mc", "§f" + rawVersion);
    version.put("text", rawVersion);
    version.put("html", MCColor.toHtml("§f" + rawVersion));
    rspData.put("version", version);

    // 玩家信息
    JSONObject players = new JSONObject();
    java.util.Collection<? extends org.bukkit.entity.Player> onlinePlayers = server.getOnlinePlayers();
    players.put("online", onlinePlayers.size());
    players.put("max", server.getMaxPlayers());

    org.json.simple.JSONArray sampleList = new org.json.simple.JSONArray();
    onlinePlayers.stream().limit(5).forEach(player -> {
      JSONObject p = new JSONObject();
      p.put("uuid", player.getUniqueId().toString());
      p.put("mc", LegacyComponentSerializer.legacySection().serialize(player.displayName()));
      JSONObject pn = new JSONObject();
      pn.put("text", MCColor.strip(LegacyComponentSerializer.legacySection().serialize(player.displayName())));
      pn.put("html", MCColor.toHtml(LegacyComponentSerializer.legacySection().serialize(player.displayName())));
      p.put("name", pn);
      sampleList.add(p);
    });
    players.put("list", sampleList);
    rspData.put("players", players);

    // MOTD信息
    JSONObject motd = new JSONObject();
    String rawMotd = LegacyComponentSerializer.legacySection().serialize(server.motd());
    motd.put("mc", rawMotd);
    motd.put("text", MCColor.strip(rawMotd));
    motd.put("html", MCColor.toHtml(rawMotd));
    rspData.put("motd", motd);

    // TPS信息
    JSONObject tps = getTPSDataAsJSON();
    rspData.put("tps", tps);

    // JVM内存信息
    JSONObject memory = new JSONObject();
    MemoryUsage mmxbH = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
    MemoryUsage mmxbNH = ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage();
    memory.put("used", mmxbH.getUsed() + mmxbNH.getUsed() / 1024 / 1024);
    memory.put("max", mmxbH.getMax() + mmxbNH.getMax() / 1024 / 1024);
    rspData.put("memory", memory);

    // 世界（维度）信息
    JSONObject worlds = new JSONObject();
    server.getWorlds().forEach(world -> {
      JSONObject worldInfo = new JSONObject();
      worldInfo.put("environment", world.getEnvironment().name()); // 如 NORMAL, NETHER, THE_END [citation:2]
      worldInfo.put("inday_time", world.getTime());
      worldInfo.put("full_time", world.getFullTime());
      worldInfo.put("has_storm", world.hasStorm());
      worldInfo.put("is_thundering", world.isThundering());
      worlds.put(world.getName(), worldInfo);
    });
    rspData.put("worlds", worlds);

    // 响应基础状态
    rspData.put("online", true);
    rspData.put("response", "200 OK");

    jlogger.debug("status数据构建完成：" + rspData.toString());
    return rspData;
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
      jlogger.error("无法获取TPS：" + e.getLocalizedMessage(), e);
      tps.put("live", -1.0);
      tps.put("avg_1m", -1.0);
      tps.put("avg_5m", -1.0);
      tps.put("avg_15m", -1.0);
    }
    return tps;
  }
}
