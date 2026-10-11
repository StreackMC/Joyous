package com.github.streackmc.Joyous.Neonaic.bridge;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import com.github.streackmc.Joyous.jlogger;

/**
 * 传输无关的桥接会话（V1 §5 / §6 的 Java 实现）。
 * <p>
 * 本类只管协议语义：握手状态机、请求/响应按 id 关联、事件派发、心跳探活；
 * 它不认识底层是 UDS 还是 HTTP —— 与传输层只有两个回调：
 * <ul>
 *   <li>{@code write(text)} —— 把一整帧文本写出去；</li>
 *   <li>{@code teardown(why)} —— 要求关闭底层连接（重连由传输层负责）。</li>
 * </ul>
 *
 * @since 0.3.0
 */
public class BridgeSession {

  /** 会话状态 */
  public enum State {
    IDLE, HANDSHAKING, READY, CLOSED
  }

  /** 本端自述信息 */
  public record Self(String name, String subname, String version, List<String> capabilities) {
  }

  /** 对端可调用的方法处理器：输入 payload，返回 response 的 payload */
  public interface MethodHandler {
    Map<String, Object> handle(Map<String, Object> payload) throws Exception;
  }

  /** 会话事件监听器（全部可选） */
  public interface Listener {
    default void onReady(Map<String, Object> peer) {
    }

    default void onClosed(String reason) {
    }

    default void onError(Throwable error) {
    }

    default void onEvent(Map<String, Object> frame) {
    }
  }

  private static final Listener NULL_LISTENER = new Listener() {
  };

  private final String role;
  private final String peerRoleExpected;
  private final Self self;
  private final java.util.function.Consumer<String> write;
  private final java.util.function.Consumer<String> teardown;
  private final boolean sendHelloFirst;
  private final long handshakeTimeoutMs;
  private final long requestTimeoutMs;
  private final long heartbeatMs;
  private final ScheduledExecutorService scheduler;
  private final Listener listener;
  private final String sessionId;

  private final Map<String, MethodHandler> methods = new HashMap<>();
  private final Map<String, Pending> pending = new ConcurrentHashMap<>();
  private final FrameCodec codec = new FrameCodec();
  private final AtomicLong seq = new AtomicLong();

  private volatile State state = State.IDLE;
  private volatile Map<String, Object> peer;
  private volatile ScheduledFuture<?> handshakeTimer;
  private volatile ScheduledFuture<?> heartbeatTimer;

  private static final class Pending {
    final CompletableFuture<Map<String, Object>> future = new CompletableFuture<>();
    final String method;
    volatile ScheduledFuture<?> timer;

    Pending(String method) {
      this.method = method;
    }

    void cancelTimer() {
      ScheduledFuture<?> t = timer;
      if (t != null) t.cancel(false);
    }
  }

  /**
   * @param role              本端角色（{@link BridgeProtocol.Roles}）
   * @param peerRoleExpected  期望的对端角色
   * @param self              本端自述信息
   * @param write             写出整帧文本的回调
   * @param teardown          要求关闭底层连接的回调
   * @param sendHelloFirst    true 表示本端先发 {@code hello}（Joyous 作为 UDS 服务端时）
   * @param handshakeTimeoutMs 握手超时（毫秒，&lt;=0 表示不限）
   * @param requestTimeoutMs  单次请求等待应答超时（毫秒）
   * @param heartbeatMs       心跳间隔（毫秒，&lt;=0 关闭）
   * @param scheduler         定时器调度器
   * @param listener          事件监听器（可为 null）
   */
  public BridgeSession(String role, String peerRoleExpected, Self self,
      java.util.function.Consumer<String> write, java.util.function.Consumer<String> teardown,
      boolean sendHelloFirst, long handshakeTimeoutMs, long requestTimeoutMs, long heartbeatMs,
      ScheduledExecutorService scheduler, Listener listener) {
    if (write == null) throw new IllegalArgumentException("桥接会话必须提供 write 回调");
    if (self == null) throw new IllegalArgumentException("桥接会话必须提供 self 自述信息");
    this.role = role;
    this.peerRoleExpected = peerRoleExpected;
    this.self = self;
    this.write = write;
    this.teardown = teardown != null ? teardown : (why) -> {
    };
    this.sendHelloFirst = sendHelloFirst;
    this.handshakeTimeoutMs = handshakeTimeoutMs;
    this.requestTimeoutMs = requestTimeoutMs;
    this.heartbeatMs = heartbeatMs;
    this.scheduler = scheduler;
    this.listener = listener != null ? listener : NULL_LISTENER;
    this.sessionId = role + "-" + Long.toString(System.nanoTime() & 0xFFFFFFL, 36);
  }

