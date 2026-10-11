package com.github.streackmc.Joyous.Neonaic;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.nanohttpd.protocols.http.IHTTPSession;
import org.nanohttpd.protocols.http.request.Method;
import org.nanohttpd.protocols.http.response.Response;
import org.nanohttpd.protocols.http.response.Status;

import com.github.streackmc.Joyous.Joyous;
import com.github.streackmc.Joyous.JStatusAPI.WebStatusAPI;
import com.github.streackmc.Joyous.Neonaic.bridge.BridgeProtocol;
import com.github.streackmc.Joyous.Neonaic.bridge.NeonaicBridge;
import com.github.streackmc.Joyous.Neonaic.chat.AiReplyController;
import com.github.streackmc.Joyous.Neonaic.chat.ChatListener;
import com.github.streackmc.Joyous.Neonaic.chat.GroupChatSync;
import com.github.streackmc.Joyous._Model.JoyousModel;
import com.github.streackmc.Joyous.jlogger;
import com.github.streackmc.StreackLib.StreackLib;
import com.github.streackmc.StreackLib.types.HTTPServer;
import com.github.streackmc.StreackLib.types.SConfig;

/**
 * Neonaic 模块（组合根）。
 * <p>
 * 把「配置 / 传输桥接 / 聊天业务」装配起来：
 * 建立 UDS 或 HTTP 通道、注册本端对外方法（{@code status.query} / {@code chat.broadcast}）、
 * 注册聊天监听与占位符、注册管理命令。
 *
 * @since 0.3.0
 */
public class NeonaicSubsystem extends JoyousModel {

  public String MODEL_NAME() {
    return "Neonaic";
  }

  private NeonaicConfig config;
  private NeonaicBridge bridge;
  private AiReplyController aiController;
  private GroupChatSync groupSync;
  private ChatListener chatListener;
  private NeonaicPHAPI phapi;

  private HTTPServer httpInboundServer;
  private String httpInboundPath;

  private volatile String peerName = "";
  private volatile String peerSubname = "";

  @Override
  public void onEnable() throws Exception {
    // 1. 释放并载入配置
    Joyous.requireFileAndCheck(NeonaicConfig.CONF_FILE, NeonaicConfig.CONF_FILE, null);
    Path confPath = Joyous.dataPath.toPath().resolve(NeonaicConfig.CONF_FILE);
    SConfig conf = new SConfig(confPath.toFile(), "yml");
    conf.setAutoReload(true);
    this.config = new NeonaicConfig(conf);

    // 2. 构建传输桥接
    NeonaicBridge.Config bridgeConfig = new NeonaicBridge.Config(
        config.method(),
        Path.of(config.udsPath()),
        "Joyous",
        Joyous.getVersion(),
        config.handshakeTimeout(),
        config.requestTimeout(),
        config.heartbeat(),
        config.commandTimeout(),
        config.httpInvokeUrl(),
        config.httpCommandUrl(),
        config.httpToken());
    this.bridge = new NeonaicBridge(bridgeConfig);

    // 3. 注册本端对外提供的方法
    bridge.registerMethod(BridgeProtocol.Methods.STATUS_QUERY, this::onStatusQuery);
    bridge.registerMethod(BridgeProtocol.Methods.CHAT_BROADCAST, this::onChatBroadcast);
    bridge.setPeerListener(peer -> {
      peerName = str(peer.get("name"));
      peerSubname = str(peer.get("subname"));
      jlogger.info("[Neonaic] 对端已就绪：%s / %s", peerName, peerSubname);
    }, () -> {
      peerName = "";
      peerSubname = "";
    });

    // 4. 业务对象
    this.aiController = new AiReplyController(config);
    this.groupSync = new GroupChatSync(config, bridge, this::resolvedName, this::resolvedSubname);
    this.chatListener = new ChatListener(config, bridge, aiController, groupSync,
        this::resolvedName, this::resolvedSubname);

    // 5. 启动桥接
    bridge.start();

    // 6. 监听聊天
    Bukkit.getPluginManager().registerEvents(chatListener, Joyous.plugin);

    // 7. 占位符
    this.phapi = new NeonaicPHAPI(this::resolvedName, this::resolvedSubname);
    if (Joyous.PlaceholderService != null && Joyous.PlaceholderService.available) {
      Joyous.PlaceholderService.registerParser(phapi);
    }

    // 8. 命令
    new NeonaicCommand(this).register();

    // 9. HTTP 入站端点（非 uds 模式时）
    registerHttpInbound();

    jlogger.info("[Neonaic] 模块已启用：传输 %s，Bot 名 %s", config.method(), resolvedName());
  }

