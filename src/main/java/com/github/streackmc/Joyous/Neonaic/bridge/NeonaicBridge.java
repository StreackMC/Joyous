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
 * <b>连接与生命周期解耦</b>：本类不负责「何时连接」——{@link #start()} / {@link #stop()}
 * 可被反复调用（模块启用、公开 API、命令都只是调用它们），对象本身在 stop 后仍可再次 start。
 * 调度器与 HTTP 客户端随连接创建/销毁。
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
  private final Map<String, BridgeSession.MethodHandler> methods = new LinkedHashMap<>();
  private final AtomicInteger threadSeq = new AtomicInteger();

  private volatile ScheduledExecutorService scheduler;
  private volatile HttpBridgeClient httpClient;
  private volatile UdsServer udsServer;
  private volatile BridgeSession activeSession;
  private volatile Map<String, Object> peer;
  private volatile boolean running;

  private volatile Consumer<Map<String, Object>> onPeerReady;
  private volatile Runnable onPeerClosed;

  public NeonaicBridge(Config config) {
    this.config = config;
  }

  // ---- 装配（启动前调用） ----

  /** 注册本端对 Neonaic 提供的方法（可在每次 {@link #start()} 之前随时调用，会作用于后续所有会话） */
  public void registerMethod(String name, BridgeSession.MethodHandler handler) {
    methods.put(name, handler);
  }

  /** 订阅对端握手就绪（参数为对端信息）与断开 */
  public void setPeerListener(Consumer<Map<String, Object>> peerReady, Runnable peerClosed) {
    this.onPeerReady = peerReady;
    this.onPeerClosed = peerClosed;
  }

  // ---- 连接生命周期（可反复调用） ----

  /**
   * 建立连接。
   * <ul>
   *   <li>UDS：创建并开始监听套接字（本端已连接，等待对端拨入）；</li>
   *   <li>HTTP：仅准备出站客户端，无监听。</li>
   * </ul>
   * 幂等：已处于连接态时直接返回。
   *
   * @throws IOException UDS 绑定失败（且模式不允许回退）
   */
  public synchronized void start() throws IOException {
    if (running) return;
    this.scheduler = Executors.newScheduledThreadPool(2, r -> {
      Thread t = new Thread(r, "Joyous-Neonaic-Bridge-" + threadSeq.incrementAndGet());
      t.setDaemon(true);
      return t;
    });
    this.httpClient = new HttpBridgeClient(config.requestTimeoutMs());
    this.running = true;

    try {
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
    } catch (IOException e) {
      // 半启动比不启动更糟：回滚干净再抛出
      running = false;
      ScheduledExecutorService s = this.scheduler;
      this.scheduler = null;
      this.httpClient = null;
      if (s != null) s.shutdownNow();
      throw e;
    }
  }

  /**
   * 断开连接并释放资源。幂等：未连接时直接返回。
   * <p>
   * UDS 会关闭监听套接字并删除本端创建的套接字文件；处于 READY 的会话被正常关闭，
   * 未完成的请求以错误结清（见 {@code BridgeSession.transportClosed}）。
   */
  public synchronized void stop() {
    if (!running) return;
    this.running = false;

    UdsServer server = this.udsServer;
    this.udsServer = null;
    if (server != null) server.stop();

    BridgeSession session = this.activeSession;
    this.activeSession = null;
    if (session != null && session.state() != BridgeSession.State.CLOSED) {
      session.close("桥接已停止");
    }
    this.peer = null;

    ScheduledExecutorService s = this.scheduler;
    this.scheduler = null;
    if (s != null) s.shutdown();
    this.httpClient = null;
  }

  /**
   * 断开后重新建立连接（等价于 {@code stop()} + {@code start()}）。
   *
   * @throws IOException UDS 绑定失败
   */
  public synchronized void restart() throws IOException {
    stop();
    start();
  }

  // ---- 状态 ----

  /** 是否处于「已建立连接」状态（UDS 为已监听；HTTP 为已就绪的客户端） */
  public boolean running() {
    return running;
  }

  /** UDS 链路是否已完成握手（HTTP 模式恒为 false） */
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

  /** 供门面内部（如定时任务）复用调度器；未连接时为 null */
  public ScheduledExecutorService scheduler() {
    return scheduler;
  }

  // ---- 出站调用 ----

  public CompletableFuture<Map<String, Object>> chatPublish(Map<String, Object> payload) {
    return outbound(BridgeProtocol.Methods.CHAT_PUBLISH, payload);
  }

  public CompletableFuture<Map<String, Object>> aiRequest(Map<String, Object> payload) {
    return outbound(BridgeProtocol.Methods.AI_REQUEST, payload);
  }

  public CompletableFuture<Map<String, Object>> identityBind(Map<String, Object> payload) {
    return outbound(BridgeProtocol.Methods.IDENTITY_BIND, payload);
  }

  public CompletableFuture<Map<String, Object>> identityResolve(Map<String, Object> payload) {
    return outbound(BridgeProtocol.Methods.IDENTITY_RESOLVE, payload);
  }

  /** {@code identity.unbind}：请 Neonaic 解除某个 MC 身份的全部绑定 */
  public CompletableFuture<Map<String, Object>> identityUnbind(Map<String, Object> payload) {
    return outbound(BridgeProtocol.Methods.IDENTITY_UNBIND, payload);
  }

  public CompletableFuture<Map<String, Object>> executeCommand(Map<String, Object> payload) {
    if (prefersUds() && udsReady()) {
      return activeSession.request(BridgeProtocol.Methods.COMMAND_EXECUTE, payload, config.requestTimeoutMs());
    }
    ScheduledExecutorService s = this.scheduler;
    HttpBridgeClient http = this.httpClient;
    if (s != null && http != null && hasText(config.httpCommandUrl())) {
      return CompletableFuture.supplyAsync(
          () -> http.executeCommand(config.httpCommandUrl(), config.httpToken(), payload), s);
    }
    return failedFuture(notReady("command.execute"));
  }

  // ---- 内部 ----

  private BridgeSession createSession(Consumer<String> write, Consumer<String> teardown) {
    ScheduledExecutorService s = this.scheduler;
    if (s == null) throw new IllegalStateException("桥接尚未启动，无法建立会话");

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
        List.of(BridgeProtocol.Capabilities.STATUS_QUERY,
            BridgeProtocol.Capabilities.CHAT_BROADCAST,
            BridgeProtocol.Capabilities.IDENTITY_CLAIM));

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
        s,
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
    ScheduledExecutorService s = this.scheduler;
    HttpBridgeClient http = this.httpClient;
    if (s != null && http != null && hasText(config.httpInvokeUrl())) {
      return CompletableFuture.supplyAsync(
          () -> http.invoke(config.httpInvokeUrl(), config.httpToken(), method, payload), s);
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