  // ---- 只读视图 ----

  public State state() {
    return state;
  }

  public boolean ready() {
    return state == State.READY;
  }

  public Map<String, Object> peer() {
    return peer;
  }

  public String sessionId() {
    return sessionId;
  }

  public int inflight() {
    return pending.size();
  }

  // ---- 注册 ----

  /** 注册一个可被对端调用的方法（必须在 beginHandshake 之前调用） */
  public BridgeSession registerMethod(String method, MethodHandler handler) {
    if (state != State.IDLE) throw new IllegalStateException("会话已启动，不能再注册方法");
    if (method == null || handler == null) throw new IllegalArgumentException("方法名与处理器不能为空");
    methods.put(method, handler);
    return this;
  }

  // ---- 生命周期 ----

  /** 底层连接已建立，开始握手 */
  public void beginHandshake() {
    if (state != State.IDLE) return;
    state = State.HANDSHAKING;
    if (handshakeTimeoutMs > 0) {
      handshakeTimer = scheduler.schedule(
          () -> failHandshake(BridgeProtocol.Errors.HANDSHAKE_TIMEOUT, handshakeTimeoutMs + "ms 内未完成握手"),
          handshakeTimeoutMs, TimeUnit.MILLISECONDS);
    }
    if (sendHelloFirst) {
      send(selfFrame(BridgeProtocol.Frames.HELLO));
    } else {
      jlogger.debug("[NeonaicBridge] 以被动端姿态等待对端 hello（会话 %s）", sessionId);
    }
  }

  /** 底层收到一段文本 */
  public void acceptChunk(String chunk) {
    if (state == State.CLOSED) return;
    List<Map<String, Object>> frames;
    try {
      frames = codec.push(chunk);
    } catch (BridgeException e) {
      listener.onError(e);
      teardown.accept("分帧失败：" + e.getMessage());
      return;
    }
    for (Map<String, Object> frame : frames) {
      try {
        dispatch(frame);
      } catch (Exception e) {
        jlogger.error("[NeonaicBridge] 处理帧 %s 时出错：%s", str(frame.get("type")), e.getLocalizedMessage(), e);
        listener.onError(e);
      }
    }
  }

  /** 底层连接已断开 */
  public void transportClosed(String reason) {
    if (state == State.CLOSED) return;
    cancelTimers();
    rejectAll(new BridgeException(BridgeProtocol.Errors.TIMEOUT, "通道关闭：" + reason));
    state = State.CLOSED;
    listener.onClosed(reason);
  }

  /** 主动关闭会话 */
  public void close(String reason) {
    if (state == State.CLOSED) return;
    transportClosed(reason);
    teardown.accept(reason);
  }

  // ---- 发送 ----

  /**
   * 发送一次请求并等待应答。
   *
   * @return 应答帧的 payload
   */
  public CompletableFuture<Map<String, Object>> request(String method, Map<String, Object> payload, long timeoutMs) {
    if (!ready()) {
      return failedFuture(new BridgeException(BridgeProtocol.Errors.NOT_HANDSHAKEN,
          "通道尚未就绪（当前状态：" + state + "），无法发送请求 " + method));
    }
    String id = nextId();
    Map<String, Object> frame = baseFrame(BridgeProtocol.Frames.REQUEST);
    frame.put("id", id);
    frame.put("method", method);
    frame.put("payload", payload != null ? payload : new LinkedHashMap<String, Object>());

    Pending p = new Pending(method);
    long limit = timeoutMs > 0 ? timeoutMs : requestTimeoutMs;
    if (limit > 0) {
      p.timer = scheduler.schedule(() -> {
        if (pending.remove(id) != null) {
          p.future.completeExceptionally(new BridgeException(BridgeProtocol.Errors.TIMEOUT,
              "请求 " + method + "（id=" + id + "）在 " + limit + "ms 内未收到应答"));
        }
      }, limit, TimeUnit.MILLISECONDS);
    }
    pending.put(id, p);
    try {
      write.accept(FrameCodec.encode(frame));
    } catch (Exception e) {
      pending.remove(id);
      p.cancelTimer();
      p.future.completeExceptionally(e);
    }
    return p.future;
  }