  @Override
  public void onDisable() throws Exception {
    if (phapi != null && Joyous.PlaceholderService != null) {
      Joyous.PlaceholderService.unregisiterParser(phapi);
    }
    if (httpInboundServer != null && httpInboundPath != null) {
      try {
        httpInboundServer.removeHandler(httpInboundPath);
      } catch (Exception e) {
        jlogger.warn("[Neonaic] 移除 HTTP 入站端点失败：%s", e.getLocalizedMessage());
      }
      httpInboundServer = null;
      httpInboundPath = null;
    }
    if (bridge != null) {
      bridge.stop();
    }
    jlogger.info("[Neonaic] 模块已禁用");
  }

  // ---- 供命令层使用 ----

  public String botName() {
    return resolvedName();
  }

  public String botSubname() {
    return resolvedSubname();
  }

  public boolean byCmdEnabled() {
    return config != null && config.aiByCmd();
  }

  public boolean bindIdentityEnabled() {
    return config != null && config.bindIdentityEnabled();
  }

  public List<String> statusLines() {
    List<String> lines = new ArrayList<>();
    lines.add("&6Neonaic 桥接状态：");
    if (config == null || bridge == null) {
      lines.add("&7· 模块未启用");
      return lines;
    }
    lines.add("&7· 传输模式：&f" + config.method());
    lines.add("&7· UDS 路径：&f" + bridge.udsPath());
    lines.add("&7· UDS 就绪：&f" + (bridge.udsReady() ? "&a是" : "&c否"));
    Map<String, Object> peer = bridge.peer();
    if (peer != null) {
      lines.add("&7· 对端：&f" + str(peer.get("name")) + " &7v&f" + str(peer.get("version"))
          + " &7能力：&f" + String.valueOf(peer.get("capabilities")));
    } else {
      lines.add("&7· 对端：&c未连接");
    }
    lines.add("&7· Bot 名：&f" + resolvedName() + " &7副名：&f" + resolvedSubname());
    if (hasText(config.httpInvokeUrl()) || hasText(config.httpCommandUrl())) {
      lines.add("&7· HTTP 出站：&f" + config.httpInvokeUrl() + " &7/&f " + config.httpCommandUrl());
    }
    return lines;
  }

  public CompletableFuture<Map<String, Object>> bind(Player player, String profile, String chatId, String code) {
    if (groupSync == null) {
      CompletableFuture<Map<String, Object>> f = new CompletableFuture<>();
      f.completeExceptionally(new IllegalStateException("Neonaic 模块未启用"));
      return f;
    }
    return groupSync.bind(profile, player, chatId, code);
  }

  public void requestPrivateReply(Player player, String prompt, String trigger) {
    if (chatListener != null) {
      chatListener.requestReply(player, prompt, trigger, false);
    }
  }

  // ---- 本端对外方法实现 ----

  private Map<String, Object> onStatusQuery(Map<String, Object> payload) {
    JSONObject data = WebStatusAPI.buildServerStatusData();
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("ok", true);
    result.put("data", data);
    return result;
  }

  private Map<String, Object> onChatBroadcast(Map<String, Object> payload) {
    GroupChatSync gs = this.groupSync;
    if (gs == null) {
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("rendered", false);
      return result;
    }
    return gs.onBroadcast(payload);
  }

  // ---- HTTP 入站 ----

