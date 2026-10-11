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
import com.github.streackmc.Joyous.Neonaic.bridge.BridgeException;
import com.github.streackmc.Joyous.Neonaic.bridge.BridgeProtocol;
import com.github.streackmc.Joyous.Neonaic.bridge.NeonaicBridge;
import com.github.streackmc.Joyous.Neonaic.chat.AiReplyController;
import com.github.streackmc.Joyous.Neonaic.chat.ChatListener;
import com.github.streackmc.Joyous.Neonaic.chat.GroupChatSync;
import com.github.streackmc.Joyous.Neonaic.chat.IdentityBindingService;
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
  private IdentityBindingService bindingService;
  private ChatListener chatListener;
  private NeonaicPHAPI phapi;

  private HTTPServer httpInboundServer;
  private String httpInboundPath;

  private volatile String peerName = "";
  private volatile String peerSubname = "";

  /** 连接是否处于「已建立」状态（与模块是否启用解耦） */
  private volatile boolean connectionRunning = false;

  /** 当前实例（模块启用期间非 null），供公开 API / 命令访问 */
  private static volatile NeonaicSubsystem INSTANCE;

  /** 获取当前实例；模块未启用时为 null */
  public static NeonaicSubsystem get() {
    return INSTANCE;
  }

  @Override
  public void onEnable() throws Exception {
    // 1. 释放并载入配置
    Joyous.requireFileAndCheck(NeonaicConfig.CONF_FILE, NeonaicConfig.CONF_FILE, null);
    Path confPath = Joyous.dataPath.toPath().resolve(NeonaicConfig.CONF_FILE);
    SConfig conf = new SConfig(confPath.toFile(), "yml");
    conf.setAutoReload(true);
    this.config = new NeonaicConfig(conf);

    // 2. 构建传输桥接（此时**不**建立连接）
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
    bridge.registerMethod(BridgeProtocol.Methods.IDENTITY_CLAIM, this::onIdentityClaim);
    bridge.setPeerListener(peer -> {
      peerName = str(peer.get("name"));
      peerSubname = str(peer.get("subname"));
      jlogger.info("[Neonaic] 对端已就绪：%s / %s", peerName, peerSubname);
    }, () -> {
      jlogger.info("[Neonaic] 对端连接已断开，本端继续保持监听，等待对端重连");
      peerName = "";
      peerSubname = "";
    });

    // 4. 业务对象（监听器/占位符/命令随模块启用即就绪，与连接状态无关）
    this.aiController = new AiReplyController(config);
    this.groupSync = new GroupChatSync(config, bridge, this::resolvedName, this::resolvedSubname);
    this.bindingService = new IdentityBindingService(config, bridge);
    this.chatListener = new ChatListener(config, bridge, aiController, groupSync,
        this::resolvedName, this::resolvedSubname);

    // 5. 监听聊天
    Bukkit.getPluginManager().registerEvents(chatListener, Joyous.plugin);

    // 6. 占位符
    this.phapi = new NeonaicPHAPI(this::resolvedName, this::resolvedSubname);
    if (Joyous.PlaceholderService != null && Joyous.PlaceholderService.available) {
      Joyous.PlaceholderService.registerParser(phapi);
    }

    // 7. 命令
    new NeonaicCommand(this).register();

    INSTANCE = this;

    // 8. 连接是否自动建立，由配置决定；缺省 false（交由 API / 命令掌控）
    if (config.autoConnect()) {
      if (!startConnection()) {
        jlogger.warn("[Neonaic] auto-connect 已开启，但连接建立失败，请检查配置或稍后手动 /neonaic connect");
      }
    } else {
      jlogger.info("[Neonaic] 模块已启用（未自动连接）。请用 /neonaic connect 或公开 API 建立连接");
    }

    jlogger.info("[Neonaic] 模块已启用：传输 %s，Bot 名 %s", config.method(), resolvedName());
  }

  @Override
  public void onDisable() throws Exception {
    INSTANCE = null;
    stopConnection(); // 仅做资源清理（关闭套接字/端点），不代表「生命周期负责连接」
    if (phapi != null && Joyous.PlaceholderService != null) {
      Joyous.PlaceholderService.unregisiterParser(phapi);
    }
    jlogger.info("[Neonaic] 模块已禁用");
  }

  // ====================================================================
  // 公开 API：连接的开始与结束（不由插件生命周期管理）
  // ====================================================================

  /**
   * 建立连接。
   * <p>
   * UDS 模式：创建并开始监听套接字（本端此时"已连接"，等待 Neonaic 拨入并握手）；
   * HTTP 模式：准备出站客户端（无监听、无长连接）。幂等。
   *
   * @return 是否已处于连接态（已连接时返回 true；失败返回 false 并已记录日志）
   */
  public boolean startConnection() {
    synchronized (this) {
      if (bridge == null) {
        jlogger.warn("[Neonaic] 模块尚未启用，无法建立连接");
        return false;
      }
      if (connectionRunning) return true;
      try {
        bridge.start();
        connectionRunning = true;
        registerHttpInbound();
        jlogger.info("[Neonaic] 连接已建立（模式 %s）", config.method());
        return true;
      } catch (Exception e) {
        jlogger.error("[Neonaic] 建立连接失败：%s", e.getLocalizedMessage(), e);
        connectionRunning = false;
        return false;
      }
    }
  }

  /** 断开连接（关闭监听套接字、结束会话、释放调度器）。幂等。 */
  public synchronized void stopConnection() {
    if (!connectionRunning && bridge == null) return;
    if (bridge != null) {
      bridge.stop();
    }
    connectionRunning = false;
    unregisterHttpInbound();
    jlogger.info("[Neonaic] 连接已断开");
  }

  /**
   * 重连：先断开再建立（等价于 {@code stopConnection()} + {@code startConnection()}）。
   *
   * @return 是否最终处于连接态
   */
  public boolean restartConnection() {
    stopConnection();
    return startConnection();
  }

  /** 连接是否处于「已建立」状态（UDS 为已监听；HTTP 为客户端已就绪） */
  public boolean isConnectionRunning() {
    return connectionRunning;
  }

  /** UDS 链路是否已完成握手、可收发业务帧（HTTP 模式恒为 false） */
  public boolean isPeerReady() {
    return bridge != null && bridge.udsReady();
  }

  /** 当前传输模式（http / uds / auto） */
  public String connectionMode() {
    return config != null ? config.method() : "unknown";
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

  /** 模块配置（只读访问，供命令层读取命令名/别名等） */
  public NeonaicConfig config() {
    return config;
  }

  /** 身份绑定服务；模块未启用时为 null */
  public IdentityBindingService binding() {
    return bindingService;
  }

  public List<String> statusLines() {
    List<String> lines = new ArrayList<>();
    lines.add("&6Neonaic 桥接状态：");
    if (config == null || bridge == null) {
      lines.add("&7· 模块未启用");
      return lines;
    }
    lines.add("&7· 传输模式：&f" + config.method() + " &7(自动连接：&f" + config.autoConnect() + "&7)");
    lines.add("&7· 连接状态：&f" + (connectionRunning ? "&a已建立" : "&c未建立")
        + " &7(会话就绪：&f" + (isPeerReady() ? "&a是" : "&c否") + "&7)");
    lines.add("&7· UDS 路径：&f" + bridge.udsPath());
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

  /** 管理员直接设置绑定（旧流程，{@code /neonaic bind <profile> <chatId> [code]}） */
  public CompletableFuture<Map<String, Object>> adminBind(Player player, String profile, String chatId, String code) {
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

  /** {@code identity.claim}（Neonaic → 本端）：用绑定代码换取 MC 身份 */
  private Map<String, Object> onIdentityClaim(Map<String, Object> payload) {
    IdentityBindingService service = this.bindingService;
    if (service == null) {
      throw new BridgeException(BridgeProtocol.Errors.INTERNAL_ERROR, "身份绑定服务未就绪");
    }
    return service.claim(payload);
  }

  // ---- HTTP 入站 ----

  /** 注册 HTTP 入站端点（幂等；仅非 uds 模式）。UDS 模式下入站经会话到达，无需 HTTP 端点。 */
  private void registerHttpInbound() {
    if ("uds".equalsIgnoreCase(config.method())) return;
    if (httpInboundPath != null) return; // 已注册
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

  /** 注销 HTTP 入站端点（幂等） */
  private void unregisterHttpInbound() {
    HTTPServer server = this.httpInboundServer;
    String path = this.httpInboundPath;
    this.httpInboundServer = null;
    this.httpInboundPath = null;
    if (server == null || path == null) return;
    try {
      server.removeHandler(path);
      jlogger.info("[Neonaic] 已注销 HTTP 入站端点 %s", path);
    } catch (Exception e) {
      jlogger.warn("[Neonaic] 注销 HTTP 入站端点 %s 失败：%s", path, e.getLocalizedMessage());
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
