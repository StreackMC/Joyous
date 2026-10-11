package com.github.streackmc.Joyous.Neonaic.bridge;

/**
 * 桥接协议异常：携带协议错误码（见 {@link BridgeProtocol.Errors}）。
 * <p>
 * 与 V1/V2 文档 §8 的错误码一一对应；凡是需要通过 {@code response.error.code}
 * 回传给对端的失败，都应抛出本异常。
 *
 * @since 0.3.0
 */
public class BridgeException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  private final String code;

  public BridgeException(String code, String message) {
    super(message);
    this.code = code;
  }

  public BridgeException(String code, String message, Throwable cause) {
    super(message, cause);
    this.code = code;
  }

  /** 协议错误码 */
  public String code() {
    return code;
  }
}