  /** 发送单向事件帧（不等待应答） */
  public boolean notify(String name, Map<String, Object> payload) {
    if (!ready()) return false;
    Map<String, Object> frame = baseFrame(BridgeProtocol.Frames.EVENT);
    frame.put("id", nextId());
    frame.put("method", name);
    frame.put("payload", payload != null ? payload : new LinkedHashMap<String, Object>());
    return send(frame);
  }

  /** 发送一条自定义帧（供传输层/门面在握手前发送） */
  public boolean sendRaw(Map<String, Object> frame) {
    return send(frame);
  }

  // ---- 内部：分发 ----

  private void dispatch(Map<String, Object> frame) {
    String type = str(frame.get("type"));
    if (type == null) {
      sendError(str(frame.get("id")), BridgeProtocol.Errors.BAD_FRAME, "帧缺少 type");
      return;
    }

    switch (type) {
      case BridgeProtocol.Frames.HELLO:
        onHandshake(BridgeProtocol.Frames.HELLO, frame);
        return;
      case BridgeProtocol.Frames.WELCOME:
        onHandshake(BridgeProtocol.Frames.WELCOME, frame);
        return;
      case BridgeProtocol.Frames.PING:
        replyPong(frame);
        return;
      case BridgeProtocol.Frames.PONG:
        settle(str(frame.get("id")), asMap(frame.get("payload")));
        return;
      default:
        break;
    }

    if (state != State.READY) {
      // 尚未握手就发业务帧：属对端实现缺陷，回错误帧但不撕破脸（可能只是时序竞争）
      BridgeException err = new BridgeException(BridgeProtocol.Errors.NOT_HANDSHAKEN, "未完成握手就收到 " + type + " 帧");
      jlogger.warn("[NeonaicBridge] %s", err.getMessage());
      listener.onError(err);
      String id = str(frame.get("id"));
      if (id != null) sendError(id, BridgeProtocol.Errors.NOT_HANDSHAKEN, err.getMessage());
      return;
    }

    switch (type) {
      case BridgeProtocol.Frames.REQUEST:
        onRequest(frame);
        return;
      case BridgeProtocol.Frames.RESPONSE:
        onResponse(frame);
        return;
      case BridgeProtocol.Frames.EVENT:
        listener.onEvent(frame);
        return;
      default:
        String id = str(frame.get("id"));
        if (id != null) sendError(id, BridgeProtocol.Errors.BAD_FRAME, "未知帧类型：" + type);
        listener.onError(new BridgeException(BridgeProtocol.Errors.BAD_FRAME, "未知帧类型：" + type));
    }
  }

  private void onHandshake(String type, Map<String, Object> frame) {
    // 对端可能在 beginHandshake 之前就把数据送上来了（accept 后立刻握手）
    if (state == State.IDLE) {
      jlogger.debug("[NeonaicBridge] 握手帧早于 beginHandshake 到达，已补启动握手");
      beginHandshake();
    }
    if (state == State.READY) {
      jlogger.warn("[NeonaicBridge] 已握手完成，忽略重复的 %s 帧", type);
      return;
    }
    if (state != State.HANDSHAKING) return;

    String mismatch = checkPeerIdentity(frame);
    if (mismatch != null) {
      failHandshake(BridgeProtocol.Errors.UNSUPPORTED_VERSION, mismatch);
      return;
    }

    peer = new LinkedHashMap<>(frame);

    // 被动端收到 hello → 回 welcome；主动端收到 welcome → 直接完成。
    // 主动端若收到的是 hello（对端也在主动），则回 welcome 并完成，避免双方僵住。
    if (BridgeProtocol.Frames.HELLO.equals(type)) {
      send(selfFrame(BridgeProtocol.Frames.WELCOME));
    }
    finishHandshake();
  }

  private void finishHandshake() {
    cancelHandshakeTimer();
    state = State.READY;
    jlogger.info("[NeonaicBridge] 握手成功：对端 %s/%s v%s（会话 %s）",
        str(peer.get("role")), str(peer.get("name")), str(peer.get("version")), sessionId);
    startHeartbeat();
    listener.onReady(peer);
  }

