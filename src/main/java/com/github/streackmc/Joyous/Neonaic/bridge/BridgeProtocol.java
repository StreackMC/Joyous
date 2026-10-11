package com.github.streackmc.Joyous.Neonaic.bridge;

/**
 * Joyous ↔ Neonaic 桥接协议常量（V2）。
 * <p>
 * 本类是 Java 侧的**唯一事实来源**，与 {@code docs/neonaic/PROTOCOL-V2.md}
 * 及 Neonaic 侧 {@code src/protocol/constants.js} 一一对应。
 *
 * @since 0.3.0
 */
public final class BridgeProtocol {

  /** 协议名称（握手时交换，用于拒绝「连错端口 / 连错对端」） */
  public static final String PROTOCOL_NAME = "neonai-joyous-bridge";

  /** 协议主版本：V2 引入 chat.* / ai.* / identity.* 方法族，主版本必须完全一致 */
  public static final int PROTOCOL_VERSION = 2;

  /** 帧分隔符：一帧 = 一行 JSON + 一个 LF */
  public static final String FRAME_DELIMITER = "\n";

  /** 单帧字节上限（1 MiB） */
  public static final int MAX_FRAME_BYTES = 1 << 20;

  /** HTTP 请求体上限（256 KiB，V1 §9.1） */
  public static final int MAX_HTTP_BODY_BYTES = 256 * 1024;

  /** 角色标识 */
  public static final class Roles {
    public static final String NEONAI = "neonai";
    public static final String JOYOUS = "joyous";

    private Roles() {
    }
  }

  /** 帧类型 */
  public static final class Frames {
    public static final String HELLO = "hello";
    public static final String WELCOME = "welcome";
    public static final String REQUEST = "request";
    public static final String RESPONSE = "response";
    public static final String EVENT = "event";
    public static final String PING = "ping";
    public static final String PONG = "pong";

    private Frames() {
    }
  }

  /** 方法名 */
  public static final class Methods {
    public static final String COMMAND_EXECUTE = "command.execute";
    public static final String STATUS_QUERY = "status.query";
    public static final String CHAT_PUBLISH = "chat.publish";
    public static final String CHAT_BROADCAST = "chat.broadcast";
    public static final String AI_REQUEST = "ai.request";
    public static final String IDENTITY_BIND = "identity.bind";
    public static final String IDENTITY_RESOLVE = "identity.resolve";
    public static final String PING = "ping";

    private Methods() {
    }
  }

  /** 能力常量（握手帧 capabilities） */
  public static final class Capabilities {
    public static final String COMMAND_EXECUTE = "command.execute";
    public static final String STATUS_QUERY = "status.query";
    public static final String CHAT_PUBLISH = "chat.publish";
    public static final String CHAT_BROADCAST = "chat.broadcast";
    public static final String AI_REQUEST = "ai.request";
    public static final String IDENTITY_BIND = "identity.bind";
    public static final String IDENTITY_RESOLVE = "identity.resolve";

    private Capabilities() {
    }
  }

  /** 协议错误码（V1 §8 + V2 §7） */
  public static final class Errors {
    public static final String BAD_FRAME = "bad_frame";
    public static final String FRAME_TOO_LARGE = "frame_too_large";
    public static final String UNSUPPORTED_VERSION = "unsupported_version";
    public static final String HANDSHAKE_TIMEOUT = "handshake_timeout";
    public static final String NOT_HANDSHAKEN = "not_handshaken";
    public static final String UNKNOWN_METHOD = "unknown_method";
    public static final String INVALID_ARGUMENT = "invalid_argument";
    public static final String UNKNOWN_COMMAND = "unknown_command";
    public static final String PERMISSION_DENIED = "permission_denied";
    public static final String COMMAND_FAILED = "command_failed";
    public static final String TIMEOUT = "timeout";
    public static final String INTERNAL_ERROR = "internal_error";
    public static final String NOT_BOUND = "not_bound";
    public static final String RATE_LIMITED = "rate_limited";

    private Errors() {
    }
  }

  /** 默认超时（毫秒） */
  public static final class Timeouts {
    public static final long CONNECT = 5000L;
    public static final long HANDSHAKE = 5000L;
    public static final long REQUEST = 10000L;
    public static final long COMMAND = 30000L;
    public static final long HEARTBEAT = 30000L;

    private Timeouts() {
    }
  }

  private BridgeProtocol() {
  }
}
