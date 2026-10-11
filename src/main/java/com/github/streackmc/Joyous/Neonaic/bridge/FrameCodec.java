package com.github.streackmc.Joyous.Neonaic.bridge;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import com.github.streackmc.Joyous.jlogger;

/**
 * 「JSON + LF」帧编解码器（V1 §4 / V2 §1）。
 * <p>
 * 编码：一帧 = 一行 JSON + 一个 LF。{@code JSON.stringify}/{@code toJSONString} 都不会在
 * 输出中产生裸换行（字符串里的换行会被写成 {@code \n} 两个字符），因此 LF 只可能出现在帧尾。
 * <p>
 * 解码：增量式。TCP/UDS 是字节流，一次读取既可能只给半帧（半包），也可能一次给多帧（粘包），
 * 因此维护残余缓冲并只在凑齐 LF 时出帧；同时容忍 CRLF、首尾空白、空行与坏帧。
 *
 * @since 0.3.0
 */
public class FrameCodec {

  /** 单帧字节上限 */
  private final int maxBytes;

  /** 尚未凑成完整帧的残余文本（不含分隔符） */
  private final StringBuilder rest = new StringBuilder();

  public FrameCodec() {
    this(BridgeProtocol.MAX_FRAME_BYTES);
  }

  public FrameCodec(int maxBytes) {
    if (maxBytes <= 0) throw new IllegalArgumentException("单帧上限必须为正：" + maxBytes);
    this.maxBytes = maxBytes;
  }

  /**
   * 把一条消息编码为可写出的整帧。
   *
   * @param frame 帧对象
   * @return 一行 JSON + LF
   * @throws BridgeException 编码失败
   */
  public static String encode(Map<String, Object> frame) {
    try {
      JSONObject obj = new JSONObject();
      obj.putAll(frame);
      return obj.toJSONString() + BridgeProtocol.FRAME_DELIMITER;
    } catch (Exception e) {
      throw new BridgeException(BridgeProtocol.Errors.BAD_FRAME,
          "帧无法序列化为 JSON：" + e.getLocalizedMessage(), e);
    }
  }

  /**
   * 送入一段文本，切出其中已完整的帧。
   *
   * @param chunk 已按 UTF-8 解码的文本片段
   * @return 成功解析出的帧，按到达顺序；坏帧已被记录并丢弃
   * @throws BridgeException 残余缓冲超过单帧上限（缓冲已被丢弃）
   */
  public List<Map<String, Object>> push(String chunk) {
    List<Map<String, Object>> frames = new ArrayList<>();
    if (chunk == null || chunk.isEmpty()) return frames;

    rest.append(chunk);
    int cut;
    while ((cut = rest.indexOf(BridgeProtocol.FRAME_DELIMITER)) >= 0) {
      String raw = rest.substring(0, cut);
      rest.delete(0, cut + 1);

      // 容忍 CRLF 与首尾空白
      String line = raw.strip();
      if (line.isEmpty()) continue;

      Object parsed;
      try {
        parsed = new JSONParser().parse(line);
      } catch (Exception e) {
        jlogger.warn("桥接收到无法解析的帧（已丢弃）：%s｜原文：%s", e.getLocalizedMessage(), preview(line));
        continue;
      }
      if (parsed instanceof Map<?, ?> map) {
        @SuppressWarnings("unchecked")
        Map<String, Object> frame = (Map<String, Object>) map;
        frames.add(frame);
      } else {
        jlogger.warn("桥接收到非 JSON 对象的帧（已丢弃）：%s", preview(line));
      }
    }

    // 对端始终不发分隔符时的兜底：宁可断开也不让缓冲无限膨胀
    if (pendingBytes() > maxBytes) {
      int pending = pendingBytes();
      reset();
      throw new BridgeException(BridgeProtocol.Errors.FRAME_TOO_LARGE,
          "单帧长度超过上限（" + pending + " > " + maxBytes + " 字节），已丢弃接收缓冲");
    }

    return frames;
  }

  /** 是否还压着半包未出 */
  public boolean pending() {
    return rest.length() > 0;
  }

  /** 当前残余缓冲的字节数 */
  public int pendingBytes() {
    return rest.toString().getBytes(StandardCharsets.UTF_8).length;
  }

  /** 丢弃残余半包（重连 / 复位时调用） */
  public void reset() {
    rest.setLength(0);
  }

  private static String preview(String line) {
    return line.length() > 200 ? line.substring(0, 200) + "…" : line;
  }
}