  private void failHandshake(String code, String message) {
    BridgeException err = new BridgeException(code, "握手失败（" + code + "）：" + message);
    jlogger.error("[NeonaicBridge] %s", err.getMessage());
    listener.onError(err);
    Map<String, Object> f = baseFrame(BridgeProtocol.Frames.RESPONSE);
    Map<String, Object> errorObj = new LinkedHashMap<>();
    errorObj.put("code", code);
    errorObj.put("message", message);
    f.put("error", errorObj);
    send(f);
    transportClosed("握手失败（" + code + "）");
    teardown.accept("握手失败（" + code + "）");
  }

  private void onRequest(Map<String, Object> frame) {
    String id = str(frame.get("id"));
    String method = str(frame.get("method"));
    if (id == null) {
      jlogger.warn("[NeonaicBridge] 收到缺 id 的请求帧，已丢弃");
      return;
    }
    if (method == null) {
      sendError(id, BridgeProtocol.Errors.INVALID_ARGUMENT, "请求缺少 method");
      return;
    }
    MethodHandler handler = methods.get(method);
    if (handler == null) {
      sendError(id, BridgeProtocol.Errors.UNKNOWN_METHOD, "未实现的方法：" + method);
      return;
    }
    Map<String, Object> payload = asMap(frame.get("payload"));
    // 在调度器上执行，避免阻塞读取线程
    scheduler.execute(() -> {
      try {
        Map<String, Object> out = handler.handle(payload != null ? payload : new LinkedHashMap<>());
        sendResponse(id, method, out != null ? out : new LinkedHashMap<>());
      } catch (BridgeException e) {
        sendError(id, e.code(), e.getMessage());
      } catch (Exception e) {
        jlogger.error("[NeonaicBridge] 处理方法 %s 失败：%s", method, e.getLocalizedMessage(), e);
        sendError(id, BridgeProtocol.Errors.INTERNAL_ERROR, String.valueOf(e.getMessage()));
      }
    });
  }

  private void onResponse(Map<String, Object> frame) {
    String id = str(frame.get("id"));
    if (id == null) {
      jlogger.warn("[NeonaicBridge] 收到无 id 的应答帧，已丢弃");
      return;
    }
    Pending p = pending.remove(id);
    if (p == null) {
      jlogger.warn("[NeonaicBridge] 收到无对应请求的应答帧（id=%s），已丢弃", id);
      return;
    }
    p.cancelTimer();

    boolean ok = !Boolean.FALSE.equals(frame.get("ok")) && frame.get("error") == null;
    if (!ok) {
      Map<String, Object> err = asMap(frame.get("error"));
      String code = err != null ? str(err.get("code")) : null;
      String msg = err != null ? str(err.get("message")) : null;
      p.future.completeExceptionally(new BridgeException(
          code != null ? code : BridgeProtocol.Errors.INTERNAL_ERROR,
          msg != null ? msg : "对端返回了未说明原因的失败"));
      return;
    }
    p.future.complete(asMap(frame.get("payload")));
  }

  private void replyPong(Map<String, Object> frame) {
    Map<String, Object> f = baseFrame(BridgeProtocol.Frames.PONG);
    f.put("id", frame.get("id"));
    send(f);
  }

  private void settle(String id, Map<String, Object> payload) {
    if (id == null) return;
    Pending p = pending.remove(id);
    if (p == null) return;
    p.cancelTimer();
    p.future.complete(payload);
  }

  // ---- 内部：发送与小工具 ----

  private boolean send(Map<String, Object> frame) {
    if (state == State.CLOSED) return false;
    try {
      write.accept(FrameCodec.encode(frame));
      return true;
    } catch (Exception e) {
      jlogger.error("[NeonaicBridge] 写出帧失败：%s", e.getLocalizedMessage(), e);
      listener.onError(e);
      return false;
    }
  }

  private void sendResponse(String id, String method, Map<String, Object> payload) {
    Map<String, Object> f = baseFrame(BridgeProtocol.Frames.RESPONSE);
    f.put("id", id);
    f.put("method", method);
    f.put("ok", Boolean.TRUE);
    f.put("payload", payload);
    send(f);
  }

