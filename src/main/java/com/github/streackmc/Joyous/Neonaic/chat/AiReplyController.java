package com.github.streackmc.Joyous.Neonaic.chat;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.github.streackmc.Joyous.Neonaic.NeonaicConfig;

/**
 * AI 回复触发控制与上下文窗口（{@code ai.reply-chat-randomly}）。
 * <p>
 * 触发判定**在本侧完成**：随机回复需同时满足「玩家间隔」「全局间隔」「概率」；
 * 上下文窗口按 {@code when-is-recent} 裁剪时间、按 {@code max-of-trigger} /
 * {@code max-of-others} 裁剪条数。
 *
 * @since 0.3.0
 */
public class AiReplyController {

  /** 一条聊天记录 */
  private record Record(String speaker, UUID uuid, String message, long ts, boolean contextEligible) {
  }

  private final NeonaicConfig config;
  private final Map<UUID, Long> lastPlayerTrigger = new ConcurrentHashMap<>();
  private final Deque<Record> history = new ArrayDeque<>();
  private final Object lock = new Object();

  private volatile long lastGlobalTrigger = 0L;

  public AiReplyController(NeonaicConfig config) {
    this.config = config;
  }

  /** 记录一条聊天（{@code contextEligible} 表示是否计入上下文） */
  public void recordChat(String speaker, UUID uuid, String message, boolean contextEligible) {
    long now = System.currentTimeMillis();
    synchronized (lock) {
      history.addLast(new Record(speaker, uuid, message, now, contextEligible));
      trimLocked(now);
    }
  }

  /**
   * 随机回复判定：满足间隔与概率则返回 true 并**立即记账**。
   */
  public boolean shouldRandomReply(UUID uuid) {
    long now = System.currentTimeMillis();
    int intervalPlayer = config.aiRandomIntervalPlayer();
    int intervalGlobal = config.aiRandomIntervalGlobal();
    double possibility = config.aiRandomPossibility();
    if (possibility <= 0) return false;

    synchronized (lock) {
      Long last = lastPlayerTrigger.get(uuid);
      if (last != null && now - last < intervalPlayer * 1000L) return false;
      if (now - lastGlobalTrigger < intervalGlobal * 1000L) return false;
      if (Math.random() >= possibility) return false;
      lastPlayerTrigger.put(uuid, now);
      lastGlobalTrigger = now;
      return true;
    }
  }

  /** 显式记账一次触发（@ 触发 / 命令触发时调用） */
  public void markTrigger(UUID uuid) {
    long now = System.currentTimeMillis();
    synchronized (lock) {
      if (uuid != null) lastPlayerTrigger.put(uuid, now);
      lastGlobalTrigger = now;
    }
  }

  /** 为触发者构建上下文（时间升序） */
  public List<Map<String, Object>> buildContext(UUID triggerer) {
    long now = System.currentTimeMillis();
    List<Record> snapshot;
    synchronized (lock) {
      trimLocked(now);
      snapshot = new ArrayList<>(history);
    }

    List<Record> triggers = new ArrayList<>();
    List<Record> others = new ArrayList<>();
    for (Record r : snapshot) {
      if (!r.contextEligible()) continue;
      if (r.uuid() != null && r.uuid().equals(triggerer)) {
        triggers.add(r);
      } else {
        others.add(r);
      }
    }

    triggers = takeLast(triggers, config.aiContextMaxTrigger());
    others = takeLast(others, config.aiContextMaxOthers());

    List<Record> merged = new ArrayList<>();
    merged.addAll(triggers);
    merged.addAll(others);
    merged.sort(Comparator.comparingLong(Record::ts));

    List<Map<String, Object>> out = new ArrayList<>(merged.size());
    for (Record r : merged) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("speaker", r.speaker());
      m.put("message", r.message());
      m.put("ts", r.ts());
      out.add(m);
    }
    return out;
  }

  // ---- 内部 ----

  private void trimLocked(long now) {
    long windowMs = config.aiContextWhenRecent() * 1000L;
    while (!history.isEmpty() && now - history.peekFirst().ts() > windowMs) {
      history.pollFirst();
    }
    // 兜底：即使窗口很长，也限制总量，避免无界增长
    while (history.size() > 1000) {
      history.pollFirst();
    }
  }

  /** 取末尾至多 max 条（max<=0 表示不限） */
  private static List<Record> takeLast(List<Record> list, int max) {
    if (max <= 0 || list.size() <= max) return list;
    return new ArrayList<>(list.subList(list.size() - max, list.size()));
  }
}
