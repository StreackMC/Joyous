package com.github.streackmc.Joyous.Neonaic.bridge;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import com.github.streackmc.Joyous.jlogger;

/**
 * 桥接门面（组合根）：把「选 UDS 还是 HTTP」「本端自述」「对外方法注册」
 * 与「出站调用路由」收拢到一处。
 * <p>
 * 与游戏侧的接口：
 * <ul>
 *   <li>启动前用 {@link #registerMethod} 注册本端对外提供的方法（{@code status.query} / {@code chat.broadcast}）；</li>
 *   <li>用 {@link #setPeerListener} 订阅对端握手就绪/断开，用于解析 Bot 展示名；</li>
 *   <li>用 {@link #chatPublish} / {@link #aiRequest} / {@link #identityBind} / {@link #identityResolve}
 *       / {@link #executeCommand} 发起出站调用。</li>
 * </ul>
 *
 * @since 0.3.0
 */
public class NeonaicBridge {

  /** 传输配置 */
  public record Config(
      String mode,
      Path udsPath,
      String selfName,
      String selfVersion,
      long handshakeTimeoutMs,
      long requestTimeoutMs,
      long heartbeatMs,
      long commandTimeoutMs,
      String httpInvokeUrl,
      String httpCommandUrl,
      String httpToken) {
  }

  private final Config config;
  private final ScheduledExecutorService scheduler;
  private final HttpBridgeClient httpClient;
  private final Map<String, BridgeSession.MethodHandler> methods = new LinkedHashMap<>();

  private volatile UdsServer udsServer;
  private volatile BridgeSession activeSession;
  private volatile Map<String, Object> peer;
  private volatile boolean running;

  private volatile Consumer<Map<String, Object>> onPeerReady;
  private volatile Runnable onPeerClosed;

  public NeonaicBridge(Config config) {
    this.config = config;
    AtomicInteger idx = new AtomicInteger();
    this.scheduler = Executors.newScheduledThreadPool(2, r -> {
      Thread t = new Thread(r, "Joyous-Neonaic-Bridge-" + idx.incrementAndGet());
      t.setDaemon(true);
      return t;
    });
    this.httpClient = new HttpBridgeClient(config.requestTimeoutMs());
  }

  // ---- 装配（启动前调用） ----

  /** 注册本端对 Neonaic 提供的方法（必须在 {@link #start()} 之前调用） */
  public void registerMethod(String name, BridgeSession.MethodHandler handler) {
    methods.put(name, handler);
  }

  /** 订阅对端握手就绪（参数为对端信息）与断开 */
  public void setPeerListener(Consumer<Map<String, Object>> peerReady, Runnable peerClosed) {
    this.onPeerReady = peerReady;
    this.onPeerClosed = peerClosed;
  }

  // ---- 生命周期 ----

  /**
   * 启动桥接。
   *
   * @throws IOException UDS 绑定失败（且模式不允许回退）
   */
  public void start() throws IOException {
    if (running) return;
    running = true;

    if (prefersUds()) {
      if (!UdsServer.isSupported()) {
        if (isUdsOnly()) {
          throw new IOException("当前平台不支持 AF_UNIX，无法以 UDS 模式启动（请改用 HTTP 模式）");
        }
        jlogger.warn("[NeonaicBridge] 当前平台不支持 AF_UNIX，自动回退到 HTTP 模式");
      } else {
        UdsServer server = new UdsServer(config.udsPath(), this::createSession);
        server.start();
        this.udsServer = server;
      }
    } else {
      jlogger.info("[NeonaicBridge] 以 HTTP 模式运行，不建立 UDS 监听");
    }
  }

  /** 停止桥接并释放资源 */
  public void stop() {
    running = false;
    UdsServer server = this.udsServer;
    this.udsServer = null;
    if (server != null) server.stop();

    BridgeSession session = this.activeSession;
    this.activeSession = null;
    if (session != null && session.state() != BridgeSession.State.CLOSED) {
      session.close("桥接已停止");
    }
    this.peer = null;
    scheduler.shutdown();
  }

  // ---- 状态 ----

  public boolean running() {
    return running;
  }

  public boolean udsReady() {
    BridgeSession s = activeSession;
    return s != null && s.ready();
  }

  public Map<String, Object> peer() {
    return peer;
  }

  public String mode() {
    return config.mode();
  }

  public Path udsPath() {
    return config.udsPath();
  }

  // ---- 出站调用 ----

  /** {@code chat.publish}：把 MC 聊天外发到 Neonaic */
  public CompletableFuture<Map<String, Object>> chatPublish(Map<String, Object> payload) {
    return outbound(BridgeProtocol.Methods.CHAT_PUBLISH, payload);
  }

  /** {@code ai.request}：请求 Neonaic 生成 AI 回复 */
  public CompletableFuture<Map<String, Object>> aiRequest(Map<String, Object> payload) {
    return outbound(BridgeProtocol.Methods.AI_REQUEST, payload);
  }

  /** {@code identity.bind}：绑定 MC↔聊天身份 */
  public CompletableFuture<Map<String, Object>> identityBind(Map<String, Object> payload) {
    return outbound(BridgeProtocol.Methods.IDENTITY_BIND, payload);
  }

