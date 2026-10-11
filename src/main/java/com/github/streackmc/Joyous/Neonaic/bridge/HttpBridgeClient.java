package com.github.streackmc.Joyous.Neonaic.bridge;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import com.github.streackmc.Joyous.jlogger;

/**
 * HTTP 通道客户端（PROTOCOL §9 / V2 §5.6）。
 * <p>
 * HTTP 模式下 Joyous 是**客户端**：把命令执行与 V2 方法调用 POST 给 Neonaic。
 * <ul>
 *   <li>{@link #executeCommand} → {@code POST {commandUrl}}，承载 {@code command.execute}（V1 契约）；</li>
 *   <li>{@link #invoke} → {@code POST {invokeUrl}}，承载 {@code chat.publish} / {@code ai.request} /
 *       {@code identity.*}（V2 通用信封 {@code { id, method, payload }}）。</li>
 * </ul>
 *
 * @since 0.3.0
 */
public class HttpBridgeClient {

  private final HttpClient client;
  private final long timeoutMs;
  private final AtomicLong seq = new AtomicLong();

  public HttpBridgeClient(long timeoutMs) {
    this.timeoutMs = timeoutMs > 0 ? timeoutMs : BridgeProtocol.Timeouts.REQUEST;
    this.client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(Math.max(1000, this.timeoutMs)))
        .build();
  }

  /**
   * 通用调用（V2 §5.6）：POST {@code { id, method, payload }}。
   *
   * @return 成功时的 {@code payload}
   */
  public Map<String, Object> invoke(String url, String token, String method, Map<String, Object> payload) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("id", "joyous-" + seq.incrementAndGet());
    body.put("method", method);
    body.put("payload", payload != null ? payload : new LinkedHashMap<String, Object>());

    Map<String, Object> resp = post(url, token, body);
    Object okObj = resp.get("ok");
    if (Boolean.FALSE.equals(okObj) || resp.get("code") != null) {
      throw errorOf(resp, "调用 " + method + " 失败");
    }
    Map<String, Object> result = asMap(resp.get("payload"));
    return result != null ? result : new LinkedHashMap<>();
  }

  /**
   * 命令执行（V1 §9.1）：POST 命令端点。
   *
   * @param commandPayload {@code { input }} 或 {@code { command, args }}
   * @return 完整的响应体（含 {@code ref/namespace/name/output/truncated}）
   */
  public Map<String, Object> executeCommand(String url, String token, Map<String, Object> commandPayload) {
    Map<String, Object> body = new LinkedHashMap<>(commandPayload);
    body.putIfAbsent("id", "joyous-" + seq.incrementAndGet());

    Map<String, Object> resp = post(url, token, body);
    if (Boolean.FALSE.equals(resp.get("ok")) || resp.get("code") != null) {
      throw errorOf(resp, "命令执行失败");
    }
    return resp;
  }

  // ---- 内部 ----

  private Map<String, Object> post(String url, String token, Map<String, Object> body) {
    if (url == null || url.isBlank()) {
      throw new BridgeException(BridgeProtocol.Errors.INVALID_ARGUMENT, "HTTP 端点地址为空");
    }

    String json;
    try {
      JSONObject obj = new JSONObject();
      obj.putAll(body);
      json = obj.toJSONString();
    } catch (Exception e) {
      throw new BridgeException(BridgeProtocol.Errors.BAD_FRAME, "请求体无法序列化：" + e.getLocalizedMessage(), e);
    }

    HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
        .timeout(Duration.ofMillis(timeoutMs))
        .header("Content-Type", "application/json; charset=utf-8")
        .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
    if (token != null && !token.isBlank()) {
      builder.header("X-Neonai-Token", token);
    }

    HttpResponse<String> response;
    try {
      response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new BridgeException(BridgeProtocol.Errors.TIMEOUT, "HTTP 请求被中断：" + e.getLocalizedMessage(), e);
    } catch (Exception e) {
      throw new BridgeException(BridgeProtocol.Errors.TIMEOUT, "HTTP 请求失败：" + e.getLocalizedMessage(), e);
    }

    String text = response.body();
    if (text == null || text.isBlank()) {
      throw new BridgeException(BridgeProtocol.Errors.INTERNAL_ERROR,
          "HTTP 响应为空（状态码 " + response.statusCode() + "）");
    }

    Object parsed;
    try {
      parsed = new JSONParser().parse(text);
    } catch (Exception e) {
      throw new BridgeException(BridgeProtocol.Errors.BAD_FRAME, "HTTP 响应不是合法 JSON：" + e.getLocalizedMessage(), e);
    }
    Map<String, Object> resp = asMap(parsed);
    if (resp == null) {
      throw new BridgeException(BridgeProtocol.Errors.BAD_FRAME, "HTTP 响应不是 JSON 对象");
    }

    if (response.statusCode() >= 400) {
      jlogger.debug("[NeonaicBridge] HTTP %d：%s", response.statusCode(), text);
    }
    return resp;
  }

  private BridgeException errorOf(Map<String, Object> resp, String fallback) {
    String code = resp.get("code") != null ? String.valueOf(resp.get("code")) : BridgeProtocol.Errors.INTERNAL_ERROR;
    Object message = resp.get("message");
    if (message == null) message = resp.get("error");
    return new BridgeException(code, message != null ? String.valueOf(message) : fallback);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> asMap(Object o) {
    return (o instanceof Map<?, ?> m) ? (Map<String, Object>) m : null;
  }
}