  private void registerHttpInbound() {
    if ("uds".equalsIgnoreCase(config.method())) return;
    String path = config.httpServerPath();
    if (path == null || path.isBlank()) return;

    HTTPServer server = StreackLib.getHttpServer();
    if (server == null) {
      jlogger.warn("[Neonaic] StreackLib 的 HTTP 服务端不可用，无法注册入站端点 %s", path);
      return;
    }
    try {
      server.registerHandler(path, this::handleHttpInvoke);
      this.httpInboundServer = server;
      this.httpInboundPath = path;
      jlogger.info("[Neonaic] 已注册 HTTP 入站端点 %s", path);
    } catch (Exception e) {
      jlogger.error("[Neonaic] 注册 HTTP 入站端点 %s 失败：%s", path, e.getLocalizedMessage(), e);
    }
  }

  private Response handleHttpInvoke(IHTTPSession session) throws Exception {
    try {
      if (!Method.POST.equals(session.getMethod())) {
        return json(Status.METHOD_NOT_ALLOWED, errorEnvelope(null, "invalid_argument", "仅支持 POST"));
      }
      Map<String, String> bodyMap = new HashMap<>();
      session.parseBody(bodyMap);
      String body = bodyMap.get("postData");
      if (body == null || body.isBlank()) {
        return json(Status.BAD_REQUEST, errorEnvelope(null, "bad_frame", "请求体为空"));
      }
      Object parsed = new JSONParser().parse(body);
      Map<String, Object> env = asMap(parsed);
      if (env == null) {
        return json(Status.BAD_REQUEST, errorEnvelope(null, "bad_frame", "请求体必须是 JSON 对象"));
      }
      Object id = env.get("id");
      String method = str(env.get("method"));
      Map<String, Object> payload = asMap(env.get("payload"));

      Map<String, Object> result;
      if (BridgeProtocol.Methods.STATUS_QUERY.equals(method)) {
        result = onStatusQuery(payload);
      } else if (BridgeProtocol.Methods.CHAT_BROADCAST.equals(method)) {
        result = onChatBroadcast(payload);
      } else {
        return json(Status.NOT_IMPLEMENTED, errorEnvelope(id, "unknown_method", "未实现的方法：" + method));
      }

      Map<String, Object> resp = new LinkedHashMap<>();
      resp.put("ok", true);
      resp.put("id", id);
      resp.put("payload", result);
      return json(Status.OK, resp);
    } catch (Exception e) {
      jlogger.error("[Neonaic] HTTP 入站处理失败：%s", e.getLocalizedMessage(), e);
      return json(Status.INTERNAL_ERROR, errorEnvelope(null, "internal_error", e.getLocalizedMessage()));
    }
  }

  @SuppressWarnings("unchecked")
  private static Response json(Status status, Map<String, Object> body) {
    JSONObject obj = new JSONObject();
    obj.putAll(body);
    Response rsp = Response.newFixedLengthResponse(status, "application/json", obj.toJSONString());
    rsp.addHeader("Access-Control-Allow-Origin", "*");
    return rsp;
  }

  private static Map<String, Object> errorEnvelope(Object id, String code, String message) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("ok", false);
    m.put("id", id);
    m.put("code", code);
    m.put("message", message);
    return m;
  }

  // ---- 内部辅助 ----

  private String resolvedName() {
    String configured = config != null ? config.name() : "";
    if (hasText(configured)) return configured;
    if (hasText(peerName)) return peerName;
    return "Neonaic";
  }

  private String resolvedSubname() {
    String configured = config != null ? config.subname() : "";
    if (hasText(configured)) return configured;
    return peerSubname != null ? peerSubname : "";
  }

  private static boolean hasText(String s) {
    return s != null && !s.isBlank();
  }

  private static String str(Object o) {
    return o == null ? "" : String.valueOf(o);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> asMap(Object o) {
    return (o instanceof Map<?, ?> m) ? (Map<String, Object>) m : null;
  }
}