  /** {@code identity.resolve}：查询身份绑定 */
  public CompletableFuture<Map<String, Object>> identityResolve(Map<String, Object> payload) {
    return outbound(BridgeProtocol.Methods.IDENTITY_RESOLVE, payload);
  }

  /** {@code command.execute}：请 Neonaic 执行一条命令 */
  public CompletableFuture<Map<String, Object>> executeCommand(Map<String, Object> payload) {
    if (prefersUds() && udsReady()) {
      return activeSession.request(BridgeProtocol.Methods.COMMAND_EXECUTE, payload, config.requestTimeoutMs());
    }
    if (hasText(config.httpCommandUrl())) {
      return CompletableFuture.supplyAsync(
          () -> httpClient.executeCommand(config.httpCommandUrl(), config.httpToken(), payload), scheduler);
    }
    return failedFuture(notReady("command.execute"));
  }

  // ---- 内部 ----

  private BridgeSession createSession(java.util.function.Consumer<String> write,
      java.util.function.Consumer<String> teardown) {
    BridgeSession[] holder = new BridgeSession[1];
    BridgeSession.Listener listener = new BridgeSession.Listener() {
      @Override
      public void onReady(Map<String, Object> p) {
        activeSession = holder[0];
        peer = p;
        Consumer<Map<String, Object>> cb = onPeerReady;
        if (cb != null) {
          try {
            cb.accept(p);
          } catch (Exception e) {
            jlogger.warn("[NeonaicBridge] 对端就绪回调异常：%s", e.getLocalizedMessage());
          }
        }
      }

      @Override
      public void onClosed(String reason) {
        if (activeSession == holder[0]) {
          activeSession = null;
          peer = null;
        }
        Runnable cb = onPeerClosed;
        if (cb != null) {
          try {
            cb.run();
          } catch (Exception e) {
            jlogger.warn("[NeonaicBridge] 对端断开回调异常：%s", e.getLocalizedMessage());
          }
        }
      }

      @Override
      public void onError(Throwable error) {
        jlogger.debug("[NeonaicBridge] 会话错误：%s", error == null ? "null" : error.getLocalizedMessage());
      }

      @Override
      public void onEvent(Map<String, Object> frame) {
        jlogger.debug("[NeonaicBridge] 收到事件：%s", frame.get("method"));
      }
    };

    BridgeSession.Self self = new BridgeSession.Self(
        config.selfName(),
        "",
        config.selfVersion(),
        List.of(BridgeProtocol.Capabilities.STATUS_QUERY, BridgeProtocol.Capabilities.CHAT_BROADCAST));

    BridgeSession session = new BridgeSession(
        BridgeProtocol.Roles.JOYOUS,
        BridgeProtocol.Roles.NEONAI,
        self,
        write,
        teardown,
        true, // Joyous 作为 UDS 服务端：accept 后先发 hello（PROTOCOL §13.2 / §14）
        config.handshakeTimeoutMs(),
        config.requestTimeoutMs(),
        config.heartbeatMs(),
        scheduler,
        listener);

    for (Map.Entry<String, BridgeSession.MethodHandler> e : methods.entrySet()) {
      session.registerMethod(e.getKey(), e.getValue());
    }
    holder[0] = session;
    return session;
  }

  private CompletableFuture<Map<String, Object>> outbound(String method, Map<String, Object> payload) {
    if (prefersUds() && udsReady()) {
      return activeSession.request(method, payload, config.requestTimeoutMs());
    }
    if (hasText(config.httpInvokeUrl())) {
      return CompletableFuture.supplyAsync(
          () -> httpClient.invoke(config.httpInvokeUrl(), config.httpToken(), method, payload), scheduler);
    }
    return failedFuture(notReady(method));
  }

  /** 是否优先走 UDS（`uds` 与 `auto` 为真；`http` 为假） */
  private boolean prefersUds() {
    String m = config.mode();
    return m == null || !"http".equalsIgnoreCase(m.trim());
  }

  /** 是否只允许 UDS（`uds` 模式不允许回退 HTTP） */
  private boolean isUdsOnly() {
    return "uds".equalsIgnoreCase(config.mode() == null ? "" : config.mode().trim());
  }

  private static boolean hasText(String s) {
    return s != null && !s.isBlank();
  }

  private static BridgeException notReady(String method) {
    return new BridgeException(BridgeProtocol.Errors.NOT_HANDSHAKEN,
        "桥接通道未就绪，无法调用 " + method);
  }

  private static <T> CompletableFuture<T> failedFuture(Throwable e) {
    CompletableFuture<T> f = new CompletableFuture<>();
    f.completeExceptionally(e);
    return f;
  }

  /** 供门面内部（如定时任务）复用调度器 */
  public ScheduledExecutorService scheduler() {
    return scheduler;
  }

  /** 阻塞等待一次出站调用的便捷方法（带超时） */
  public Map<String, Object> await(CompletableFuture<Map<String, Object>> future) {
    try {
      return future.get(config.commandTimeoutMs(), TimeUnit.MILLISECONDS);
    } catch (Exception e) {
      Throwable cause = e.getCause() != null ? e.getCause() : e;
      throw new BridgeException(BridgeProtocol.Errors.TIMEOUT, cause.getMessage(), cause);
    }
  }
}