  private void sendError(String id, String code, String message) {
    Map<String, Object> f = baseFrame(BridgeProtocol.Frames.RESPONSE);
    f.put("id", id);
    f.put("ok", Boolean.FALSE);
    Map<String, Object> errorObj = new LinkedHashMap<>();
    errorObj.put("code", code);
    errorObj.put("message", message);
    f.put("error", errorObj);
    send(f);
  }

  private Map<String, Object> baseFrame(String type) {
    Map<String, Object> f = new LinkedHashMap<>();
    f.put("v", BridgeProtocol.PROTOCOL_VERSION);
    f.put("type", type);
    f.put("id", null);
    f.put("ts", System.currentTimeMillis());
    return f;
  }

  private Map<String, Object> selfFrame(String type) {
    Map<String, Object> f = baseFrame(type);
    f.put("protocol", BridgeProtocol.PROTOCOL_NAME);
    f.put("role", role);
    f.put("name", self.name());
    f.put("subname", self.subname() != null ? self.subname() : "");
    f.put("version", self.version());
    f.put("capabilities", self.capabilities());
    f.put("session", sessionId);
    return f;
  }

  private String nextId() {
    return sessionId + "#" + seq.incrementAndGet();
  }

  private String checkPeerIdentity(Map<String, Object> frame) {
    if (!BridgeProtocol.PROTOCOL_NAME.equals(str(frame.get("protocol")))) {
      return "协议名不匹配（对端为 " + str(frame.get("protocol")) + "，本端要求 " + BridgeProtocol.PROTOCOL_NAME + "）";
    }
    Long v = asLong(frame.get("v"));
    if (v == null || v != BridgeProtocol.PROTOCOL_VERSION) {
      return "协议主版本不匹配（对端为 " + str(frame.get("v")) + "，本端为 " + BridgeProtocol.PROTOCOL_VERSION + "）";
    }
    if (!peerRoleExpected.equals(str(frame.get("role")))) {
      return "对端角色不是 " + peerRoleExpected + "（对端自称 " + str(frame.get("role")) + "）";
    }
    return null;
  }

  private void startHeartbeat() {
    if (heartbeatMs <= 0) return;
    heartbeatTimer = scheduler.scheduleAtFixedRate(() -> {
      if (!ready()) return;
      String id = nextId();
      Pending p = new Pending(BridgeProtocol.Frames.PING);
      long limit = requestTimeoutMs > 0 ? requestTimeoutMs : BridgeProtocol.Timeouts.REQUEST;
      p.timer = scheduler.schedule(() -> {
        if (pending.remove(id) != null) {
          jlogger.warn("[NeonaicBridge] 心跳未获应答，判定链路已失效");
          teardown.accept("心跳超时");
        }
      }, limit, TimeUnit.MILLISECONDS);
      pending.put(id, p);
      Map<String, Object> f = baseFrame(BridgeProtocol.Frames.PING);
      f.put("id", id);
      if (!send(f)) {
        pending.remove(id);
        p.cancelTimer();
      }
    }, heartbeatMs, heartbeatMs, TimeUnit.MILLISECONDS);
  }

  private void cancelHandshakeTimer() {
    ScheduledFuture<?> t = handshakeTimer;
    if (t != null) {
      t.cancel(false);
      handshakeTimer = null;
    }
  }

  private void cancelTimers() {
    cancelHandshakeTimer();
    ScheduledFuture<?> h = heartbeatTimer;
    if (h != null) {
      h.cancel(false);
      heartbeatTimer = null;
    }
  }

  private void rejectAll(Throwable err) {
    for (Map.Entry<String, Pending> e : pending.entrySet()) {
      Pending p = pending.remove(e.getKey());
      if (p == null) continue;
      p.cancelTimer();
      p.future.completeExceptionally(err);
    }
  }

  private static <T> CompletableFuture<T> failedFuture(Throwable e) {
    CompletableFuture<T> f = new CompletableFuture<>();
    f.completeExceptionally(e);
    return f;
  }

  // ---- 类型转换小工具 ----

  private static String str(Object o) {
    return o == null ? null : String.valueOf(o);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> asMap(Object o) {
    return (o instanceof Map<?, ?> m) ? (Map<String, Object>) m : null;
  }

  private static Long asLong(Object o) {
    if (o instanceof Number n) return n.longValue();
    if (o instanceof String s) {
      try {
        return Long.parseLong(s.trim());
      } catch (NumberFormatException ignored) {
        return null;
      }
    }
    return null;
  }
}
